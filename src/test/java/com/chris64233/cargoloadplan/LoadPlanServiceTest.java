package com.chris64233.cargoloadplan;

import com.chris64233.cargoloadplan.dto.AdjustPlanRequest;
import com.chris64233.cargoloadplan.dto.AssignmentRequest;
import com.chris64233.cargoloadplan.dto.ConstraintReportResponse;
import com.chris64233.cargoloadplan.dto.CreateFlightRequest;
import com.chris64233.cargoloadplan.dto.CreatePlanRequest;
import com.chris64233.cargoloadplan.dto.HoldRequest;
import com.chris64233.cargoloadplan.dto.HoldUsageResponse;
import com.chris64233.cargoloadplan.dto.PlanResponse;
import com.chris64233.cargoloadplan.dto.PlanVersionResponse;
import com.chris64233.cargoloadplan.dto.RecordUnloadRequest;
import com.chris64233.cargoloadplan.dto.RegisterCargoRequest;
import com.chris64233.cargoloadplan.dto.UpdateFlightConfigRequest;
import com.chris64233.cargoloadplan.dto.UpdateWeightRequest;
import com.chris64233.cargoloadplan.dto.WhereaboutsResponse;
import com.chris64233.cargoloadplan.service.CargoService;
import com.chris64233.cargoloadplan.service.ConflictException;
import com.chris64233.cargoloadplan.service.FlightService;
import com.chris64233.cargoloadplan.service.LoadConstraintException;
import com.chris64233.cargoloadplan.service.LoadPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配载方案核心业务规则集成测试。
 * 测试航班统一几何：空机 1000kg @ 力臂 10，重心区间 [8, 12]，
 * 货舱 FWD(力臂 5) / AFT(力臂 15)，默认载重上限足够大。
 */
@SpringBootTest
class LoadPlanServiceTest {

    @Autowired
    FlightService flightService;
    @Autowired
    CargoService cargoService;
    @Autowired
    LoadPlanService loadPlanService;

    // 静态计数器：JUnit 每个测试方法新建实例，共享 H2 库内编号需跨实例唯一
    private static final AtomicInteger seq = new AtomicInteger();

    private String id(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private String newFlight() {
        return newFlight(10000, 10000);
    }

    private String newFlight(double fwdMaxWeight, double aftMaxWeight) {
        String flightNo = id("CA");
        flightService.createFlight(new CreateFlightRequest(flightNo, 1000, 10, 8, 12,
                List.of(new HoldRequest("FWD", fwdMaxWeight, 10000, 5, Set.of()),
                        new HoldRequest("AFT", aftMaxWeight, 10000, 15, Set.of()))));
        return flightNo;
    }

    private String newUnit(double weight, String category, Set<String> incompatible) {
        String unitNo = id("U");
        cargoService.register(new RegisterCargoRequest(unitNo, weight, 10, category, incompatible));
        return unitNo;
    }

    private String newUnit(double weight) {
        return newUnit(weight, "GEN", Set.of());
    }

    private AdjustPlanRequest adjust(String changeNo, List<AssignmentRequest> moves, List<String> unload) {
        return new AdjustPlanRequest(changeNo, moves, unload);
    }

    private void createAndConfirm(String flight, String planNo, List<AssignmentRequest> assignments) {
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo, assignments));
        loadPlanService.confirm(planNo);
    }

    // ---------- 方案号幂等 ----------

    @Test
    void createPlanIsIdempotentByPlanNo() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        CreatePlanRequest req = new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD")));

        PlanResponse first = loadPlanService.createPlan(flight, req);
        PlanResponse second = loadPlanService.createPlan(flight, req);

        assertEquals("DRAFT", first.status());
        assertEquals(first.planNo(), second.planNo());
        assertEquals(first.assignments(), second.assignments());
        assertEquals(1, first.currentVersionNo());
    }

    @Test
    void confirmIsIdempotent() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));

        PlanVersionResponse first = loadPlanService.confirm(planNo);
        PlanVersionResponse second = loadPlanService.confirm(planNo);

        assertEquals("CONFIRMED", first.status());
        assertEquals(1, first.versionNo());
        assertEquals("CONFIRMED", second.status());
    }

    // ---------- 确认：原子锁定与约束校验 ----------

    @Test
    void confirmLocksAllUnitsAtomically() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "AFT"))));

        loadPlanService.confirm(planNo);

        assertEquals("LOCKED", cargoService.getUnit(u1).status());
        assertEquals("LOCKED", cargoService.getUnit(u2).status());
        assertEquals(planNo, cargoService.getUnit(u1).planNo());

        List<HoldUsageResponse> usage = flightService.holdUsage(flight);
        HoldUsageResponse fwd = usage.stream().filter(h -> h.holdCode().equals("FWD")).findFirst().orElseThrow();
        assertEquals(100, fwd.usedWeight());
        assertEquals(List.of(u1), fwd.loadedUnits());

        ConstraintReportResponse report = flightService.constraints(flight);
        assertTrue(report.withinEnvelope());
        assertEquals(10.0, report.centerOfGravity(), 1e-6);
        assertTrue(report.violations().isEmpty());
    }

    @Test
    void confirmRejectsOverweightAndLeavesNoPartialState() {
        // FWD 上限 150，方案装两件 100kg 货物必然超重
        String flight = newFlight(150, 10000);
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");

        // 准备阶段即做完整预检，超重方案无法建立
        assertThrows(LoadConstraintException.class, () -> loadPlanService.createPlan(flight,
                new CreatePlanRequest(planNo,
                        List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "FWD")))));

        // 不留任何部分状态：货物仍空闲，方案不存在
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());
        assertEquals("AVAILABLE", cargoService.getUnit(u2).status());
        assertThrows(com.chris64233.cargoloadplan.service.NotFoundException.class,
                () -> loadPlanService.getPlan(planNo));
    }

    @Test
    void confirmRevalidatesAgainstCapacityTakenByOtherPlan() {
        // FWD 上限 150：两个方案各装 100kg，先确认者占舱，后确认者必须失败且无部分状态
        String flight = newFlight(150, 10000);
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String p1 = id("P");
        String p2 = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(p1,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.createPlan(flight, new CreatePlanRequest(p2,
                List.of(new AssignmentRequest(u2, "FWD"))));

        loadPlanService.confirm(p1);

        assertThrows(LoadConstraintException.class, () -> loadPlanService.confirm(p2));
        assertEquals("AVAILABLE", cargoService.getUnit(u2).status());
        assertEquals("DRAFT", loadPlanService.getPlan(p2).status());
    }

    @Test
    void planRejectsIncompatibleCargoInSameHold() {
        String flight = newFlight();
        String live = newUnit(100, "LIVE", Set.of("ICE"));
        String ice = newUnit(100, "ICE", Set.of());

        LoadConstraintException ex = assertThrows(LoadConstraintException.class,
                () -> loadPlanService.createPlan(flight, new CreatePlanRequest(id("P"),
                        List.of(new AssignmentRequest(live, "FWD"),
                                new AssignmentRequest(ice, "FWD")))));
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("不可同舱")));

        // 分舱装载则可以通过
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(live, "FWD"), new AssignmentRequest(ice, "AFT"))));
        assertEquals("CONFIRMED", loadPlanService.confirm(planNo).status());
    }

    @Test
    void planRejectsCgOutOfEnvelope() {
        String flight = newFlight();
        String heavy = newUnit(500);
        // 500kg 全部装前舱：重心 (1000*10 + 500*5)/1500 = 8.33... 仍在区间内，改用 1000kg
        String tooHeavy = newUnit(1000);
        // (1000*10 + 1000*5)/2000 = 7.5 < 8，重心超限
        LoadConstraintException ex = assertThrows(LoadConstraintException.class,
                () -> loadPlanService.createPlan(flight, new CreatePlanRequest(id("P"),
                        List.of(new AssignmentRequest(tooHeavy, "FWD")))));
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("重心")));
        assertEquals("AVAILABLE", cargoService.getUnit(heavy).status());
    }

    @Test
    void planRejectsCategoryNotAllowedInHold() {
        String flightNo = id("CA");
        flightService.createFlight(new CreateFlightRequest(flightNo, 1000, 10, 8, 12,
                List.of(new HoldRequest("FWD", 10000, 10000, 5, Set.of("GEN")),
                        new HoldRequest("AFT", 10000, 10000, 15, Set.of("GEN")))));
        String haz = newUnit(100, "HAZ", Set.of());

        LoadConstraintException ex = assertThrows(LoadConstraintException.class,
                () -> loadPlanService.createPlan(flightNo, new CreatePlanRequest(id("P"),
                        List.of(new AssignmentRequest(haz, "FWD")))));
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("不允许类别")));
    }

    // ---------- 快照失效：准备期间货物/航班变化 ----------

    @Test
    void confirmFailsWhenCargoWeightChangedAfterPreparation() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));

        cargoService.updateWeight(u1, new UpdateWeightRequest(120));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> loadPlanService.confirm(planNo));
        assertTrue(ex.getMessage().contains("快照失效"));
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());
        assertEquals("DRAFT", loadPlanService.getPlan(planNo).status());
    }

    @Test
    void confirmFailsWhenFlightConfigChangedAfterPreparation() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));

        flightService.updateConfig(flight, new UpdateFlightConfigRequest(1000, 10, 8, 11,
                List.of(new HoldRequest("FWD", 10000, 10000, 5, Set.of()),
                        new HoldRequest("AFT", 10000, 10000, 15, Set.of()))));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> loadPlanService.confirm(planNo));
        assertTrue(ex.getMessage().contains("快照失效"));
        assertEquals("DRAFT", loadPlanService.getPlan(planNo).status());
    }

    // ---------- 并发争抢：最多一个成功 ----------

    @Test
    void concurrentConfirmOnSameUnitOnlyOneWins() throws Exception {
        String flight = newFlight();
        String u1 = newUnit(100);
        String p1 = id("P");
        String p2 = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(p1,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.createPlan(flight, new CreatePlanRequest(p2,
                List.of(new AssignmentRequest(u1, "AFT"))));

        int successes = raceConfirm(p1, p2);

        assertEquals(1, successes);
        WhereaboutsResponse w = cargoService.whereabouts(u1);
        assertEquals("LOCKED", w.status());
        assertTrue(w.planNo().equals(p1) || w.planNo().equals(p2));
    }

    @Test
    void concurrentConfirmOnRemainingCapacityOnlyOneWins() throws Exception {
        // FWD 上限 150，两个方案各需 100kg，并发确认只能成功一个
        String flight = newFlight(150, 10000);
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String p1 = id("P");
        String p2 = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(p1,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.createPlan(flight, new CreatePlanRequest(p2,
                List.of(new AssignmentRequest(u2, "FWD"))));

        int successes = raceConfirm(p1, p2);

        assertEquals(1, successes);
        List<HoldUsageResponse> usage = flightService.holdUsage(flight);
        HoldUsageResponse fwd = usage.stream().filter(h -> h.holdCode().equals("FWD")).findFirst().orElseThrow();
        assertEquals(100, fwd.usedWeight());
        assertEquals(1, fwd.loadedUnits().size());
    }

    private int raceConfirm(String p1, String p2) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Callable<Boolean> task1 = confirmTask(p1, gate);
        Callable<Boolean> task2 = confirmTask(p2, gate);
        Future<Boolean> f1 = pool.submit(task1);
        Future<Boolean> f2 = pool.submit(task2);
        gate.countDown();
        int successes = (f1.get(30, TimeUnit.SECONDS) ? 1 : 0) + (f2.get(30, TimeUnit.SECONDS) ? 1 : 0);
        pool.shutdownNow();
        return successes;
    }

    private Callable<Boolean> confirmTask(String planNo, CountDownLatch gate) {
        return () -> {
            gate.await();
            try {
                loadPlanService.confirm(planNo);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    // ====================== 临时卸货 / 替换货物：版本化调整（本次新增） ======================

    /** 1、调整形成新版本，准备阶段不触碰实际占用，并按整份方案重新核算。 */
    @Test
    void prepareAdjustCreatesDraftVersionWithoutTouchingLoad() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo,
                List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "AFT")));

        // 把 u2 从 AFT 调到 FWD，形成 v2 草案（CG (10000+500+500)/1200=9.17 在区间内）
        PlanVersionResponse v2 = loadPlanService.prepareAdjust(planNo,
                adjust("C1", List.of(new AssignmentRequest(u2, "FWD")), List.of()));

        assertEquals(2, v2.versionNo());
        assertEquals("C1", v2.changeNo());
        assertEquals("DRAFT", v2.status());
        assertEquals(2, v2.assignments().size());
        assertTrue(v2.assignments().contains(new AssignmentRequest(u2, "FWD")));

        // 实际占用尚未切换：u2 仍在 AFT
        assertEquals("AFT", cargoService.getUnit(u2).holdCode());
        assertEquals("FWD", cargoService.getUnit(u1).holdCode());
        // 当前生效版本仍是 v1
        PlanResponse plan = loadPlanService.getPlan(planNo);
        assertEquals(1, plan.currentVersionNo());
        assertEquals("CONFIRMED", plan.status());
        List<PlanVersionResponse> all = loadPlanService.listVersions(planNo);
        assertEquals(2, all.size());
        assertEquals("CONFIRMED", all.get(0).status());
        assertEquals("DRAFT", all.get(1).status());
    }

    /** 1、按整份方案重新核算：加入替换货物导致超舱时准备即拒绝，不产生新版本，原方案不变。 */
    @Test
    void prepareAdjustRevalidatesWholePlaneAndRejectsViolation() {
        String flight = newFlight(150, 10000); // FWD 上限 150
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo, List.of(new AssignmentRequest(u1, "FWD")));

        assertThrows(LoadConstraintException.class, () -> loadPlanService.prepareAdjust(planNo,
                adjust("C1", List.of(new AssignmentRequest(u2, "FWD")), List.of())));

        // 新版本未落库，原方案与占用不变
        assertEquals(1, loadPlanService.listVersions(planNo).size());
        assertEquals("AVAILABLE", cargoService.getUnit(u2).status());
        assertEquals("FWD", cargoService.getUnit(u1).holdCode());
    }

    /** 1、整份方案重算重心：卸货+替换后整机重心必须重新落入区间，否则准备即拒绝。 */
    @Test
    void prepareAdjustRecomputesCenterOfGravity() {
        String flight = newFlight();
        String a = newUnit(500);
        String b = newUnit(500);
        String planNo = id("P");
        // FWD/AFT 各一件 500kg，CG=10
        createAndConfirm(flight, planNo,
                List.of(new AssignmentRequest(a, "FWD"), new AssignmentRequest(b, "AFT")));

        // 卸下 AFT 平衡货物 b，并加入 1500kg 替换货物到 FWD：
        // 前舱合计 2000kg，CG=(1000*10+2000*5)/3000=6.67 < 8，整份方案重心越界
        String heavy = newUnit(1500);
        LoadConstraintException ex = assertThrows(LoadConstraintException.class,
                () -> loadPlanService.prepareAdjust(planNo,
                        adjust("C1", List.of(new AssignmentRequest(heavy, "FWD")), List.of(b))));
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("重心")));
        // 调整未落库，原配载不变
        assertEquals(1, loadPlanService.listVersions(planNo).size());
        assertEquals("LOCKED", cargoService.getUnit(b).status());
        assertEquals("AFT", cargoService.getUnit(b).holdCode());
    }

    /** 2、确认时同一事务切换全部货物与舱位：卸下 u1 释放，加入替换 u3 锁定并占舱。 */
    @Test
    void confirmAdjustSwitchesAllOccupancyAtomically() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String u3 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo,
                List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "AFT")));

        loadPlanService.prepareAdjust(planNo,
                adjust("C1", List.of(new AssignmentRequest(u3, "FWD")), List.of(u1)));
        PlanVersionResponse confirmed = loadPlanService.confirm(planNo);

        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(2, confirmed.versionNo());
        assertEquals(List.of(u1), confirmed.unloadedUnitNos());
        assertEquals(List.of(u3), confirmed.addedUnitNos());

        // 卸下货物只释放一次：u1 回到空闲
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());
        assertNull(cargoService.getUnit(u1).planNo());
        assertNull(cargoService.getUnit(u1).holdCode());
        // 替换货物锁定到本方案 FWD
        assertEquals("LOCKED", cargoService.getUnit(u3).status());
        assertEquals("FWD", cargoService.getUnit(u3).holdCode());
        assertEquals(planNo, cargoService.getUnit(u3).planNo());
        // 在机货物 u2 保持
        assertEquals("LOCKED", cargoService.getUnit(u2).status());

        // 版本切换：v1 被取代，v2 为当前
        List<PlanVersionResponse> all = loadPlanService.listVersions(planNo);
        assertEquals("SUPERSEDED", all.get(0).status());
        assertEquals("CONFIRMED", all.get(1).status());
        assertEquals(2, loadPlanService.getPlan(planNo).currentVersionNo());

        // 约束与舱位用量反映最终装机方案
        ConstraintReportResponse report = flightService.constraints(flight);
        assertTrue(report.withinEnvelope());
        HoldUsageResponse fwd = flightService.holdUsage(flight).stream()
                .filter(h -> h.holdCode().equals("FWD")).findFirst().orElseThrow();
        assertEquals(100, fwd.usedWeight());
        assertEquals(List.of(u3), fwd.loadedUnits());
    }

    /** 2、替换货物在确认时不可用：整体回滚，原已确认配载继续有效。 */
    @Test
    void unavailableReplacementAtConfirmKeepsOriginalLoad() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        String otherPlan = id("P");
        createAndConfirm(flight, planNo, List.of(new AssignmentRequest(u1, "FWD")));
        createAndConfirm(flight, otherPlan, List.of(new AssignmentRequest(u2, "AFT")));

        // planNo 想把 u2 作为替换货物加入：准备时 u2 已被 otherPlan 占用 → 准备即拒绝
        ConflictException prep = assertThrows(ConflictException.class,
                () -> loadPlanService.prepareAdjust(planNo,
                        adjust("C1", List.of(new AssignmentRequest(u2, "FWD")), List.of(u1))));
        assertTrue(prep.getMessage().contains("替换货物"));
        assertEquals("FWD", cargoService.getUnit(u1).holdCode());

        // 另一情景：准备时替换货物空闲，确认前被别的方案抢走
        String u3 = newUnit(100);
        loadPlanService.prepareAdjust(planNo,
                adjust("C2", List.of(new AssignmentRequest(u3, "FWD")), List.of(u1)));
        String thief = id("P");
        createAndConfirm(flight, thief, List.of(new AssignmentRequest(u3, "AFT")));

        ConflictException confirm = assertThrows(ConflictException.class,
                () -> loadPlanService.confirm(planNo));
        assertTrue(confirm.getMessage().contains("已被其他方案占用"));
        // 原配载继续有效：u1 仍锁在 FWD，当前版本仍是 v1
        assertEquals("LOCKED", cargoService.getUnit(u1).status());
        assertEquals("FWD", cargoService.getUnit(u1).holdCode());
        assertEquals(planNo, cargoService.getUnit(u1).planNo());
        assertEquals(1, loadPlanService.getPlan(planNo).currentVersionNo());
        assertEquals("CONFIRMED", loadPlanService.listVersions(planNo).get(0).status());
    }

    /** 3、确认前替换货物被改重（货物版本变化）→ 快照失效，确认拒绝、原方案不变。 */
    @Test
    void confirmAdjustFailsWhenReplacementWeightChanged() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo, List.of(new AssignmentRequest(u1, "FWD")));

        loadPlanService.prepareAdjust(planNo,
                adjust("C1", List.of(new AssignmentRequest(u2, "FWD")), List.of(u1)));
        cargoService.updateWeight(u2, new UpdateWeightRequest(120));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> loadPlanService.confirm(planNo));
        assertTrue(ex.getMessage().contains("快照失效"));
        assertEquals("LOCKED", cargoService.getUnit(u1).status());
        assertEquals("FWD", cargoService.getUnit(u1).holdCode());
        assertEquals(1, loadPlanService.getPlan(planNo).currentVersionNo());
    }

    /** 3、确认前航班配置变化（航班配置版本递增）→ 快照失效，确认拒绝、原方案不变。 */
    @Test
    void confirmAdjustFailsWhenFlightConfigChanged() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo, List.of(new AssignmentRequest(u1, "FWD")));

        loadPlanService.prepareAdjust(planNo,
                adjust("C1", List.of(new AssignmentRequest(u1, "AFT")), List.of()));
        flightService.updateConfig(flight, new UpdateFlightConfigRequest(1000, 10, 8, 11,
                List.of(new HoldRequest("FWD", 10000, 10000, 5, Set.of()),
                        new HoldRequest("AFT", 10000, 10000, 15, Set.of()))));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> loadPlanService.confirm(planNo));
        assertTrue(ex.getMessage().contains("快照失效"));
        assertEquals("FWD", cargoService.getUnit(u1).holdCode());
    }

    /** 3、航班起飞（关闭）后调整版本不可确认，卸下未发生，原配载有效。 */
    @Test
    void confirmAdjustRejectedAfterFlightClosed() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo, List.of(new AssignmentRequest(u1, "FWD")));

        // 卸下唯一货物后的空方案 CG=10 仍合法，可以准备
        loadPlanService.prepareAdjust(planNo, adjust("C1", List.of(), List.of(u1)));
        flightService.close(flight);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> loadPlanService.confirm(planNo));
        assertTrue(ex.getMessage().contains("已关闭"));
        assertEquals("LOCKED", cargoService.getUnit(u1).status());
        assertEquals("FWD", cargoService.getUnit(u1).holdCode());
        assertEquals(1, loadPlanService.getPlan(planNo).currentVersionNo());
    }

    /** 3、关闭后不允许准备新的调整。 */
    @Test
    void prepareAdjustRejectedAfterFlightClosed() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo, List.of(new AssignmentRequest(u1, "FWD")));
        flightService.close(flight);

        assertThrows(ConflictException.class, () -> loadPlanService.prepareAdjust(planNo,
                adjust("C1", List.of(), List.of(u1))));
    }

    /** 3、两个方案并发确认同一替换货物：航班行锁 + 货物占用只允许一个成功，败者原方案不变。 */
    @Test
    void concurrentAdjustConfirmOnSharedReplacementOnlyOneWins() throws Exception {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String u3 = newUnit(100);
        String planA = id("P");
        String planB = id("P");
        createAndConfirm(flight, planA, List.of(new AssignmentRequest(u1, "FWD")));
        createAndConfirm(flight, planB, List.of(new AssignmentRequest(u2, "AFT")));

        // 两个调整都想加入同一空闲替换货物 u3（各自替换掉原货物）
        loadPlanService.prepareAdjust(planA,
                adjust("CA", List.of(new AssignmentRequest(u3, "FWD")), List.of(u1)));
        loadPlanService.prepareAdjust(planB,
                adjust("CB", List.of(new AssignmentRequest(u3, "AFT")), List.of(u2)));

        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Callable<Boolean> taskA = () -> {
            gate.await();
            try {
                loadPlanService.confirm(planA);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
        Callable<Boolean> taskB = () -> {
            gate.await();
            try {
                loadPlanService.confirm(planB);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
        Future<Boolean> fA = pool.submit(taskA);
        Future<Boolean> fB = pool.submit(taskB);
        gate.countDown();
        boolean aWon = fA.get(30, TimeUnit.SECONDS);
        boolean bWon = fB.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, (aWon ? 1 : 0) + (bWon ? 1 : 0));
        // u3 恰好被一个方案锁定
        WhereaboutsResponse w = cargoService.whereabouts(u3);
        assertEquals("LOCKED", w.status());
        assertTrue(w.planNo().equals(planA) || w.planNo().equals(planB));
        // 败者的原货物仍锁定、当前版本仍为 v1
        if (aWon) {
            assertEquals("LOCKED", cargoService.getUnit(u2).status());
            assertEquals(planB, cargoService.getUnit(u2).planNo());
            assertEquals(1, loadPlanService.getPlan(planB).currentVersionNo());
        } else {
            assertEquals("LOCKED", cargoService.getUnit(u1).status());
            assertEquals(planA, cargoService.getUnit(u1).planNo());
            assertEquals(1, loadPlanService.getPlan(planA).currentVersionNo());
        }
    }

    /** 4、变更号幂等：同一 changeNo 重复准备返回同一版本，不重复卸货/占用。 */
    @Test
    void prepareAdjustIsIdempotentByChangeNo() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo, List.of(new AssignmentRequest(u1, "FWD")));

        AdjustPlanRequest req = adjust("C1", List.of(new AssignmentRequest(u2, "AFT")), List.of(u1));
        PlanVersionResponse first = loadPlanService.prepareAdjust(planNo, req);
        PlanVersionResponse second = loadPlanService.prepareAdjust(planNo, req);

        assertEquals(first.versionNo(), second.versionNo());
        assertEquals(2, first.versionNo());
        assertEquals(2, loadPlanService.listVersions(planNo).size());
    }

    /** 4、以新变更号提交时，上一待确认草案作废；已作废版本不可确认。 */
    @Test
    void newChangeNoCancelsPendingDraft() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo, List.of(new AssignmentRequest(u1, "FWD")));

        PlanVersionResponse c1 = loadPlanService.prepareAdjust(planNo,
                adjust("C1", List.of(new AssignmentRequest(u1, "AFT")), List.of()));
        PlanVersionResponse c2 = loadPlanService.prepareAdjust(planNo,
                adjust("C2", List.of(new AssignmentRequest(u1, "FWD")), List.of()));

        assertEquals("CANCELLED", loadPlanService.getVersion(planNo, c1.versionNo()).status());
        assertEquals("DRAFT", c2.status());
        assertThrows(ConflictException.class,
                () -> loadPlanService.confirmVersion(planNo, c1.versionNo()));

        PlanVersionResponse confirmed = loadPlanService.confirmVersion(planNo, c2.versionNo());
        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(3, confirmed.versionNo());
    }

    /** 4、确认幂等：重复确认同一已确认版本不再二次卸货，货物状态稳定。 */
    @Test
    void confirmAdjustIsIdempotentAndUnloadsOnce() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo,
                List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "AFT")));

        loadPlanService.prepareAdjust(planNo, adjust("C1", List.of(), List.of(u1)));
        PlanVersionResponse first = loadPlanService.confirm(planNo);
        PlanVersionResponse second = loadPlanService.confirm(planNo);

        assertEquals("CONFIRMED", first.status());
        assertEquals("CONFIRMED", second.status());
        assertEquals(2, second.versionNo());
        // u1 只释放一次，重复确认后仍是空闲（不会被重复操作或报错）
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());
        assertNull(cargoService.getUnit(u1).planNo());
        // u2 仍锁定
        assertEquals("LOCKED", cargoService.getUnit(u2).status());
    }

    /** 4、整组卸载幂等：货物只释放一次，重复请求返回同一取消结果。 */
    @Test
    void unloadPlanReleasesAllUnitsAndIsIdempotent() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo, List.of(new AssignmentRequest(u1, "FWD")));

        PlanResponse cancelled = loadPlanService.unloadPlan(planNo);
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());

        // 重复卸载：幂等返回，不抛错、不重复释放
        PlanResponse again = loadPlanService.unloadPlan(planNo);
        assertEquals("CANCELLED", again.status());
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());
        assertNull(cargoService.getUnit(u1).planNo());
    }

    /** 4、各配载版本明细完整保留，可逐版本追溯卸下/加入的货物。 */
    @Test
    void allVersionDetailsAreRetained() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String u3 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo,
                List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "AFT")));

        loadPlanService.prepareAdjust(planNo,
                adjust("C1", List.of(new AssignmentRequest(u3, "FWD")), List.of(u1)));
        loadPlanService.confirm(planNo);
        loadPlanService.prepareAdjust(planNo, adjust("C2", List.of(), List.of(u2)));
        loadPlanService.confirm(planNo);

        List<PlanVersionResponse> all = loadPlanService.listVersions(planNo);
        assertEquals(3, all.size());
        assertEquals("SUPERSEDED", all.get(0).status());
        assertEquals("SUPERSEDED", all.get(1).status());
        assertEquals("CONFIRMED", all.get(2).status());

        PlanVersionResponse v1 = loadPlanService.getVersion(planNo, 1);
        assertEquals(2, v1.assignments().size());
        assertTrue(v1.addedUnitNos().containsAll(List.of(u1, u2)));

        PlanVersionResponse v2 = loadPlanService.getVersion(planNo, 2);
        assertTrue(v2.assignments().contains(new AssignmentRequest(u2, "AFT")));
        assertTrue(v2.assignments().contains(new AssignmentRequest(u3, "FWD")));
        assertEquals(List.of(u1), v2.unloadedUnitNos());
        assertEquals(List.of(u3), v2.addedUnitNos());

        PlanVersionResponse v3 = loadPlanService.getVersion(planNo, 3);
        assertEquals(List.of(new AssignmentRequest(u3, "FWD")), v3.assignments());
        assertEquals(List.of(u2), v3.unloadedUnitNos());
    }

    /** 整组卸载后存在待确认草案：草案一并取消，且不能再通过指定版本确认复活方案。 */
    @Test
    void unloadWithPendingDraftCancelsDraftAndBlocksRevival() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo, List.of(new AssignmentRequest(u1, "FWD")));

        PlanVersionResponse draft = loadPlanService.prepareAdjust(planNo,
                adjust("C1", List.of(new AssignmentRequest(u1, "AFT")), List.of()));
        assertEquals(2, draft.versionNo());

        loadPlanService.unloadPlan(planNo);

        assertEquals("CANCELLED", loadPlanService.getPlan(planNo).status());
        assertEquals("CANCELLED", loadPlanService.getVersion(planNo, draft.versionNo()).status());
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());

        // 不能通过确认遗留草案把已卸载方案复活
        assertThrows(ConflictException.class, () -> loadPlanService.confirmVersion(planNo, draft.versionNo()));
        assertThrows(ConflictException.class, () -> loadPlanService.confirm(planNo));
    }

    // ---------- 航班关闭后 ----------

    @Test
    void closedFlightRejectsModificationAndRecordsActualUnload() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.confirm(planNo);
        flightService.close(flight);

        // 关闭后方案不可修改
        assertThrows(ConflictException.class, () -> loadPlanService.prepareAdjust(planNo,
                adjust("C1", List.of(new AssignmentRequest(u1, "AFT")), List.of())));
        assertThrows(ConflictException.class, () -> loadPlanService.unloadPlan(planNo));
        assertThrows(ConflictException.class, () -> loadPlanService.createPlan(flight,
                new CreatePlanRequest(id("P"), List.of(new AssignmentRequest(newUnit(50), "AFT")))));

        // 只能记录实际卸载事件
        var event = flightService.recordUnload(flight, new RecordUnloadRequest(u1, 98.5));
        assertNotNull(event.id());
        assertEquals(planNo, event.planNo());
        assertEquals("FWD", event.holdCode());

        WhereaboutsResponse w = cargoService.whereabouts(u1);
        assertEquals("UNLOADED", w.status());
        assertEquals(flight, w.flightNo());
        assertEquals(planNo, w.planNo());
        assertEquals(1, w.unloadEvents().size());
        assertEquals(98.5, w.unloadEvents().get(0).actualWeight());

        // 重复卸载同一货物被拒绝
        assertThrows(ConflictException.class,
                () -> flightService.recordUnload(flight, new RecordUnloadRequest(u1, 98.5)));
    }

    @Test
    void recordUnloadRequiresClosedFlight() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.confirm(planNo);

        assertThrows(ConflictException.class,
                () -> flightService.recordUnload(flight, new RecordUnloadRequest(u1, 100)));
    }

    // ---------- 查询 ----------

    @Test
    void whereaboutsOfAvailableUnit() {
        String u1 = newUnit(100);
        WhereaboutsResponse w = cargoService.whereabouts(u1);
        assertEquals("AVAILABLE", w.status());
        assertNull(w.flightNo());
        assertNull(w.holdCode());
        assertNull(w.planNo());
        assertTrue(w.unloadEvents().isEmpty());
    }

    @Test
    void constraintReportReflectsCurrentLoad() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        createAndConfirm(flight, planNo,
                List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "AFT")));

        ConstraintReportResponse report = flightService.constraints(flight);
        // (1000*10 + 100*5 + 100*15) / 1200 = 10
        assertEquals(10.0, report.centerOfGravity(), 1e-6);
        assertTrue(report.withinEnvelope());
        assertFalse(report.violations().stream().anyMatch(v -> v.contains("重心")));
    }
}

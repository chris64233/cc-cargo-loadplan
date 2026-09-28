package com.chris64233.cargoloadplan;

import com.chris64233.cargoloadplan.dto.AdjustPlanRequest;
import com.chris64233.cargoloadplan.dto.AssignmentRequest;
import com.chris64233.cargoloadplan.dto.ConstraintReportResponse;
import com.chris64233.cargoloadplan.dto.CreateFlightRequest;
import com.chris64233.cargoloadplan.dto.CreatePlanRequest;
import com.chris64233.cargoloadplan.dto.HoldRequest;
import com.chris64233.cargoloadplan.dto.HoldUsageResponse;
import com.chris64233.cargoloadplan.dto.PlanResponse;
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
    }

    @Test
    void confirmIsIdempotent() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));

        PlanResponse first = loadPlanService.confirm(planNo);
        PlanResponse second = loadPlanService.confirm(planNo);

        assertEquals("CONFIRMED", first.status());
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

    // ---------- 起飞前临时卸货/替换：版本化调整与确认切换 ----------

    @Test
    void adjustCreatesProposedVersionWithoutTouchingOccupancy() {
        String flight = newFlight(10000, 150);
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "AFT"))));
        loadPlanService.confirm(planNo);

        // 准备调整：u2 重新分舱到 FWD，只生成 PROPOSED 版本，占用不变
        PlanResponse proposed = loadPlanService.adjust(planNo,
                new AdjustPlanRequest(List.of(new AssignmentRequest(u2, "FWD")), List.of()));

        assertEquals("CONFIRMED", proposed.status());
        assertEquals(1, proposed.currentVersionNo());
        assertEquals(2, proposed.versions().size());
        var v2 = proposed.versions().get(1);
        assertEquals(2, v2.versionNo());
        assertEquals("PROPOSED", v2.status());
        assertTrue(v2.items().contains(new AssignmentRequest(u2, "FWD")));
        // 当前生效版本与货物占用仍停留在版本 1
        assertEquals("AFT", cargoService.getUnit(u2).holdCode());
        assertTrue(proposed.assignments().contains(new AssignmentRequest(u2, "AFT")));

        // 确认切换后版本 2 生效，版本 1 保留为 SUPERSEDED
        PlanResponse switched = loadPlanService.confirmAdjustment(planNo);
        assertEquals(2, switched.currentVersionNo());
        assertEquals("ACTIVE", switched.versions().get(1).status());
        assertEquals("SUPERSEDED", switched.versions().get(0).status());
        assertEquals("FWD", cargoService.getUnit(u2).holdCode());
        assertTrue(switched.assignments().contains(new AssignmentRequest(u2, "FWD")));
    }

    @Test
    void adjustRevalidatesWholePlanWeightVolumeCgAndSameHoldRules() {
        // AFT 上限 150：把两件 100kg 货物都调去 AFT 必然超重；调整阶段即 422，无新版本
        String flight = newFlight(10000, 150);
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "AFT"))));
        loadPlanService.confirm(planNo);

        assertThrows(LoadConstraintException.class, () -> loadPlanService.adjust(planNo,
                new AdjustPlanRequest(List.of(new AssignmentRequest(u1, "AFT"),
                        new AssignmentRequest(u2, "AFT")), List.of())));

        // 没有生成任何版本，原配载不变
        PlanResponse plan = loadPlanService.getPlan(planNo);
        assertEquals(1, plan.versions().size());
        assertEquals("FWD", cargoService.getUnit(u1).holdCode());
        assertEquals("AFT", cargoService.getUnit(u2).holdCode());

        // 同舱互斥同样按整份方案复核：替换货物与原货物互斥时拒绝
        String flight2 = newFlight();
        String live = newUnit(100, "LIVE", Set.of("ICE"));
        String p2 = id("P");
        loadPlanService.createPlan(flight2, new CreatePlanRequest(p2,
                List.of(new AssignmentRequest(live, "FWD"))));
        loadPlanService.confirm(p2);
        String ice = newUnit(100, "ICE", Set.of());
        LoadConstraintException ex = assertThrows(LoadConstraintException.class,
                () -> loadPlanService.adjust(p2,
                        new AdjustPlanRequest(List.of(new AssignmentRequest(ice, "FWD")), List.of())));
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("不可同舱")));
        assertEquals("AVAILABLE", cargoService.getUnit(ice).status());
    }

    @Test
    void lateOffloadAndReplaceSwitchesAtomicallyAndKeepsOldVersion() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.confirm(planNo);

        // 临时卸下 u1、加入替换货物 u2，先准备新版本
        PlanResponse proposed = loadPlanService.adjust(planNo,
                new AdjustPlanRequest(List.of(new AssignmentRequest(u2, "AFT")), List.of(u1)));
        var v2 = proposed.versions().get(1);
        assertEquals("PROPOSED", v2.status());
        assertEquals(List.of(new AssignmentRequest(u2, "AFT")), v2.items());
        // 确认前：u1 仍占位锁定，u2 仍空闲
        assertEquals("LOCKED", cargoService.getUnit(u1).status());
        assertEquals(planNo, cargoService.getUnit(u1).planNo());
        assertEquals("AVAILABLE", cargoService.getUnit(u2).status());

        PlanResponse switched = loadPlanService.confirmAdjustment(planNo);

        // 同一事务切换完成
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());
        assertNull(cargoService.getUnit(u1).planNo());
        assertNull(cargoService.getUnit(u1).holdCode());
        assertEquals("LOCKED", cargoService.getUnit(u2).status());
        assertEquals(planNo, cargoService.getUnit(u2).planNo());
        assertEquals("AFT", cargoService.getUnit(u2).holdCode());
        assertEquals(2, switched.currentVersionNo());
        // 两个版本明细都保留：版本 1 含 u1，版本 2 含 u2
        assertEquals(List.of(new AssignmentRequest(u1, "FWD")),
                switched.versions().get(0).items());
        assertEquals(List.of(new AssignmentRequest(u2, "AFT")),
                switched.versions().get(1).items());
        assertEquals("SUPERSEDED", switched.versions().get(0).status());
    }

    @Test
    void confirmFailsWhenReplacementTakenAndOriginalPlanStaysEffective() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String replacement = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.confirm(planNo);
        loadPlanService.adjust(planNo,
                new AdjustPlanRequest(List.of(new AssignmentRequest(replacement, "AFT")), List.of(u1)));

        // 替换货物被同一航班上的另一个已确认方案占用
        String takePlan = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(takePlan,
                List.of(new AssignmentRequest(replacement, "FWD"))));
        loadPlanService.confirm(takePlan);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> loadPlanService.confirmAdjustment(planNo));
        assertTrue(ex.getMessage().contains("替换货物"));

        // 原配载继续有效：u1 仍锁定在原方案原舱位，版本仍为 1
        assertEquals("LOCKED", cargoService.getUnit(u1).status());
        assertEquals(planNo, cargoService.getUnit(u1).planNo());
        assertEquals("FWD", cargoService.getUnit(u1).holdCode());
        PlanResponse plan = loadPlanService.getPlan(planNo);
        assertEquals(1, plan.currentVersionNo());
        assertEquals("ACTIVE", plan.versions().get(0).status());
        assertEquals("PROPOSED", plan.versions().get(1).status());
    }

    @Test
    void confirmFailsWhenFlightClosedAfterProposalAndOriginalStays() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.confirm(planNo);
        loadPlanService.adjust(planNo,
                new AdjustPlanRequest(List.of(), List.of(u1)));

        // 航班起飞（关闭）后调整确认必须失败
        flightService.close(flight);
        assertThrows(ConflictException.class, () -> loadPlanService.confirmAdjustment(planNo));

        // 原方案货物未被释放，仍锁定
        assertEquals("LOCKED", cargoService.getUnit(u1).status());
        assertEquals(planNo, cargoService.getUnit(u1).planNo());
        assertEquals(1, loadPlanService.getPlan(planNo).currentVersionNo());
    }

    @Test
    void confirmFailsWhenCargoChangedAfterProposal() {
        // 替换货物在调整待确认期间重量被修改（货物版本递增），确认时快照失效、原配载有效
        String flight = newFlight();
        String a1 = newUnit(100);
        String repl = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(a1, "FWD"))));
        loadPlanService.confirm(planNo);
        loadPlanService.adjust(planNo,
                new AdjustPlanRequest(List.of(new AssignmentRequest(repl, "AFT")), List.of(a1)));
        cargoService.updateWeight(repl, new UpdateWeightRequest(120));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> loadPlanService.confirmAdjustment(planNo));
        assertTrue(ex.getMessage().contains("快照失效"));
        // 原方案继续有效，替换货物未入舱
        assertEquals("LOCKED", cargoService.getUnit(a1).status());
        assertEquals(planNo, cargoService.getUnit(a1).planNo());
        assertEquals("AVAILABLE", cargoService.getUnit(repl).status());
        assertEquals(1, loadPlanService.getPlan(planNo).currentVersionNo());
    }

    @Test
    void repeatedAdjustRegeneratesSingleProposedVersion() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.confirm(planNo);

        loadPlanService.adjust(planNo,
                new AdjustPlanRequest(List.of(new AssignmentRequest(u2, "AFT")), List.of(u1)));
        // 再次准备：替换掉未确认的候选，不累积版本号
        PlanResponse rePrepared = loadPlanService.adjust(planNo,
                new AdjustPlanRequest(List.of(), List.of(u1)));

        assertEquals(2, rePrepared.versions().size());
        var proposed = rePrepared.versions().get(1);
        assertEquals(2, proposed.versionNo());
        assertEquals("PROPOSED", proposed.status());
        assertEquals(List.of(), proposed.items());

        PlanResponse switched = loadPlanService.confirmAdjustment(planNo);
        assertEquals(2, switched.versions().size());
        assertEquals(2, switched.currentVersionNo());
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());
        assertEquals("AVAILABLE", cargoService.getUnit(u2).status());
    }

    @Test
    void confirmAdjustmentWithoutProposalConflicts() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.confirm(planNo);
        assertThrows(ConflictException.class, () -> loadPlanService.confirmAdjustment(planNo));
    }

    @Test
    void successiveAdjustmentsBuildVersionHistory() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String u3 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"))));
        loadPlanService.confirm(planNo);

        // 版本 2：u1 -> AFT
        loadPlanService.adjust(planNo,
                new AdjustPlanRequest(List.of(new AssignmentRequest(u1, "AFT")), List.of()));
        loadPlanService.confirmAdjustment(planNo);
        // 版本 3：卸 u1，加 u2、u3
        loadPlanService.adjust(planNo,
                new AdjustPlanRequest(
                        List.of(new AssignmentRequest(u2, "FWD"), new AssignmentRequest(u3, "AFT")),
                        List.of(u1)));
        PlanResponse v3 = loadPlanService.confirmAdjustment(planNo);

        assertEquals(3, v3.currentVersionNo());
        assertEquals(3, v3.versions().size());
        assertEquals("SUPERSEDED", v3.versions().get(0).status());
        assertEquals("SUPERSEDED", v3.versions().get(1).status());
        assertEquals("ACTIVE", v3.versions().get(2).status());
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());
        assertEquals("LOCKED", cargoService.getUnit(u2).status());
        assertEquals("LOCKED", cargoService.getUnit(u3).status());
    }

    @Test
    void concurrentAdjustmentConfirmOnlyOneResult() throws Exception {
        // 两个方案在同一航班并发确认调整、争抢同一替换货物：最多一个成功
        String flight = newFlight();
        String a1 = newUnit(100);
        String b1 = newUnit(100);
        String replacement = newUnit(100);
        String p1 = id("P");
        String p2 = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(p1,
                List.of(new AssignmentRequest(a1, "FWD"))));
        loadPlanService.createPlan(flight, new CreatePlanRequest(p2,
                List.of(new AssignmentRequest(b1, "FWD"))));
        loadPlanService.confirm(p1);
        loadPlanService.confirm(p2);
        loadPlanService.adjust(p1,
                new AdjustPlanRequest(List.of(new AssignmentRequest(replacement, "AFT")), List.of(a1)));
        loadPlanService.adjust(p2,
                new AdjustPlanRequest(List.of(new AssignmentRequest(replacement, "AFT")), List.of(b1)));

        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (String p : List.of(p1, p2)) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    loadPlanService.confirmAdjustment(p);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }));
        }
        gate.countDown();
        int successes = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdownNow();

        assertEquals(1, successes);
        // 替换货物只被一个方案锁定
        WhereaboutsResponse w = cargoService.whereabouts(replacement);
        assertEquals("LOCKED", w.status());
        assertTrue(w.planNo().equals(p1) || w.planNo().equals(p2));
        // 失败的一方原配载继续有效
        String loser = w.planNo().equals(p1) ? p2 : p1;
        String loserUnit = loser.equals(p1) ? a1 : b1;
        assertEquals("LOCKED", cargoService.getUnit(loserUnit).status());
        assertEquals(loser, cargoService.getUnit(loserUnit).planNo());
    }

    @Test
    void concurrentAdjustmentConfirmAcrossFlightsOnlyOneLocksReplacement() throws Exception {
        // 两个不同航班并发确认调整、争抢同一替换货物：货物乐观锁保证最多一个成功
        String f1 = newFlight();
        String f2 = newFlight();
        String a1 = newUnit(100);
        String b1 = newUnit(100);
        String replacement = newUnit(100);
        String p1 = id("P");
        String p2 = id("P");
        loadPlanService.createPlan(f1, new CreatePlanRequest(p1,
                List.of(new AssignmentRequest(a1, "FWD"))));
        loadPlanService.createPlan(f2, new CreatePlanRequest(p2,
                List.of(new AssignmentRequest(b1, "FWD"))));
        loadPlanService.confirm(p1);
        loadPlanService.confirm(p2);
        loadPlanService.adjust(p1,
                new AdjustPlanRequest(List.of(new AssignmentRequest(replacement, "AFT")), List.of(a1)));
        loadPlanService.adjust(p2,
                new AdjustPlanRequest(List.of(new AssignmentRequest(replacement, "AFT")), List.of(b1)));

        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (String p : List.of(p1, p2)) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    loadPlanService.confirmAdjustment(p);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }));
        }
        gate.countDown();
        int successes = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdownNow();

        assertEquals(1, successes);
        WhereaboutsResponse w = cargoService.whereabouts(replacement);
        assertEquals("LOCKED", w.status());
        // 失败方原方案货物继续锁定在本方案
        String loser = w.planNo().equals(p1) ? p2 : p1;
        String loserUnit = loser.equals(p1) ? a1 : b1;
        assertEquals("LOCKED", cargoService.getUnit(loserUnit).status());
        assertEquals(loser, cargoService.getUnit(loserUnit).planNo());
    }

    @Test
    void unloadPlanIsIdempotentAndReleasesEachUnitOnce() {
        String flight = newFlight();
        String u1 = newUnit(100);
        String u2 = newUnit(100);
        String planNo = id("P");
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "AFT"))));
        loadPlanService.confirm(planNo);

        PlanResponse first = loadPlanService.unloadPlan(planNo);
        // 重复请求保持幂等：返回取消态，不报错，货物不会被二次处理
        PlanResponse second = loadPlanService.unloadPlan(planNo);

        assertEquals("CANCELLED", first.status());
        assertEquals("CANCELLED", second.status());
        assertEquals("AVAILABLE", cargoService.getUnit(u1).status());
        assertEquals("AVAILABLE", cargoService.getUnit(u2).status());
        assertNull(cargoService.getUnit(u1).planNo());
        // 版本明细仍保留可追溯
        assertEquals(1, first.versions().size());
        assertEquals("SUPERSEDED", first.versions().get(0).status());
        assertEquals(2, first.versions().get(0).items().size());
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
        assertThrows(ConflictException.class, () -> loadPlanService.adjust(planNo,
                new AdjustPlanRequest(List.of(new AssignmentRequest(u1, "AFT")), List.of())));
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
        loadPlanService.createPlan(flight, new CreatePlanRequest(planNo,
                List.of(new AssignmentRequest(u1, "FWD"), new AssignmentRequest(u2, "AFT"))));
        loadPlanService.confirm(planNo);

        ConstraintReportResponse report = flightService.constraints(flight);
        // (1000*10 + 100*5 + 100*15) / 1200 = 10
        assertEquals(10.0, report.centerOfGravity(), 1e-6);
        assertTrue(report.withinEnvelope());
        assertFalse(report.violations().stream().anyMatch(v -> v.contains("重心")));
    }
}

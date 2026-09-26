package com.chris64233.cargoloadplan;

import com.chris64233.cargoloadplan.domain.CargoCategory;
import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.domain.CargoUnitStatus;
import com.chris64233.cargoloadplan.domain.Compartment;
import com.chris64233.cargoloadplan.domain.Flight;
import com.chris64233.cargoloadplan.domain.LoadPlan;
import com.chris64233.cargoloadplan.domain.PlanStatus;
import com.chris64233.cargoloadplan.repository.CargoUnitRepository;
import com.chris64233.cargoloadplan.repository.CompartmentRepository;
import com.chris64233.cargoloadplan.repository.FlightRepository;
import com.chris64233.cargoloadplan.repository.LoadPlanRepository;
import com.chris64233.cargoloadplan.repository.OffloadEventRepository;
import com.chris64233.cargoloadplan.service.CargoUnitService;
import com.chris64233.cargoloadplan.service.FlightService;
import com.chris64233.cargoloadplan.service.LoadPlanException;
import com.chris64233.cargoloadplan.service.LoadPlanService;
import com.chris64233.cargoloadplan.service.LoadPlanService.PlacementSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LoadPlanServiceTest {

    @Autowired
    LoadPlanService loadPlanService;
    @Autowired
    FlightService flightService;
    @Autowired
    CargoUnitService cargoUnitService;
    @Autowired
    FlightRepository flightRepository;
    @Autowired
    CompartmentRepository compartmentRepository;
    @Autowired
    CargoUnitRepository cargoUnitRepository;
    @Autowired
    LoadPlanRepository loadPlanRepository;
    @Autowired
    OffloadEventRepository offloadEventRepository;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        // 先解除 cargo_units -> load_plans 的外键引用，再按依赖顺序清空
        jdbcTemplate.update("update cargo_units set active_plan_id = null");
        offloadEventRepository.deleteAll();
        loadPlanRepository.deleteAll();
        cargoUnitRepository.deleteAll();
        compartmentRepository.deleteAll();
        flightRepository.deleteAll();
    }

    // ---------- 基础流程 ----------

    @Test
    void prepareAndConfirm_success() {
        Flight flight = newFlight("CA1001");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        CargoUnit unit = registerUnit("ULD001", 800, 5);

        LoadPlan plan = loadPlanService.preparePlan("PLAN-1", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId())));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(cargoUnitRepository.findByUnitNo("ULD001").orElseThrow().getStatus())
                .isEqualTo(CargoUnitStatus.ALLOCATED);

        LoadPlan confirmed = loadPlanService.confirmPlan("PLAN-1");
        assertThat(confirmed.getStatus()).isEqualTo(PlanStatus.CONFIRMED);

        var usage = loadPlanService.compartmentUsage(flight.getId());
        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).usedWeightKg()).isEqualTo(800);
        assertThat(usage.get(0).remainingWeightKg()).isEqualTo(4200);

        var cg = loadPlanService.constraintComputation(flight.getId());
        assertThat(cg.totalWeightKg()).isEqualTo(10_800);
        assertThat(cg.withinEnvelope()).isTrue();
    }

    @Test
    void confirm_isIdempotent() {
        Flight flight = newFlight("CA1002");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        CargoUnit unit = registerUnit("ULD002", 800, 5);
        loadPlanService.preparePlan("PLAN-2", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId())));

        loadPlanService.confirmPlan("PLAN-2");
        LoadPlan again = loadPlanService.confirmPlan("PLAN-2");
        assertThat(again.getStatus()).isEqualTo(PlanStatus.CONFIRMED);
    }

    @Test
    void prepare_isIdempotentForSamePlanNoAndPayload() {
        Flight flight = newFlight("CA1003");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        CargoUnit unit = registerUnit("ULD003", 800, 5);
        List<PlacementSpec> specs = List.of(new PlacementSpec(unit.getId(), fwd.getId()));

        LoadPlan first = loadPlanService.preparePlan("PLAN-3", flight.getId(), specs);
        LoadPlan second = loadPlanService.preparePlan("PLAN-3", flight.getId(), specs);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(loadPlanRepository.findByFlight_IdAndStatus(flight.getId(), PlanStatus.DRAFT)).hasSize(1);
    }

    @Test
    void prepare_samePlanNoWithDifferentPayload_conflicts() {
        Flight flight = newFlight("CA1004");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        Compartment aft = addCompartment(flight, "AFT", 5000, 30, -5.0);
        CargoUnit unit = registerUnit("ULD004", 800, 5);
        loadPlanService.preparePlan("PLAN-4", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId())));

        assertThatThrownBy(() -> loadPlanService.preparePlan("PLAN-4", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), aft.getId()))))
                .isInstanceOf(LoadPlanException.class)
                .extracting(e -> ((LoadPlanException) e).getCode()).isEqualTo("PLAN_NO_CONFLICT");
    }

    // ---------- 约束校验 ----------

    @Test
    void prepare_rejectsOverweightCompartment() {
        Flight flight = newFlight("CA1005");
        Compartment fwd = addCompartment(flight, "FWD", 100, 30, 5.0);
        CargoUnit unit = registerUnit("ULD005", 150, 5);

        assertThatThrownBy(() -> loadPlanService.preparePlan("PLAN-5", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId()))))
                .isInstanceOf(LoadPlanException.class)
                .hasMessageContaining("overweight");
    }

    @Test
    void prepare_rejectsOverVolumeCompartment() {
        Flight flight = newFlight("CA1006");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 1.0, 5.0);
        CargoUnit unit = registerUnit("ULD006", 100, 2.0);

        assertThatThrownBy(() -> loadPlanService.preparePlan("PLAN-6", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId()))))
                .isInstanceOf(LoadPlanException.class)
                .hasMessageContaining("over volume");
    }

    @Test
    void prepare_rejectsCategoryNotAllowed() {
        Flight flight = newFlight("CA1007");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0); // 仅 GENERAL
        CargoUnit unit = cargoUnitService.register("ULD007", 100, 1,
                CargoCategory.DANGEROUS, Set.of());

        assertThatThrownBy(() -> loadPlanService.preparePlan("PLAN-7", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId()))))
                .isInstanceOf(LoadPlanException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void prepare_rejectsIncompatibleCargoInSameCompartment() {
        Flight flight = newFlight("CA1008");
        Compartment fwd = flightService.addCompartment(flight.getId(), "FWD", 5000, 30, 5.0,
                Set.of(CargoCategory.GENERAL, CargoCategory.DANGEROUS, CargoCategory.PERISHABLE));

        CargoUnit dangerous = cargoUnitService.register("ULD008A", 100, 1,
                CargoCategory.DANGEROUS, Set.of(CargoCategory.PERISHABLE));
        CargoUnit perishable = cargoUnitService.register("ULD008B", 100, 1,
                CargoCategory.PERISHABLE, Set.of());

        assertThatThrownBy(() -> loadPlanService.preparePlan("PLAN-8", flight.getId(),
                List.of(new PlacementSpec(dangerous.getId(), fwd.getId()),
                        new PlacementSpec(perishable.getId(), fwd.getId()))))
                .isInstanceOf(LoadPlanException.class)
                .hasMessageContaining("incompatible");
    }

    @Test
    void prepare_rejectsCgOutsideEnvelope() {
        // 窄重心包线 [-1, 1]，货物全部装在前舱（力臂 +10），重心必然越界
        Flight flight = flightService.createFlight("CA1009", 1000, 0, -1, 1);
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 10.0);
        CargoUnit unit = registerUnit("ULD009", 500, 5);

        assertThatThrownBy(() -> loadPlanService.preparePlan("PLAN-9", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId()))))
                .isInstanceOf(LoadPlanException.class)
                .hasMessageContaining("center of gravity");
    }

    @Test
    void failedPrepare_leavesNoPartialState() {
        Flight flight = newFlight("CA1010");
        Compartment fwd = addCompartment(flight, "FWD", 100, 30, 5.0);
        CargoUnit fits = registerUnit("ULD010A", 50, 1);
        CargoUnit tooHeavy = registerUnit("ULD010B", 150, 1);

        assertThatThrownBy(() -> loadPlanService.preparePlan("PLAN-10", flight.getId(),
                List.of(new PlacementSpec(fits.getId(), fwd.getId()),
                        new PlacementSpec(tooHeavy.getId(), fwd.getId()))))
                .isInstanceOf(LoadPlanException.class);

        // 失败不能留下部分装载状态：两个货物都仍可用，方案未落库
        assertThat(cargoUnitRepository.findByUnitNo("ULD010A").orElseThrow().getStatus())
                .isEqualTo(CargoUnitStatus.AVAILABLE);
        assertThat(cargoUnitRepository.findByUnitNo("ULD010B").orElseThrow().getStatus())
                .isEqualTo(CargoUnitStatus.AVAILABLE);
        assertThat(loadPlanRepository.findByPlanNo("PLAN-10")).isEmpty();
    }

    @Test
    void cargoUnit_belongsToOnlyOneActivePlan() {
        Flight flight = newFlight("CA1011");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        CargoUnit unit = registerUnit("ULD011", 100, 1);
        loadPlanService.preparePlan("PLAN-11A", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId())));

        assertThatThrownBy(() -> loadPlanService.preparePlan("PLAN-11B", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId()))))
                .isInstanceOf(LoadPlanException.class)
                .extracting(e -> ((LoadPlanException) e).getCode()).isEqualTo("CARGO_UNAVAILABLE");
    }

    // ---------- 过期方案 ----------

    @Test
    void confirm_rejectsStalePlanWhenCargoWeightChanged() {
        Flight flight = newFlight("CA1012");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        CargoUnit unit = registerUnit("ULD012", 100, 1);
        loadPlanService.preparePlan("PLAN-12", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId())));

        cargoUnitService.update("ULD012", 120.0, null);

        assertThatThrownBy(() -> loadPlanService.confirmPlan("PLAN-12"))
                .isInstanceOf(LoadPlanException.class)
                .extracting(e -> ((LoadPlanException) e).getCode()).isEqualTo("STALE_PLAN");
        assertThat(loadPlanRepository.findByPlanNo("PLAN-12").orElseThrow().getStatus())
                .isEqualTo(PlanStatus.DRAFT);
    }

    @Test
    void confirm_rejectsStalePlanWhenFlightConfigChanged() {
        Flight flight = newFlight("CA1013");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        CargoUnit unit = registerUnit("ULD013", 100, 1);
        loadPlanService.preparePlan("PLAN-13", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId())));

        flightService.updateCompartment(fwd.getId(), 4000.0, null);

        assertThatThrownBy(() -> loadPlanService.confirmPlan("PLAN-13"))
                .isInstanceOf(LoadPlanException.class)
                .extracting(e -> ((LoadPlanException) e).getCode()).isEqualTo("STALE_PLAN");
    }

    // ---------- 并发 ----------

    @Test
    void concurrentConfirm_competingForCapacity_onlyOneSucceeds() throws Exception {
        Flight flight = newFlight("CA1014");
        Compartment fwd = addCompartment(flight, "FWD", 1000, 30, 5.0);
        CargoUnit u1 = registerUnit("ULD014A", 800, 5);
        CargoUnit u2 = registerUnit("ULD014B", 800, 5);
        loadPlanService.preparePlan("PLAN-14A", flight.getId(),
                List.of(new PlacementSpec(u1.getId(), fwd.getId())));
        loadPlanService.preparePlan("PLAN-14B", flight.getId(),
                List.of(new PlacementSpec(u2.getId(), fwd.getId())));

        AtomicInteger successes = new AtomicInteger();
        runConcurrently(List.of(
                () -> loadPlanService.confirmPlan("PLAN-14A"),
                () -> loadPlanService.confirmPlan("PLAN-14B")), successes);

        assertThat(successes.get()).isEqualTo(1);
        var usage = loadPlanService.compartmentUsage(flight.getId());
        assertThat(usage.get(0).usedWeightKg()).isEqualTo(800);
    }

    @Test
    void concurrentPrepare_competingForSameCargo_onlyOneSucceeds() throws Exception {
        Flight flight = newFlight("CA1015");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        CargoUnit unit = registerUnit("ULD015", 100, 1);
        Long unitId = unit.getId();
        Long compartmentId = fwd.getId();
        Long flightId = flight.getId();

        AtomicInteger successes = new AtomicInteger();
        runConcurrently(List.of(
                () -> loadPlanService.preparePlan("PLAN-15A", flightId,
                        List.of(new PlacementSpec(unitId, compartmentId))),
                () -> loadPlanService.preparePlan("PLAN-15B", flightId,
                        List.of(new PlacementSpec(unitId, compartmentId)))), successes);

        assertThat(successes.get()).isEqualTo(1);
        assertThat(loadPlanRepository.findAll()).hasSize(1);
    }

    // ---------- 调整与卸载 ----------

    @Test
    void adjust_movesCargoAndReleasesOriginalSlot() {
        Flight flight = newFlight("CA1016");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        Compartment aft = addCompartment(flight, "AFT", 5000, 30, -5.0);
        CargoUnit u1 = registerUnit("ULD016A", 100, 1);
        CargoUnit u2 = registerUnit("ULD016B", 200, 1);
        loadPlanService.preparePlan("PLAN-16", flight.getId(),
                List.of(new PlacementSpec(u1.getId(), fwd.getId())));
        loadPlanService.confirmPlan("PLAN-16");

        // 整组调整：u1 移到 AFT，并加入 u2
        LoadPlan adjusted = loadPlanService.adjustPlan("PLAN-16",
                List.of(new PlacementSpec(u1.getId(), aft.getId()),
                        new PlacementSpec(u2.getId(), aft.getId())));
        assertThat(adjusted.getItems()).hasSize(2);

        var usage = loadPlanService.compartmentUsage(flight.getId());
        var fwdUsage = usage.stream().filter(u -> u.compartmentCode().equals("FWD")).findFirst().orElseThrow();
        var aftUsage = usage.stream().filter(u -> u.compartmentCode().equals("AFT")).findFirst().orElseThrow();
        assertThat(fwdUsage.usedWeightKg()).isEqualTo(0);
        assertThat(aftUsage.usedWeightKg()).isEqualTo(300);
    }

    @Test
    void failedAdjust_leavesOriginalPlanUnchanged() {
        Flight flight = newFlight("CA1017");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        Compartment small = addCompartment(flight, "SMALL", 50, 30, -5.0);
        CargoUnit unit = registerUnit("ULD017", 100, 1);
        loadPlanService.preparePlan("PLAN-17", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId())));
        loadPlanService.confirmPlan("PLAN-17");

        // 目标货舱容量不足 → 调整失败，原配载不变
        assertThatThrownBy(() -> loadPlanService.adjustPlan("PLAN-17",
                List.of(new PlacementSpec(unit.getId(), small.getId()))))
                .isInstanceOf(LoadPlanException.class);

        LoadPlan plan = loadPlanService.getPlan("PLAN-17");
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.CONFIRMED);
        assertThat(plan.getItems()).hasSize(1);
        assertThat(plan.getItems().get(0).getCompartment().getCode()).isEqualTo("FWD");
        var usage = loadPlanService.compartmentUsage(flight.getId());
        var fwdUsage = usage.stream().filter(u -> u.compartmentCode().equals("FWD")).findFirst().orElseThrow();
        assertThat(fwdUsage.usedWeightKg()).isEqualTo(100);
    }

    @Test
    void offload_beforeClose_releasesCargoAndCapacity() {
        Flight flight = newFlight("CA1018");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        CargoUnit u1 = registerUnit("ULD018A", 100, 1);
        CargoUnit u2 = registerUnit("ULD018B", 200, 1);
        loadPlanService.preparePlan("PLAN-18", flight.getId(),
                List.of(new PlacementSpec(u1.getId(), fwd.getId()),
                        new PlacementSpec(u2.getId(), fwd.getId())));
        loadPlanService.confirmPlan("PLAN-18");

        loadPlanService.offloadUnits("PLAN-18", List.of(u1.getId()));

        assertThat(cargoUnitRepository.findByUnitNo("ULD018A").orElseThrow().getStatus())
                .isEqualTo(CargoUnitStatus.AVAILABLE);
        var usage = loadPlanService.compartmentUsage(flight.getId());
        assertThat(usage.get(0).usedWeightKg()).isEqualTo(200);

        // 释放后的货物可以进入新方案
        loadPlanService.preparePlan("PLAN-18B", flight.getId(),
                List.of(new PlacementSpec(u1.getId(), fwd.getId())));
        assertThat(loadPlanService.getPlan("PLAN-18B").getStatus()).isEqualTo(PlanStatus.DRAFT);
    }

    // ---------- 航班关闭 ----------

    @Test
    void closedFlight_plansImmutable_offloadEventsRecorded() {
        Flight flight = newFlight("CA1019");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        CargoUnit unit = registerUnit("ULD019", 100, 1);
        loadPlanService.preparePlan("PLAN-19", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId())));
        loadPlanService.confirmPlan("PLAN-19");
        // 另准备一个未确认的草稿方案，用于验证关闭后不可再确认
        CargoUnit draftUnit = registerUnit("ULD019D", 60, 1);
        loadPlanService.preparePlan("PLAN-19D", flight.getId(),
                List.of(new PlacementSpec(draftUnit.getId(), fwd.getId())));
        flightService.closeFlight(flight.getId());

        // 关闭后：确认/调整/卸载/准备均不可行
        assertThatThrownBy(() -> loadPlanService.confirmPlan("PLAN-19D"))
                .isInstanceOf(LoadPlanException.class)
                .extracting(e -> ((LoadPlanException) e).getCode()).isEqualTo("FLIGHT_CLOSED");
        assertThatThrownBy(() -> loadPlanService.adjustPlan("PLAN-19", List.of()))
                .isInstanceOf(LoadPlanException.class)
                .extracting(e -> ((LoadPlanException) e).getCode()).isEqualTo("FLIGHT_CLOSED");
        assertThatThrownBy(() -> loadPlanService.offloadUnits("PLAN-19", List.of(unit.getId())))
                .isInstanceOf(LoadPlanException.class)
                .extracting(e -> ((LoadPlanException) e).getCode()).isEqualTo("FLIGHT_CLOSED");
        CargoUnit other = registerUnit("ULD019B", 50, 1);
        assertThatThrownBy(() -> loadPlanService.preparePlan("PLAN-19B", flight.getId(),
                List.of(new PlacementSpec(other.getId(), fwd.getId()))))
                .isInstanceOf(LoadPlanException.class)
                .extracting(e -> ((LoadPlanException) e).getCode()).isEqualTo("FLIGHT_CLOSED");

        // 只能记录实际卸载事件
        var event = loadPlanService.recordOffloadEvent(flight.getId(), "ULD019", "目的地卸货");
        assertThat(event.getPlanNo()).isEqualTo("PLAN-19");

        // 方案保持已确认不变，货物标记为已卸载
        assertThat(loadPlanService.getPlan("PLAN-19").getStatus()).isEqualTo(PlanStatus.CONFIRMED);
        CargoUnit offloaded = loadPlanService.cargoWhereabouts("ULD019");
        assertThat(offloaded.getStatus()).isEqualTo(CargoUnitStatus.OFFLOADED);
        assertThat(loadPlanService.offloadEventsOf("ULD019")).hasSize(1);
    }

    @Test
    void offloadEvent_beforeClose_rejected() {
        Flight flight = newFlight("CA1020");
        Compartment fwd = addCompartment(flight, "FWD", 5000, 30, 5.0);
        CargoUnit unit = registerUnit("ULD020", 100, 1);
        loadPlanService.preparePlan("PLAN-20", flight.getId(),
                List.of(new PlacementSpec(unit.getId(), fwd.getId())));

        assertThatThrownBy(() -> loadPlanService.recordOffloadEvent(flight.getId(), "ULD020", "太早"))
                .isInstanceOf(LoadPlanException.class)
                .extracting(e -> ((LoadPlanException) e).getCode()).isEqualTo("FLIGHT_NOT_CLOSED");
    }

    // ---------- 测试辅助 ----------

    private Flight newFlight(String flightNo) {
        // 宽重心包线，默认不因重心失败
        return flightService.createFlight(flightNo, 10_000, 0, -100, 100);
    }

    private Compartment addCompartment(Flight flight, String code, double maxWeight, double maxVolume,
                                       double arm) {
        return flightService.addCompartment(flight.getId(), code, maxWeight, maxVolume, arm,
                Set.of(CargoCategory.GENERAL));
    }

    private CargoUnit registerUnit(String unitNo, double weightKg, double volumeM3) {
        return cargoUnitService.register(unitNo, weightKg, volumeM3, CargoCategory.GENERAL, Set.of());
    }

    private void runConcurrently(List<Runnable> tasks, AtomicInteger successes) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        for (Runnable task : tasks) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                    successes.incrementAndGet();
                } catch (LoadPlanException expected) {
                    // 争抢失败的一方
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();
    }
}

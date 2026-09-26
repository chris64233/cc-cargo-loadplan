package com.chris64233.cargoloadplan.service;

import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.domain.CargoUnitStatus;
import com.chris64233.cargoloadplan.domain.Compartment;
import com.chris64233.cargoloadplan.domain.Flight;
import com.chris64233.cargoloadplan.domain.FlightStatus;
import com.chris64233.cargoloadplan.domain.LoadPlan;
import com.chris64233.cargoloadplan.domain.LoadPlanItem;
import com.chris64233.cargoloadplan.domain.OffloadEvent;
import com.chris64233.cargoloadplan.domain.PlanStatus;
import com.chris64233.cargoloadplan.repository.CargoUnitRepository;
import com.chris64233.cargoloadplan.repository.FlightRepository;
import com.chris64233.cargoloadplan.repository.LoadPlanRepository;
import com.chris64233.cargoloadplan.repository.OffloadEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配载方案核心服务。
 *
 * 并发模型：所有变更操作先对航班行加悲观写锁（串行化同一航班的配载决策），
 * 再按 id 顺序锁定涉及的货物单元；配合 planNo 唯一约束保证幂等，
 * 并发争抢同一货物或剩余舱位时最多一个成功。
 */
@Service
public class LoadPlanService {

    /** 一条装载指令：货物单元 → 货舱。 */
    public record PlacementSpec(Long cargoUnitId, Long compartmentId) {
    }

    /** 货舱用量视图。 */
    public record CompartmentUsage(String compartmentCode, double maxWeightKg, double usedWeightKg,
                                   double remainingWeightKg, double maxVolumeM3, double usedVolumeM3,
                                   double remainingVolumeM3) {
    }

    private final FlightRepository flights;
    private final CargoUnitRepository cargoUnits;
    private final LoadPlanRepository loadPlans;
    private final OffloadEventRepository offloadEvents;
    private final LoadConstraintService constraints;

    public LoadPlanService(FlightRepository flights, CargoUnitRepository cargoUnits,
                           LoadPlanRepository loadPlans, OffloadEventRepository offloadEvents,
                           LoadConstraintService constraints) {
        this.flights = flights;
        this.cargoUnits = cargoUnits;
        this.loadPlans = loadPlans;
        this.offloadEvents = offloadEvents;
        this.constraints = constraints;
    }

    /**
     * 准备配载方案（幂等）：相同 planNo 且内容一致时直接返回已有方案；
     * planNo 相同但内容不同则冲突。成功后货物单元被锁定给本方案。
     */
    @Transactional
    public LoadPlan preparePlan(String planNo, Long flightId, List<PlacementSpec> specs) {
        var existing = loadPlans.findByPlanNo(planNo);
        if (existing.isPresent()) {
            LoadPlan plan = existing.get();
            if (!plan.getFlight().getId().equals(flightId) || !sameItems(plan, specs)) {
                throw new LoadPlanException("PLAN_NO_CONFLICT",
                        "planNo " + planNo + " is already used by a different plan");
            }
            return plan;
        }
        if (specs == null || specs.isEmpty()) {
            throw new LoadPlanException("EMPTY_PLAN", "a load plan must contain at least one item");
        }
        Flight flight = flights.findByIdForUpdate(flightId)
                .orElseThrow(() -> new NotFoundException("flight " + flightId + " not found"));
        requireOpen(flight);

        List<CargoUnit> units = lockAvailableUnits(specs);
        List<LoadConstraintService.Placement> placements = buildPlacements(flight, units, specs);
        constraints.validatePlacements(flight, placements, confirmedItems(flight.getId(), null));

        LoadPlan plan = new LoadPlan(planNo, flight, constraints.fingerprint(flight, units));
        for (LoadConstraintService.Placement placement : placements) {
            plan.addItem(new LoadPlanItem(plan, placement.unit(), placement.compartment()));
        }
        units.forEach(u -> u.assignTo(plan));
        return loadPlans.save(plan);
    }

    /**
     * 确认配载：重算配置指纹（准备期间货物重量或航班配置变化则拒绝），
     * 重新校验全部舱位限制、互斥货物与整机重心，然后在同一事务内
     * 原子锁定全部货物单元并置方案为已确认；任何一步失败整体回滚。
     * 已确认的方案重复确认是幂等成功。
     */
    @Transactional
    public LoadPlan confirmPlan(String planNo) {
        LoadPlan plan = loadPlans.findByPlanNo(planNo)
                .orElseThrow(() -> new NotFoundException("plan " + planNo + " not found"));
        if (plan.getStatus() == PlanStatus.CONFIRMED) {
            return plan;
        }
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("flight not found"));
        requireOpen(flight);
        if (plan.getStatus() != PlanStatus.DRAFT) {
            throw new LoadPlanException("PLAN_NOT_DRAFT",
                    "plan " + planNo + " is " + plan.getStatus() + " and cannot be confirmed");
        }

        List<Long> unitIds = plan.getItems().stream()
                .map(item -> item.getCargoUnit().getId()).sorted().toList();
        List<CargoUnit> units = cargoUnits.findAllByIdForUpdate(unitIds);
        String currentFingerprint = constraints.fingerprint(flight, units);
        if (!currentFingerprint.equals(plan.getConfigFingerprint())) {
            throw new LoadPlanException("STALE_PLAN",
                    "cargo weights or flight configuration changed since plan " + planNo + " was prepared");
        }
        for (CargoUnit unit : units) {
            if (unit.getActivePlan() == null || !unit.getActivePlan().getId().equals(plan.getId())) {
                throw new LoadPlanException("CARGO_NOT_HELD",
                        "cargo unit " + unit.getUnitNo() + " is no longer held by plan " + planNo);
            }
        }

        List<LoadConstraintService.Placement> placements = plan.getItems().stream()
                .map(item -> new LoadConstraintService.Placement(item.getCargoUnit(), item.getCompartment()))
                .toList();
        constraints.validatePlacements(flight, placements, confirmedItems(flight.getId(), plan.getId()));

        plan.setStatus(PlanStatus.CONFIRMED);
        return loadPlans.save(plan);
    }

    /**
     * 整组调整：用新的装载指令集整体替换方案当前明细。
     * 校验通过并提交后才释放原舱位/原货物；任何失败回滚，原配载不变。
     */
    @Transactional
    public LoadPlan adjustPlan(String planNo, List<PlacementSpec> specs) {
        LoadPlan plan = loadPlans.findByPlanNo(planNo)
                .orElseThrow(() -> new NotFoundException("plan " + planNo + " not found"));
        if (plan.getStatus() == PlanStatus.CANCELLED) {
            throw new LoadPlanException("PLAN_CANCELLED", "plan " + planNo + " is cancelled");
        }
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("flight not found"));
        requireOpen(flight);
        if (specs == null) {
            throw new LoadPlanException("EMPTY_PLAN", "adjustment specs must not be null");
        }

        Set<Long> allUnitIds = new TreeSet<>();
        specs.forEach(spec -> allUnitIds.add(spec.cargoUnitId()));
        plan.getItems().forEach(item -> allUnitIds.add(item.getCargoUnit().getId()));
        Map<Long, CargoUnit> unitsById = lockUnits(allUnitIds);

        Set<Long> newIds = new HashSet<>();
        for (PlacementSpec spec : specs) {
            CargoUnit unit = unitsById.get(spec.cargoUnitId());
            if (unit == null) {
                throw new NotFoundException("cargo unit " + spec.cargoUnitId() + " not found");
            }
            boolean heldByThisPlan = unit.getActivePlan() != null
                    && unit.getActivePlan().getId().equals(plan.getId());
            if (!heldByThisPlan && unit.getStatus() != CargoUnitStatus.AVAILABLE) {
                throw new LoadPlanException("CARGO_UNAVAILABLE",
                        "cargo unit " + unit.getUnitNo() + " already belongs to another active plan");
            }
            if (!newIds.add(spec.cargoUnitId())) {
                throw new LoadPlanException("DUPLICATE_CARGO",
                        "cargo unit " + unit.getUnitNo() + " appears more than once");
            }
        }

        List<LoadConstraintService.Placement> placements = buildPlacements(flight, unitsById, specs);
        constraints.validatePlacements(flight, placements, confirmedItems(flight.getId(), plan.getId()));

        // 校验全部通过后才变更：释放被移出的货物，替换明细，锁定新货物。
        for (LoadPlanItem item : new ArrayList<>(plan.getItems())) {
            if (!newIds.contains(item.getCargoUnit().getId())) {
                item.getCargoUnit().release();
            }
        }
        plan.getItems().clear();
        for (LoadConstraintService.Placement placement : placements) {
            plan.addItem(new LoadPlanItem(plan, placement.unit(), placement.compartment()));
            placement.unit().assignTo(plan);
        }
        plan.setConfigFingerprint(constraints.fingerprint(flight,
                placements.stream().map(LoadConstraintService.Placement::unit).toList()));
        return loadPlans.save(plan);
    }

    /**
     * 航班关闭前从方案中卸载部分货物单元：释放舱位与货物锁定。
     */
    @Transactional
    public LoadPlan offloadUnits(String planNo, List<Long> cargoUnitIds) {
        LoadPlan plan = loadPlans.findByPlanNo(planNo)
                .orElseThrow(() -> new NotFoundException("plan " + planNo + " not found"));
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("flight not found"));
        requireOpen(flight);

        Set<Long> ids = new HashSet<>(cargoUnitIds);
        List<CargoUnit> remaining = new ArrayList<>();
        for (LoadPlanItem item : new ArrayList<>(plan.getItems())) {
            if (ids.contains(item.getCargoUnit().getId())) {
                item.getCargoUnit().release();
                plan.getItems().remove(item);
            } else {
                remaining.add(item.getCargoUnit());
            }
        }
        plan.setConfigFingerprint(constraints.fingerprint(flight, remaining));
        return loadPlans.save(plan);
    }

    /**
     * 航班关闭后记录实际卸载事件：方案本身不可再修改，仅追加事件并标记货物已卸载。
     */
    @Transactional
    public OffloadEvent recordOffloadEvent(Long flightId, String unitNo, String reason) {
        Flight flight = flights.findByIdForUpdate(flightId)
                .orElseThrow(() -> new NotFoundException("flight " + flightId + " not found"));
        if (flight.getStatus() != FlightStatus.CLOSED) {
            throw new LoadPlanException("FLIGHT_NOT_CLOSED",
                    "offload events can only be recorded after flight " + flight.getFlightNo() + " is closed");
        }
        CargoUnit unit = cargoUnits.findByUnitNo(unitNo)
                .orElseThrow(() -> new NotFoundException("cargo unit " + unitNo + " not found"));
        LoadPlan plan = unit.getActivePlan();
        if (unit.getStatus() != CargoUnitStatus.ALLOCATED || plan == null
                || !plan.getFlight().getId().equals(flightId)) {
            throw new LoadPlanException("CARGO_NOT_ON_FLIGHT",
                    "cargo unit " + unitNo + " is not loaded on flight " + flight.getFlightNo());
        }
        unit.setStatus(CargoUnitStatus.OFFLOADED);
        return offloadEvents.save(new OffloadEvent(flight, unit, plan.getPlanNo(), reason));
    }

    @Transactional(readOnly = true)
    public LoadPlan getPlan(String planNo) {
        LoadPlan plan = loadPlans.findByPlanNo(planNo)
                .orElseThrow(() -> new NotFoundException("plan " + planNo + " not found"));
        // 在事务内初始化明细及其关联，便于事务外读取
        plan.getItems().forEach(item -> {
            item.getCargoUnit().getUnitNo();
            item.getCompartment().getCode();
        });
        return plan;
    }

    /** 各货舱用量（按已确认方案统计）。 */
    @Transactional(readOnly = true)
    public List<CompartmentUsage> compartmentUsage(Long flightId) {
        Flight flight = flights.findById(flightId)
                .orElseThrow(() -> new NotFoundException("flight " + flightId + " not found"));
        List<LoadPlanItem> confirmed = confirmedItems(flightId, null);
        List<CompartmentUsage> usage = new ArrayList<>();
        for (Compartment compartment : flight.getCompartments()) {
            double usedWeight = 0;
            double usedVolume = 0;
            for (LoadPlanItem item : confirmed) {
                if (item.getCompartment().getId().equals(compartment.getId())) {
                    usedWeight += item.getCargoUnit().getWeightKg();
                    usedVolume += item.getCargoUnit().getVolumeM3();
                }
            }
            usage.add(new CompartmentUsage(compartment.getCode(), compartment.getMaxWeightKg(), usedWeight,
                    compartment.getMaxWeightKg() - usedWeight, compartment.getMaxVolumeM3(), usedVolume,
                    compartment.getMaxVolumeM3() - usedVolume));
        }
        return usage;
    }

    /** 当前已确认装载下的整机重心与包线校验结果。 */
    @Transactional(readOnly = true)
    public LoadConstraintService.CgResult constraintComputation(Long flightId) {
        Flight flight = flights.findById(flightId)
                .orElseThrow(() -> new NotFoundException("flight " + flightId + " not found"));
        return constraints.computeCg(flight, confirmedItems(flightId, null));
    }

    /** 货物去向查询。 */
    @Transactional(readOnly = true)
    public CargoUnit cargoWhereabouts(String unitNo) {
        return cargoUnits.findByUnitNo(unitNo)
                .orElseThrow(() -> new NotFoundException("cargo unit " + unitNo + " not found"));
    }

    @Transactional(readOnly = true)
    public List<OffloadEvent> offloadEventsOf(String unitNo) {
        return offloadEvents.findByCargoUnit_UnitNoOrderByRecordedAt(unitNo);
    }

    private void requireOpen(Flight flight) {
        if (flight.getStatus() != FlightStatus.OPEN) {
            throw new LoadPlanException("FLIGHT_CLOSED",
                    "flight " + flight.getFlightNo() + " is closed; load plans can no longer be modified");
        }
    }

    private List<LoadPlanItem> confirmedItems(Long flightId, Long excludePlanId) {
        return loadPlans.findByFlight_IdAndStatus(flightId, PlanStatus.CONFIRMED).stream()
                .filter(plan -> excludePlanId == null || !plan.getId().equals(excludePlanId))
                .flatMap(plan -> plan.getItems().stream())
                .toList();
    }

    private List<CargoUnit> lockAvailableUnits(List<PlacementSpec> specs) {
        Set<Long> ids = new TreeSet<>();
        for (PlacementSpec spec : specs) {
            if (!ids.add(spec.cargoUnitId())) {
                throw new LoadPlanException("DUPLICATE_CARGO",
                        "cargo unit " + spec.cargoUnitId() + " appears more than once");
            }
        }
        Map<Long, CargoUnit> unitsById = lockUnits(ids);
        List<CargoUnit> units = new ArrayList<>();
        for (Long id : ids) {
            CargoUnit unit = unitsById.get(id);
            if (unit == null) {
                throw new NotFoundException("cargo unit " + id + " not found");
            }
            if (unit.getStatus() != CargoUnitStatus.AVAILABLE || unit.getActivePlan() != null) {
                throw new LoadPlanException("CARGO_UNAVAILABLE",
                        "cargo unit " + unit.getUnitNo() + " already belongs to an active plan");
            }
            units.add(unit);
        }
        return units;
    }

    private Map<Long, CargoUnit> lockUnits(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return cargoUnits.findAllByIdForUpdate(List.copyOf(ids)).stream()
                .collect(Collectors.toMap(CargoUnit::getId, Function.identity()));
    }

    private List<LoadConstraintService.Placement> buildPlacements(Flight flight, List<CargoUnit> units,
                                                                  List<PlacementSpec> specs) {
        Map<Long, CargoUnit> unitsById = units.stream()
                .collect(Collectors.toMap(CargoUnit::getId, Function.identity()));
        return buildPlacements(flight, unitsById, specs);
    }

    private List<LoadConstraintService.Placement> buildPlacements(Flight flight,
                                                                  Map<Long, CargoUnit> unitsById,
                                                                  List<PlacementSpec> specs) {
        Map<Long, Compartment> compartmentsById = flight.getCompartments().stream()
                .collect(Collectors.toMap(Compartment::getId, Function.identity()));
        List<LoadConstraintService.Placement> placements = new ArrayList<>();
        for (PlacementSpec spec : specs) {
            CargoUnit unit = unitsById.get(spec.cargoUnitId());
            if (unit == null) {
                throw new NotFoundException("cargo unit " + spec.cargoUnitId() + " not found");
            }
            Compartment compartment = compartmentsById.get(spec.compartmentId());
            if (compartment == null) {
                throw new LoadPlanException("COMPARTMENT_NOT_FOUND",
                        "compartment " + spec.compartmentId() + " does not belong to flight "
                                + flight.getFlightNo());
            }
            placements.add(new LoadConstraintService.Placement(unit, compartment));
        }
        return placements;
    }

    private boolean sameItems(LoadPlan plan, List<PlacementSpec> specs) {
        if (specs == null || plan.getItems().size() != specs.size()) {
            return false;
        }
        Set<String> expected = specs.stream()
                .map(spec -> spec.cargoUnitId() + "@" + spec.compartmentId())
                .collect(Collectors.toSet());
        Set<String> actual = plan.getItems().stream()
                .map(item -> item.getCargoUnit().getId() + "@" + item.getCompartment().getId())
                .collect(Collectors.toSet());
        return expected.equals(actual);
    }
}

package com.chris64233.cargoloadplan.service;

import com.chris64233.cargoloadplan.domain.CargoHold;
import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.domain.Flight;
import com.chris64233.cargoloadplan.domain.FlightStatus;
import com.chris64233.cargoloadplan.domain.LoadPlan;
import com.chris64233.cargoloadplan.domain.PlanAssignment;
import com.chris64233.cargoloadplan.domain.PlanStatus;
import com.chris64233.cargoloadplan.domain.UnitStatus;
import com.chris64233.cargoloadplan.dto.AdjustPlanRequest;
import com.chris64233.cargoloadplan.dto.AssignmentRequest;
import com.chris64233.cargoloadplan.dto.CreatePlanRequest;
import com.chris64233.cargoloadplan.dto.PlanResponse;
import com.chris64233.cargoloadplan.repository.CargoUnitRepository;
import com.chris64233.cargoloadplan.repository.FlightRepository;
import com.chris64233.cargoloadplan.repository.LoadPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配载方案核心服务。
 *
 * 并发与一致性约定：
 * - 所有变更操作先对航班行加悲观写锁，串行化同一航班上的确认/调整/卸载；
 * - 方案号全局唯一，作为幂等键；
 * - 方案准备时记录航班配置版本与货物版本快照，确认时逐一比对；
 * - 确认在单个事务内完成全部校验与货物锁定，任一约束不满足即整体回滚，
 *   不会留下部分装载状态。
 */
@Service
public class LoadPlanService {

    private final FlightRepository flights;
    private final CargoUnitRepository units;
    private final LoadPlanRepository plans;
    private final ConstraintValidator validator;

    public LoadPlanService(FlightRepository flights, CargoUnitRepository units,
                           LoadPlanRepository plans, ConstraintValidator validator) {
        this.flights = flights;
        this.units = units;
        this.plans = plans;
        this.validator = validator;
    }

    /**
     * 准备配载方案。方案号幂等：已存在时直接返回既有方案。
     * 准备时记录航班配置版本与货物版本快照，并对当前载重做一次完整约束预检。
     */
    @Transactional
    public PlanResponse createPlan(String flightNo, CreatePlanRequest req) {
        var existing = plans.findByPlanNo(req.planNo());
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        Flight flight = flights.findByFlightNoForUpdate(flightNo)
                .orElseThrow(() -> new NotFoundException("航班不存在: " + flightNo));
        requireOpen(flight);

        Set<String> seen = new HashSet<>();
        LoadPlan plan = new LoadPlan(req.planNo(), flight, flight.getVersion());
        List<ConstraintValidator.LoadItem> prospective = lockedItems(flight);
        for (AssignmentRequest a : req.assignments()) {
            if (!seen.add(a.unitNo())) {
                throw new LoadConstraintException(List.of("方案内货物重复: " + a.unitNo()));
            }
            CargoUnit unit = units.findByUnitNo(a.unitNo())
                    .orElseThrow(() -> new NotFoundException("货物单元不存在: " + a.unitNo()));
            if (unit.getStatus() != UnitStatus.AVAILABLE) {
                throw new ConflictException("货物 " + a.unitNo() + " 已属于其他活动配载方案");
            }
            plan.addAssignment(new PlanAssignment(plan, a.unitNo(), a.holdCode(), unit.getVersion()));
            prospective.add(item(unit, a.holdCode()));
        }
        List<String> violations = validator.validate(flight, prospective);
        if (!violations.isEmpty()) {
            throw new LoadConstraintException(violations);
        }
        plans.save(plan);
        return toResponse(plan);
    }

    /**
     * 确认方案。幂等：已确认的方案重复确认直接返回。
     * 校验快照有效性、货物可用性与全部舱位/互斥/重心约束后，
     * 在同一事务内原子锁定全部货物单元；任一约束不满足即整体回滚。
     */
    @Transactional
    public PlanResponse confirm(String planNo) {
        LoadPlan plan = findPlan(planNo);
        if (plan.getStatus() == PlanStatus.CONFIRMED) {
            return toResponse(plan);
        }
        if (plan.getStatus() == PlanStatus.CANCELLED) {
            throw new ConflictException("方案已取消，不能确认: " + planNo);
        }
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("航班不存在"));
        requireOpen(flight);
        if (flight.getVersion() != plan.getFlightVersionSnapshot()) {
            throw new ConflictException("航班配置已变化，方案快照失效，请重新准备方案: " + planNo);
        }

        Map<String, CargoUnit> planUnits = new HashMap<>();
        for (PlanAssignment a : plan.getAssignments()) {
            CargoUnit unit = units.findByUnitNo(a.getUnitNo())
                    .orElseThrow(() -> new NotFoundException("货物单元不存在: " + a.getUnitNo()));
            if (unit.getVersion() != a.getUnitVersionSnapshot()) {
                throw new ConflictException("货物 " + a.getUnitNo()
                        + " 信息已变化，方案快照失效，请重新准备方案: " + planNo);
            }
            if (unit.getStatus() != UnitStatus.AVAILABLE) {
                throw new ConflictException("货物 " + a.getUnitNo() + " 已被其他方案占用");
            }
            planUnits.put(a.getUnitNo(), unit);
        }

        List<ConstraintValidator.LoadItem> prospective = lockedItems(flight);
        for (PlanAssignment a : plan.getAssignments()) {
            prospective.add(item(planUnits.get(a.getUnitNo()), a.getHoldCode()));
        }
        List<String> violations = validator.validate(flight, prospective);
        if (!violations.isEmpty()) {
            throw new LoadConstraintException(violations);
        }

        // 原子锁定全部货物单元
        Map<String, CargoHold> holds = holdsByCode(flight);
        for (PlanAssignment a : plan.getAssignments()) {
            planUnits.get(a.getUnitNo()).lock(flight, holds.get(a.getHoldCode()), planNo);
        }
        plan.setStatus(PlanStatus.CONFIRMED);
        return toResponse(plan);
    }

    /**
     * 整组调整：对方案内货物重新分舱、加入空闲货物或卸载部分货物。
     * 先对调整后的整机载重做完整约束校验，通过后才在同一事务内应用变更
     * （重新分舱/加入与释放原舱位一起提交）；校验失败即回滚，原配载不变。
     */
    @Transactional
    public PlanResponse adjust(String planNo, AdjustPlanRequest req) {
        LoadPlan plan = findPlan(planNo);
        if (plan.getStatus() != PlanStatus.CONFIRMED) {
            throw new ConflictException("只有已确认的方案才能调整: " + planNo);
        }
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("航班不存在"));
        requireOpen(flight);

        List<AssignmentRequest> reassign = req.assignments() == null ? List.of() : req.assignments();
        List<String> unload = req.unloadUnitNos() == null ? List.of() : req.unloadUnitNos();

        Map<String, CargoUnit> planUnits = units.findByPlanNoAndStatus(planNo, UnitStatus.LOCKED)
                .stream().collect(Collectors.toMap(CargoUnit::getUnitNo, Function.identity()));
        for (String unitNo : unload) {
            if (!planUnits.containsKey(unitNo)) {
                throw new ConflictException("货物 " + unitNo + " 不属于方案 " + planNo);
            }
        }
        Map<String, CargoUnit> reassignUnits = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (AssignmentRequest a : reassign) {
            if (!seen.add(a.unitNo())) {
                throw new LoadConstraintException(List.of("调整请求中货物重复: " + a.unitNo()));
            }
            CargoUnit unit = planUnits.get(a.unitNo());
            if (unit == null) {
                unit = units.findByUnitNo(a.unitNo())
                        .orElseThrow(() -> new NotFoundException("货物单元不存在: " + a.unitNo()));
                if (unit.getStatus() != UnitStatus.AVAILABLE) {
                    throw new ConflictException("货物 " + a.unitNo() + " 已被其他方案占用");
                }
            }
            reassignUnits.put(a.unitNo(), unit);
        }

        // 前瞻载重：全机已锁定货物应用本次调整后的结果
        Map<String, String> newHold = reassign.stream()
                .collect(Collectors.toMap(AssignmentRequest::unitNo, AssignmentRequest::holdCode));
        List<ConstraintValidator.LoadItem> prospective = new ArrayList<>();
        for (CargoUnit u : units.findByFlightIdAndStatus(flight.getId(), UnitStatus.LOCKED)) {
            if (unload.contains(u.getUnitNo())) {
                continue;
            }
            prospective.add(item(u, newHold.getOrDefault(u.getUnitNo(), u.getHold().getCode())));
        }
        for (Map.Entry<String, CargoUnit> e : reassignUnits.entrySet()) {
            if (!planUnits.containsKey(e.getKey())) {
                prospective.add(item(e.getValue(), newHold.get(e.getKey())));
            }
        }
        List<String> violations = validator.validate(flight, prospective);
        if (!violations.isEmpty()) {
            throw new LoadConstraintException(violations);
        }

        // 校验通过后才应用：重新分舱/加入与释放原舱位在同一事务内提交
        Map<String, CargoHold> holds = holdsByCode(flight);
        for (Map.Entry<String, CargoUnit> e : reassignUnits.entrySet()) {
            e.getValue().lock(flight, holds.get(newHold.get(e.getKey())), planNo);
        }
        for (String unitNo : unload) {
            planUnits.get(unitNo).release();
        }

        // 刷新方案快照
        plan.getAssignments().removeIf(a -> unload.contains(a.getUnitNo()));
        for (PlanAssignment a : plan.getAssignments()) {
            String holdCode = newHold.get(a.getUnitNo());
            if (holdCode != null) {
                a.setHoldCode(holdCode);
            }
            CargoUnit unit = reassignUnits.getOrDefault(a.getUnitNo(), planUnits.get(a.getUnitNo()));
            if (unit != null) {
                a.setUnitVersionSnapshot(unit.getVersion());
            }
        }
        for (Map.Entry<String, CargoUnit> e : reassignUnits.entrySet()) {
            if (!planUnits.containsKey(e.getKey())) {
                plan.addAssignment(new PlanAssignment(plan, e.getKey(),
                        newHold.get(e.getKey()), e.getValue().getVersion()));
            }
        }
        plan.setFlightVersionSnapshot(flight.getVersion());
        return toResponse(plan);
    }

    /** 整组卸载：释放方案锁定的全部货物单元，方案取消。仅航班关闭前可用。 */
    @Transactional
    public PlanResponse unloadPlan(String planNo) {
        LoadPlan plan = findPlan(planNo);
        if (plan.getStatus() != PlanStatus.CONFIRMED) {
            throw new ConflictException("只有已确认的方案才能卸载: " + planNo);
        }
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("航班不存在"));
        requireOpen(flight);
        for (CargoUnit unit : units.findByPlanNoAndStatus(planNo, UnitStatus.LOCKED)) {
            unit.release();
        }
        plan.setStatus(PlanStatus.CANCELLED);
        return toResponse(plan);
    }

    @Transactional(readOnly = true)
    public PlanResponse getPlan(String planNo) {
        return toResponse(findPlan(planNo));
    }

    private LoadPlan findPlan(String planNo) {
        return plans.findByPlanNo(planNo)
                .orElseThrow(() -> new NotFoundException("方案不存在: " + planNo));
    }

    private void requireOpen(Flight flight) {
        if (flight.getStatus() != FlightStatus.OPEN) {
            throw new ConflictException("航班已关闭，方案不可修改: " + flight.getFlightNo());
        }
    }

    private List<ConstraintValidator.LoadItem> lockedItems(Flight flight) {
        return units.findByFlightIdAndStatus(flight.getId(), UnitStatus.LOCKED).stream()
                .map(u -> item(u, u.getHold().getCode()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private ConstraintValidator.LoadItem item(CargoUnit unit, String holdCode) {
        return new ConstraintValidator.LoadItem(unit.getUnitNo(), holdCode, unit.getWeight(),
                unit.getVolume(), unit.getCategory(), unit.getIncompatibleCategories());
    }

    private Map<String, CargoHold> holdsByCode(Flight flight) {
        return flight.getHolds().stream()
                .collect(Collectors.toMap(CargoHold::getCode, Function.identity()));
    }

    private PlanResponse toResponse(LoadPlan plan) {
        return new PlanResponse(plan.getPlanNo(), plan.getFlight().getFlightNo(),
                plan.getStatus().name(), plan.getFlightVersionSnapshot(),
                plan.getAssignments().stream()
                        .map(a -> new AssignmentRequest(a.getUnitNo(), a.getHoldCode()))
                        .toList());
    }
}

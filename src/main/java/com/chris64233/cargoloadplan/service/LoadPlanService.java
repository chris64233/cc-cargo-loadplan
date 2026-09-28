package com.chris64233.cargoloadplan.service;

import com.chris64233.cargoloadplan.domain.CargoHold;
import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.domain.Flight;
import com.chris64233.cargoloadplan.domain.FlightStatus;
import com.chris64233.cargoloadplan.domain.LoadPlan;
import com.chris64233.cargoloadplan.domain.PlanAssignment;
import com.chris64233.cargoloadplan.domain.PlanStatus;
import com.chris64233.cargoloadplan.domain.PlanVersion;
import com.chris64233.cargoloadplan.domain.PlanVersionItem;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配载方案核心服务。
 *
 * 版本模型：方案首次确认形成版本 1（ACTIVE）；起飞前每次临时卸货/替换/重新分舱都生成
 * 一个新版本——先以 PROPOSED 保存完整的调整后装机明细（不动货物与舱位占用），确认时
 * 才在单个事务内完成切换。被取代的版本永久保留（SUPERSEDED）。
 *
 * 并发与一致性约定：
 * - 所有变更操作先对航班行加悲观写锁，串行化同一航班上的确认/调整/卸载/关闭；
 * - 方案号全局唯一，作为幂等键；
 * - 方案准备/调整时记录航班配置版本与每个货物单元的乐观锁版本快照，确认时逐一比对；
 * - 跨航班争抢同一货物由货物 {@code @Version} 乐观锁兜底：最多一个事务提交成功；
 * - 确认/切换在单个事务内完成全部校验与货物锁定/释放，任一约束不满足即整体回滚，
 *   不会留下部分装载状态，原配载继续有效。
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
        List<ConstraintValidator.LoadItem> prospective = lockedItemsOfOtherPlans(flight, null);
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
     * 校验快照有效性、货物可用性与全部舱位/互斥/重心约束后，在同一事务内原子锁定全部
     * 货物单元并形成配载版本 1；任一约束不满足即整体回滚。
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

        Map<String, CargoUnit> planUnits = new LinkedHashMap<>();
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

        List<ConstraintValidator.LoadItem> prospective = lockedItemsOfOtherPlans(flight, null);
        for (PlanAssignment a : plan.getAssignments()) {
            prospective.add(item(planUnits.get(a.getUnitNo()), a.getHoldCode()));
        }
        List<String> violations = validator.validate(flight, prospective);
        if (!violations.isEmpty()) {
            throw new LoadConstraintException(violations);
        }

        // 原子锁定全部货物单元并形成版本 1
        Map<String, CargoHold> holds = holdsByCode(flight);
        Instant now = Instant.now();
        PlanVersion v1 = new PlanVersion(plan, 1, flight.getVersion(), now);
        for (PlanAssignment a : plan.getAssignments()) {
            planUnits.get(a.getUnitNo()).lock(flight, holds.get(a.getHoldCode()), planNo);
            v1.addItem(new PlanVersionItem(v1, a.getUnitNo(), a.getHoldCode(),
                    a.getUnitVersionSnapshot()));
        }
        plan.addVersion(v1);
        v1.activate(now);
        plan.setStatus(PlanStatus.CONFIRMED);
        return toResponse(plan);
    }

    /**
     * 准备临时卸货/替换调整：以当前生效版本为基线，应用"卸货 + 重新分舱 + 加入替换货物"
     * 后生成一个新版本（PROPOSED）。本方法只保存版本明细，不改动任何货物状态与舱位占用；
     * 会对调整后的整份装机方案重新校验重量、体积、重心、类别与同舱限制，不通过即抛 422，
     * 不生成新版本。同一方案已有待确认版本时，以本次请求重新生成候选版本。
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

        PlanVersion active = plan.activeVersion()
                .orElseThrow(() -> new IllegalStateException("已确认方案缺少生效版本: " + planNo));

        List<AssignmentRequest> reassign = req.assignments() == null ? List.of() : req.assignments();
        List<String> unload = req.unloadUnitNos() == null ? List.of() : req.unloadUnitNos();

        Set<String> unloadSet = new HashSet<>(unload);
        if (unloadSet.size() != unload.size()) {
            throw new LoadConstraintException(List.of("卸货列表中货物重复"));
        }
        Map<String, String> newHold = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (AssignmentRequest a : reassign) {
            if (!seen.add(a.unitNo())) {
                throw new LoadConstraintException(List.of("调整请求中货物重复: " + a.unitNo()));
            }
            newHold.put(a.unitNo(), a.holdCode());
        }
        for (String unitNo : unloadSet) {
            if (newHold.containsKey(unitNo)) {
                throw new LoadConstraintException(
                        List.of("货物 " + unitNo + " 不能同时卸货和重新分舱"));
            }
        }

        // 组装新版本的完整货物安排：保留货物（可能改舱）+ 新加入的替换货物 - 卸下货物
        Map<String, PlanVersionItem> activeItems = active.getItems().stream()
                .collect(Collectors.toMap(PlanVersionItem::getUnitNo, Function.identity(),
                        (x, y) -> x, LinkedHashMap::new));
        for (String unitNo : unloadSet) {
            if (!activeItems.containsKey(unitNo)) {
                throw new ConflictException("货物 " + unitNo + " 不属于方案 " + planNo + " 的当前版本");
            }
        }
        Map<String, CargoUnit> addedUnits = new LinkedHashMap<>();
        for (AssignmentRequest a : reassign) {
            if (!activeItems.containsKey(a.unitNo())) {
                CargoUnit unit = units.findByUnitNo(a.unitNo())
                        .orElseThrow(() -> new NotFoundException("货物单元不存在: " + a.unitNo()));
                if (unit.getStatus() != UnitStatus.AVAILABLE) {
                    throw new ConflictException("替换货物 " + a.unitNo() + " 不可用，已被其他方案占用");
                }
                addedUnits.put(a.unitNo(), unit);
            }
        }

        // 整份方案的前瞻载重：其他方案在本航班上的已锁定占用 + 本版本调整后的全部货物
        List<ConstraintValidator.LoadItem> prospective = lockedItemsOfOtherPlans(flight, planNo);
        for (PlanVersionItem item : activeItems.values()) {
            if (unloadSet.contains(item.getUnitNo())) {
                continue;
            }
            CargoUnit unit = units.findByUnitNo(item.getUnitNo()).orElseThrow();
            prospective.add(item(unit, newHold.getOrDefault(item.getUnitNo(), item.getHoldCode())));
        }
        for (Map.Entry<String, CargoUnit> e : addedUnits.entrySet()) {
            prospective.add(item(e.getValue(), newHold.get(e.getKey())));
        }
        List<String> violations = validator.validate(flight, prospective);
        if (!violations.isEmpty()) {
            throw new LoadConstraintException(violations);
        }

        // 校验通过：替换既有 PROPOSED（未确认候选不算历史版本），生成新的候选版本
        plan.proposedVersion().ifPresent(v -> plan.getVersions().remove(v));
        PlanVersion proposed = new PlanVersion(plan, plan.nextVersionNo(),
                flight.getVersion(), Instant.now());
        for (PlanVersionItem item : activeItems.values()) {
            if (unloadSet.contains(item.getUnitNo())) {
                continue;
            }
            String holdCode = newHold.getOrDefault(item.getUnitNo(), item.getHoldCode());
            CargoUnit current = units.findByUnitNo(item.getUnitNo()).orElseThrow();
            proposed.addItem(new PlanVersionItem(proposed, item.getUnitNo(), holdCode,
                    current.getVersion()));
        }
        for (Map.Entry<String, CargoUnit> e : addedUnits.entrySet()) {
            proposed.addItem(new PlanVersionItem(proposed, e.getKey(),
                    newHold.get(e.getKey()), e.getValue().getVersion()));
        }
        plan.addVersion(proposed);
        plan.setFlightVersionSnapshot(flight.getVersion());
        return toResponse(plan);
    }

    /**
     * 确认调整：把待确认（PROPOSED）版本切换为当前生效版本。
     * 在单个事务内完成：航班/货物版本比对、替换货物可用性检查、整份方案约束复核，
     * 随后一次性释放被卸货物（每件仅释放一次）、把保留/替换货物锁定到新舱位、
     * 翻转版本状态。任一环节失败则整体回滚，原版本与原配载继续有效。
     */
    @Transactional
    public PlanResponse confirmAdjustment(String planNo) {
        LoadPlan plan = findPlan(planNo);
        if (plan.getStatus() != PlanStatus.CONFIRMED) {
            throw new ConflictException("只有已确认的方案才能确认调整: " + planNo);
        }
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("航班不存在"));
        requireOpen(flight);

        PlanVersion active = plan.activeVersion()
                .orElseThrow(() -> new IllegalStateException("已确认方案缺少生效版本: " + planNo));
        PlanVersion proposed = plan.proposedVersion()
                .orElseThrow(() -> new ConflictException("方案 " + planNo + " 没有待确认的调整版本"));
        if (flight.getVersion() != proposed.getFlightVersionSnapshot()) {
            throw new ConflictException("航班配置已变化，调整版本快照失效，请重新准备调整: " + planNo);
        }

        Map<String, PlanVersionItem> activeItems = active.getItems().stream()
                .collect(Collectors.toMap(PlanVersionItem::getUnitNo, Function.identity(),
                        (x, y) -> x, LinkedHashMap::new));
        Map<String, PlanVersionItem> targetItems = proposed.getItems().stream()
                .collect(Collectors.toMap(PlanVersionItem::getUnitNo, Function.identity(),
                        (x, y) -> x, LinkedHashMap::new));

        // 逐一加载新版本涉及的货物，先判可用性再比对版本快照（此时不改状态）
        Map<String, CargoUnit> targetUnits = new LinkedHashMap<>();
        for (PlanVersionItem target : targetItems.values()) {
            CargoUnit unit = units.findByUnitNo(target.getUnitNo())
                    .orElseThrow(() -> new NotFoundException("货物单元不存在: " + target.getUnitNo()));
            if (activeItems.containsKey(target.getUnitNo())) {
                // 原方案保留货物：必须仍由本方案锁定
                if (unit.getStatus() != UnitStatus.LOCKED || !planNo.equals(unit.getPlanNo())) {
                    throw new ConflictException(
                            "原方案货物 " + target.getUnitNo() + " 已不在方案中，调整无法确认: " + planNo);
                }
            } else {
                // 替换货物：必须仍空闲可用（被其他方案占用时优先报占用，原配载继续有效）
                if (unit.getStatus() != UnitStatus.AVAILABLE) {
                    throw new ConflictException(
                            "替换货物 " + target.getUnitNo() + " 已被其他方案占用，原配载继续有效");
                }
            }
            if (unit.getVersion() != target.getUnitVersionSnapshot()) {
                throw new ConflictException("货物 " + target.getUnitNo()
                        + " 信息已变化，调整版本快照失效，请重新准备调整: " + planNo);
            }
            targetUnits.put(target.getUnitNo(), unit);
        }

        // 按调整后的整份装机方案复核全部约束（计入其他方案在本航班上的占用）
        List<ConstraintValidator.LoadItem> prospective = lockedItemsOfOtherPlans(flight, planNo);
        for (PlanVersionItem target : targetItems.values()) {
            prospective.add(item(targetUnits.get(target.getUnitNo()), target.getHoldCode()));
        }
        List<String> violations = validator.validate(flight, prospective);
        if (!violations.isEmpty()) {
            throw new LoadConstraintException(violations);
        }

        // 全部校验通过后原子切换：
        Map<String, CargoHold> holds = holdsByCode(flight);
        // 1) 被卸货物只释放一次（新版本不再包含的原方案货物）
        for (String unitNo : activeItems.keySet()) {
            if (!targetItems.containsKey(unitNo)) {
                CargoUnit removed = units.findByUnitNo(unitNo).orElseThrow();
                removed.release();
            }
        }
        // 2) 保留货物移动到新舱位、替换货物锁定入舱
        for (PlanVersionItem target : targetItems.values()) {
            targetUnits.get(target.getUnitNo())
                    .lock(flight, holds.get(target.getHoldCode()), planNo);
        }
        // 3) 翻转版本：原版本保留为 SUPERSEDED，新版本生效
        active.supersede();
        proposed.activate(Instant.now());

        // 同步方案安排为新版本明细
        plan.getAssignments().clear();
        for (PlanVersionItem target : targetItems.values()) {
            plan.addAssignment(new PlanAssignment(plan, target.getUnitNo(), target.getHoldCode(),
                    target.getUnitVersionSnapshot()));
        }
        plan.setFlightVersionSnapshot(flight.getVersion());
        return toResponse(plan);
    }

    /**
     * 整组卸载：释放方案当前版本锁定的全部货物单元（每件只释放一次），方案取消，
     * 历史版本明细全部保留。仅航班关闭前可用。幂等：重复卸载直接返回既有结果。
     */
    @Transactional
    public PlanResponse unloadPlan(String planNo) {
        LoadPlan plan = findPlan(planNo);
        if (plan.getStatus() == PlanStatus.CANCELLED) {
            // 幂等：已整组卸载过，重复请求直接返回，不再次释放
            return toResponse(plan);
        }
        if (plan.getStatus() != PlanStatus.CONFIRMED) {
            throw new ConflictException("只有已确认的方案才能卸载: " + planNo);
        }
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("航班不存在"));
        requireOpen(flight);

        // 仅释放当前仍由本方案锁定的货物，每件一次；版本明细保留
        for (CargoUnit unit : units.findByPlanNoAndStatus(planNo, UnitStatus.LOCKED)) {
            unit.release();
        }
        plan.activeVersion().ifPresent(PlanVersion::supersede);
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

    /**
     * 本航班上其他活动方案（排除 excludePlanNo）已锁定货物的前瞻占用。
     * 整份方案校验时必须计入这些占用，才能发现并发下剩余舱位不足的问题。
     */
    private List<ConstraintValidator.LoadItem> lockedItemsOfOtherPlans(Flight flight, String excludePlanNo) {
        List<ConstraintValidator.LoadItem> items = new ArrayList<>();
        for (CargoUnit u : units.findByFlightIdAndStatus(flight.getId(), UnitStatus.LOCKED)) {
            if (excludePlanNo != null && excludePlanNo.equals(u.getPlanNo())) {
                continue;
            }
            items.add(item(u, u.getHold().getCode()));
        }
        return items;
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
        List<com.chris64233.cargoloadplan.dto.PlanVersionResponse> versionViews = plan.getVersions().stream()
                .sorted(java.util.Comparator.comparingInt(PlanVersion::getVersionNo))
                .map(v -> new com.chris64233.cargoloadplan.dto.PlanVersionResponse(
                        v.getVersionNo(), v.getStatus().name(), v.getFlightVersionSnapshot(),
                        v.getCreatedAt(), v.getActivatedAt(),
                        v.getItems().stream()
                                .map(i -> new AssignmentRequest(i.getUnitNo(), i.getHoldCode()))
                                .toList()))
                .toList();

        int currentVersionNo = plan.activeVersion().map(PlanVersion::getVersionNo).orElse(0);

        // assignments 反映当前装机方案：优先 ACTIVE 版本；DRAFT 时为草稿安排
        List<AssignmentRequest> assignments;
        var active = plan.activeVersion();
        if (active.isPresent()) {
            assignments = active.get().getItems().stream()
                    .map(i -> new AssignmentRequest(i.getUnitNo(), i.getHoldCode()))
                    .toList();
        } else {
            assignments = plan.getAssignments().stream()
                    .map(a -> new AssignmentRequest(a.getUnitNo(), a.getHoldCode()))
                    .toList();
        }
        return new PlanResponse(plan.getPlanNo(), plan.getFlight().getFlightNo(),
                plan.getStatus().name(), plan.getFlightVersionSnapshot(), currentVersionNo,
                assignments, versionViews);
    }
}

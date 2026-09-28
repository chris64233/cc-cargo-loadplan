package com.chris64233.cargoloadplan.service;

import com.chris64233.cargoloadplan.domain.CargoHold;
import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.domain.Flight;
import com.chris64233.cargoloadplan.domain.FlightStatus;
import com.chris64233.cargoloadplan.domain.LoadPlan;
import com.chris64233.cargoloadplan.domain.PlanAssignment;
import com.chris64233.cargoloadplan.domain.PlanStatus;
import com.chris64233.cargoloadplan.domain.PlanVersion;
import com.chris64233.cargoloadplan.domain.UnitStatus;
import com.chris64233.cargoloadplan.domain.VersionStatus;
import com.chris64233.cargoloadplan.dto.AdjustPlanRequest;
import com.chris64233.cargoloadplan.dto.AssignmentRequest;
import com.chris64233.cargoloadplan.dto.CreatePlanRequest;
import com.chris64233.cargoloadplan.dto.PlanResponse;
import com.chris64233.cargoloadplan.dto.PlanVersionResponse;
import com.chris64233.cargoloadplan.repository.CargoUnitRepository;
import com.chris64233.cargoloadplan.repository.FlightRepository;
import com.chris64233.cargoloadplan.repository.LoadPlanRepository;
import com.chris64233.cargoloadplan.repository.PlanVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配载方案核心服务，支持"准备版本 → 确认版本"以及航班关闭（起飞）前的临时卸货/替换货物。
 *
 * 并发与一致性约定：
 * - 所有变更操作先对航班行加悲观写锁，串行化同一航班上的确认/调整/卸载/关闭；
 * - 方案号、调整变更号均唯一，分别作为方案与单次调整的幂等键；
 * - 每个版本记录航班配置版本与每个货物的版本快照，确认时逐一比对；
 * - 确认在单个事务内完成全部校验并原子切换整份方案的货物/舱位占用
 *   （新货物锁定、在机货物改舱、卸下货物释放），任一校验不满足或快照失效即整体回滚，
 *   替换货物不可用时原已确认配载继续有效。
 */
@Service
public class LoadPlanService {

    private final FlightRepository flights;
    private final CargoUnitRepository units;
    private final LoadPlanRepository plans;
    private final PlanVersionRepository versions;
    private final ConstraintValidator validator;
    private final Clock clock;

    public LoadPlanService(FlightRepository flights, CargoUnitRepository units,
                           LoadPlanRepository plans, PlanVersionRepository versions,
                           ConstraintValidator validator, Clock clock) {
        this.flights = flights;
        this.units = units;
        this.plans = plans;
        this.versions = versions;
        this.validator = validator;
        this.clock = clock;
    }

    // ============================== 初版方案 ==============================

    /**
     * 准备配载方案。方案号幂等：已存在时直接返回既有方案。
     * 初版为版本 1 的 DRAFT，记录航班配置版本与货物版本快照，并对当前载重做完整约束预检。
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

        Set<String> seen = new LinkedHashSet<>();
        LoadPlan plan = new LoadPlan(req.planNo(), flight);
        PlanVersion v1 = new PlanVersion(plan, 1, null, flight.getVersion(), Instant.now(clock));
        Map<String, CargoUnit> target = new LinkedHashMap<>();
        for (AssignmentRequest a : req.assignments()) {
            if (!seen.add(a.unitNo())) {
                throw new LoadConstraintException(List.of("方案内货物重复: " + a.unitNo()));
            }
            CargoUnit unit = units.findByUnitNo(a.unitNo())
                    .orElseThrow(() -> new NotFoundException("货物单元不存在: " + a.unitNo()));
            if (unit.getStatus() != UnitStatus.AVAILABLE) {
                throw new ConflictException("货物 " + a.unitNo() + " 已属于其他活动配载方案");
            }
            v1.addAssignment(new PlanAssignment(v1, a.unitNo(), a.holdCode(), unit.getVersion()));
            target.put(a.unitNo(), unit);
        }
        validateWholePlane(flight, v1, lockedByOthers(flight, null), target);

        plan.addVersion(v1);
        plan.setCurrentVersion(v1);
        plans.save(plan);
        return toResponse(plan);
    }

    /**
     * 确认配载方案。幂等：已确认的版本重复确认直接返回。
     * 无待确认调整草案时确认初版（版本 1）；有待确认草案时确认该草案。
     */
    @Transactional
    public PlanVersionResponse confirm(String planNo) {
        return confirmVersion(planNo, null);
    }

    // ============================== 临时卸货 / 替换货物（版本化调整） ==============================

    /**
     * 准备一次调整：对已确认配载做临时卸货、重新分舱，并可同时加入空闲的替换货物。
     *
     * <p>结果是一个新的 DRAFT 版本，保存调整后整份装机方案的完整安排，并按整份方案重新核算
     * 重量、体积、重心与同舱限制。准备阶段不改变任何货物/舱位占用，原已确认配载继续有效。
     *
     * <p>变更号幂等：同一方案重复提交相同 changeNo 返回既有版本；若存在上一个尚未确认的草案，
     * 以新 changeNo 提交时旧草案作废（CANCELLED）并由新草案取代——同一方案至多一个待确认草案。
     */
    @Transactional
    public PlanVersionResponse prepareAdjust(String planNo, AdjustPlanRequest req) {
        LoadPlan plan = findPlan(planNo);
        if (plan.getStatus() != PlanStatus.CONFIRMED) {
            throw new ConflictException("只有已确认的方案才能临时卸货/调整: " + planNo);
        }
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("航班不存在"));
        requireOpen(flight);

        // 幂等：同一变更号返回既有版本
        Optional<PlanVersion> sameChange = versions.findByPlanIdAndChangeNo(plan.getId(), req.changeNo());
        if (sameChange.isPresent()) {
            return toVersionResponse(plan, sameChange.get());
        }

        List<AssignmentRequest> reassign = req.assignments() == null ? List.of() : req.assignments();
        List<String> unload = req.unloadUnitNos() == null ? List.of() : req.unloadUnitNos();

        // 当前在机（本方案锁定）货物
        Map<String, CargoUnit> loaded = units.findByPlanNoAndStatus(planNo, UnitStatus.LOCKED)
                .stream().collect(Collectors.toMap(CargoUnit::getUnitNo, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        // 目标安排：默认全部在机货物维持原舱位
        Map<String, String> newHold = new LinkedHashMap<>();
        Map<String, CargoUnit> targetUnits = new LinkedHashMap<>();
        for (CargoUnit u : loaded.values()) {
            newHold.put(u.getUnitNo(), u.getHold().getCode());
            targetUnits.put(u.getUnitNo(), u);
        }

        // 临时卸下
        for (String unitNo : unload) {
            if (!loaded.containsKey(unitNo)) {
                throw new ConflictException("货物 " + unitNo + " 不在方案 " + planNo + " 的当前装机清单中");
            }
        }
        unload.forEach(newHold::remove);
        unload.forEach(targetUnits::remove);

        // 重新分舱 / 加入替换货物
        Set<String> seen = new LinkedHashSet<>();
        for (AssignmentRequest a : reassign) {
            if (!seen.add(a.unitNo())) {
                throw new LoadConstraintException(List.of("调整请求中货物重复: " + a.unitNo()));
            }
            CargoUnit unit = loaded.get(a.unitNo());
            if (unit == null) {
                unit = units.findByUnitNo(a.unitNo())
                        .orElseThrow(() -> new NotFoundException("货物单元不存在: " + a.unitNo()));
                if (unit.getStatus() != UnitStatus.AVAILABLE) {
                    throw new ConflictException("替换货物 " + a.unitNo() + " 不可用（已被其他方案占用）");
                }
            }
            newHold.put(a.unitNo(), a.holdCode());
            targetUnits.put(a.unitNo(), unit);
        }

        // 旧的待确认草案作废，保证同一方案至多一个 DRAFT
        PlanVersion staleDraft = plan.findDraftVersion();
        if (staleDraft != null) {
            staleDraft.markCancelled();
        }

        PlanVersion draft = new PlanVersion(plan, plan.nextVersionNo(), req.changeNo(),
                flight.getVersion(), Instant.now(clock));
        for (Map.Entry<String, String> e : newHold.entrySet()) {
            CargoUnit unit = targetUnits.get(e.getKey());
            draft.addAssignment(new PlanAssignment(draft, e.getKey(), e.getValue(), unit.getVersion()));
        }

        // 按整份方案重新核算（含其他方案已占舱位）
        validateWholePlane(flight, draft, lockedByOthers(flight, plan), targetUnits);

        plan.addVersion(draft);
        return toVersionResponse(plan, draft);
    }

    /**
     * 确认一个版本（版本号为 null 时确认当前待生效草案，否则确认指定版本）。在同一事务内：
     * 校验航班未关闭、航班/货物版本快照未失效、替换货物仍可用，并重算整份方案全部约束；
     * 通过后原子完成加入货物锁定、在机货物改舱、卸下货物释放，旧的已确认版本置为 SUPERSEDED。
     */
    @Transactional
    public PlanVersionResponse confirmVersion(String planNo, Integer versionNo) {
        LoadPlan plan = findPlan(planNo);
        if (plan.getStatus() == PlanStatus.CANCELLED) {
            throw new ConflictException("方案已整组卸载（取消），不能再确认: " + planNo);
        }
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("航班不存在"));

        PlanVersion target;
        if (versionNo == null) {
            PlanVersion draft = plan.findDraftVersion();
            target = draft != null ? draft : plan.getCurrentVersion();
        } else {
            target = versions.findByPlanIdAndVersionNo(plan.getId(), versionNo)
                    .orElseThrow(() -> new NotFoundException("版本不存在: " + planNo + "#v" + versionNo));
        }

        if (target.getStatus() == VersionStatus.CONFIRMED) {
            return toVersionResponse(plan, target); // 幂等
        }
        if (target.getStatus() == VersionStatus.SUPERSEDED) {
            return toVersionResponse(plan, plan.getCurrentVersion()); // 已被取代：返回当前生效版本
        }
        if (target.getStatus() == VersionStatus.CANCELLED) {
            throw new ConflictException("版本已作废: " + planNo + "#v" + target.getVersionNo());
        }

        // DRAFT：必须航班关闭（起飞）前确认
        requireOpen(flight);
        if (flight.getVersion() != target.getFlightVersionSnapshot()) {
            throw new ConflictException("航班配置已变化，版本快照失效，请重新准备: "
                    + planNo + "#v" + target.getVersionNo());
        }

        // 取出新版本涉及的全部货物，先核对可用性（替换货物是否仍空闲），再核对版本快照
        Map<String, CargoUnit> targetUnits = new LinkedHashMap<>();
        for (PlanAssignment a : target.getAssignments()) {
            CargoUnit unit = units.findByUnitNo(a.getUnitNo())
                    .orElseThrow(() -> new NotFoundException("货物单元不存在: " + a.getUnitNo()));
            boolean belongsToThisPlan = unit.getStatus() == UnitStatus.LOCKED
                    && planNo.equals(unit.getPlanNo());
            if (!belongsToThisPlan && unit.getStatus() != UnitStatus.AVAILABLE) {
                // 替换货物在准备后被其他方案占用：放弃确认，原已确认配载继续有效
                throw new ConflictException("替换货物 " + a.getUnitNo() + " 已被其他方案占用");
            }
            if (unit.getVersion() != a.getUnitVersionSnapshot()) {
                throw new ConflictException("货物 " + a.getUnitNo()
                        + " 信息已变化，版本快照失效，请重新准备: " + planNo);
            }
            targetUnits.put(a.getUnitNo(), unit);
        }

        // 整份方案重新核算（含其他方案已占舱位）
        validateWholePlane(flight, target, lockedByOthers(flight, plan), targetUnits);

        // ---- 校验全部通过：在同一事务内切换全部货物与舱位占用 ----
        Map<String, CargoHold> holds = holdsByCode(flight);
        Map<String, CargoUnit> previouslyLoaded = units
                .findByPlanNoAndStatus(planNo, UnitStatus.LOCKED).stream()
                .collect(Collectors.toMap(CargoUnit::getUnitNo, Function.identity()));

        // 新加入（替换）货物与在机改舱：按新版本锁定
        for (PlanAssignment a : target.getAssignments()) {
            CargoUnit unit = targetUnits.get(a.getUnitNo());
            unit.lock(flight, holds.get(a.getHoldCode()), planNo);
        }
        // 新版本中已不存在的货物 = 本次卸下，释放一次（重复确认时此处已无锁定货物，自然幂等）
        for (Map.Entry<String, CargoUnit> e : previouslyLoaded.entrySet()) {
            if (!targetUnits.containsKey(e.getKey())) {
                e.getValue().release();
            }
        }

        // 版本切换：其他已确认版本置 SUPERSEDED，目标版本置 CONFIRMED
        for (PlanVersion v : plan.getVersions()) {
            if (!v.equals(target) && v.getStatus() == VersionStatus.CONFIRMED) {
                v.markSuperseded();
            }
        }
        target.markConfirmed();
        plan.setCurrentVersion(target);
        plan.setStatus(PlanStatus.CONFIRMED);
        return toVersionResponse(plan, target);
    }

    /** 整组卸载：释放方案锁定的全部货物，方案与当前版本置为取消。仅航班关闭前可用。幂等。 */
    @Transactional
    public PlanResponse unloadPlan(String planNo) {
        LoadPlan plan = findPlan(planNo);
        if (plan.getStatus() == PlanStatus.CANCELLED) {
            return toResponse(plan); // 幂等：重复卸载保持原结果
        }
        if (plan.getStatus() != PlanStatus.CONFIRMED) {
            throw new ConflictException("只有已确认的方案才能卸载: " + planNo);
        }
        Flight flight = flights.findByIdForUpdate(plan.getFlight().getId())
                .orElseThrow(() -> new NotFoundException("航班不存在"));
        requireOpen(flight);

        // 每个被锁货物只释放一次；重复请求时已无锁定货物，自然幂等
        for (CargoUnit unit : units.findByPlanNoAndStatus(planNo, UnitStatus.LOCKED)) {
            unit.release();
        }
        // 取消当前生效版本与任何待确认的调整草案（明细保留）
        for (PlanVersion v : plan.getVersions()) {
            if (v.getStatus() == VersionStatus.CONFIRMED || v.getStatus() == VersionStatus.DRAFT) {
                v.markCancelled();
            }
        }
        plan.setStatus(PlanStatus.CANCELLED);
        return toResponse(plan);
    }

    // ============================== 查询 ==============================

    @Transactional(readOnly = true)
    public PlanResponse getPlan(String planNo) {
        return toResponse(findPlan(planNo));
    }

    @Transactional(readOnly = true)
    public List<PlanVersionResponse> listVersions(String planNo) {
        LoadPlan plan = findPlan(planNo);
        return versions.findByPlanIdOrderByVersionNoAsc(plan.getId()).stream()
                .map(v -> toVersionResponse(plan, v)).toList();
    }

    @Transactional(readOnly = true)
    public PlanVersionResponse getVersion(String planNo, int versionNo) {
        LoadPlan plan = findPlan(planNo);
        PlanVersion v = versions.findByPlanIdAndVersionNo(plan.getId(), versionNo)
                .orElseThrow(() -> new NotFoundException("版本不存在: " + planNo + "#v" + versionNo));
        return toVersionResponse(plan, v);
    }

    // ============================== 内部辅助 ==============================

    private LoadPlan findPlan(String planNo) {
        return plans.findByPlanNo(planNo)
                .orElseThrow(() -> new NotFoundException("方案不存在: " + planNo));
    }

    private void requireOpen(Flight flight) {
        if (flight.getStatus() != FlightStatus.OPEN) {
            throw new ConflictException("航班已关闭（起飞），方案不可修改: " + flight.getFlightNo());
        }
    }

    /**
     * 整份方案校验：把"其他方案已锁定货物 + 本版本目标货物"作为前瞻整机载重，
     * 交给 {@link ConstraintValidator} 统一核算货舱重量/体积、允许类别、同舱互斥与整机重心。
     */
    private void validateWholePlane(Flight flight, PlanVersion version,
                                    List<ConstraintValidator.LoadItem> othersLocked,
                                    Map<String, CargoUnit> targetUnits) {
        List<ConstraintValidator.LoadItem> prospective = new ArrayList<>(othersLocked);
        for (PlanAssignment a : version.getAssignments()) {
            CargoUnit unit = targetUnits.get(a.getUnitNo());
            if (unit != null) {
                prospective.add(item(unit, a.getHoldCode()));
            }
        }
        List<String> violations = validator.validate(flight, prospective);
        if (!violations.isEmpty()) {
            throw new LoadConstraintException(violations);
        }
    }

    /** 该航班上、除给定方案外其他活动方案已锁定的货物（已占用的舱位）。 */
    private List<ConstraintValidator.LoadItem> lockedByOthers(Flight flight, LoadPlan self) {
        return units.findByFlightIdAndStatus(flight.getId(), UnitStatus.LOCKED).stream()
                .filter(u -> self == null || !self.getPlanNo().equals(u.getPlanNo()))
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
        PlanVersion current = plan.getCurrentVersion();
        List<AssignmentRequest> assignments = current == null ? List.of()
                : current.getAssignments().stream()
                .map(a -> new AssignmentRequest(a.getUnitNo(), a.getHoldCode())).toList();
        List<PlanResponse.VersionSummary> summaries = plan.getVersions().stream()
                .sorted(Comparator.comparingInt(PlanVersion::getVersionNo))
                .map(v -> new PlanResponse.VersionSummary(v.getVersionNo(), v.getChangeNo(),
                        v.getStatus().name()))
                .toList();
        return new PlanResponse(plan.getPlanNo(), plan.getFlight().getFlightNo(),
                plan.getStatus().name(), current == null ? 0 : current.getVersionNo(),
                assignments, summaries);
    }

    private PlanVersionResponse toVersionResponse(LoadPlan plan, PlanVersion v) {
        List<AssignmentRequest> assignments = v.getAssignments().stream()
                .map(a -> new AssignmentRequest(a.getUnitNo(), a.getHoldCode())).toList();
        // 与上一版本比较，得出卸下 / 新加入的货物
        Set<String> now = v.getAssignments().stream().map(PlanAssignment::getUnitNo)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> before = predecessorOf(plan, v)
                .map(p -> p.getAssignments().stream().map(PlanAssignment::getUnitNo)
                        .collect(Collectors.toCollection(LinkedHashSet::new)))
                .orElseGet(LinkedHashSet::new);
        List<String> unloaded = before.stream().filter(u -> !now.contains(u)).sorted().toList();
        List<String> added = now.stream().filter(u -> !before.contains(u)).sorted().toList();
        return new PlanVersionResponse(plan.getPlanNo(), v.getVersionNo(), v.getChangeNo(),
                v.getStatus().name(), v.getFlightVersionSnapshot(), v.getCreatedAt(),
                assignments, unloaded, added);
    }

    /** 某版本的"上一版本"：版本号更小的最近一个版本。 */
    private Optional<PlanVersion> predecessorOf(LoadPlan plan, PlanVersion v) {
        return plan.getVersions().stream()
                .filter(x -> x.getVersionNo() < v.getVersionNo())
                .max(Comparator.comparingInt(PlanVersion::getVersionNo));
    }
}

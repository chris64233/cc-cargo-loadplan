package com.chris64233.cargoloadplan.web;

import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.domain.LoadPlan;
import com.chris64233.cargoloadplan.domain.LoadPlanItem;
import com.chris64233.cargoloadplan.service.CargoUnitService;
import com.chris64233.cargoloadplan.service.LoadPlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.chris64233.cargoloadplan.web.ApiDtos.CargoUnitResponse;
import static com.chris64233.cargoloadplan.web.ApiDtos.OffloadEventResponse;
import static com.chris64233.cargoloadplan.web.ApiDtos.RegisterCargoRequest;
import static com.chris64233.cargoloadplan.web.ApiDtos.UpdateCargoRequest;
import static com.chris64233.cargoloadplan.web.ApiDtos.WhereaboutsResponse;

@RestController
@RequestMapping("/api/cargo-units")
public class CargoUnitController {

    private final CargoUnitService cargoUnitService;
    private final LoadPlanService loadPlanService;

    public CargoUnitController(CargoUnitService cargoUnitService, LoadPlanService loadPlanService) {
        this.cargoUnitService = cargoUnitService;
        this.loadPlanService = loadPlanService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CargoUnitResponse register(@Valid @RequestBody RegisterCargoRequest request) {
        return toResponse(cargoUnitService.register(request.unitNo(), request.weightKg(), request.volumeM3(),
                request.category(), request.incompatibleCategories()));
    }

    @PatchMapping("/{unitNo}")
    public CargoUnitResponse update(@PathVariable String unitNo,
                                    @Valid @RequestBody UpdateCargoRequest request) {
        return toResponse(cargoUnitService.update(unitNo, request.weightKg(), request.volumeM3()));
    }

    /** 货物去向查询：当前状态、所属方案/货舱、实际卸载事件。 */
    @GetMapping("/{unitNo}/whereabouts")
    public WhereaboutsResponse whereabouts(@PathVariable String unitNo) {
        CargoUnit unit = loadPlanService.cargoWhereabouts(unitNo);
        String planNo = null;
        String compartmentCode = null;
        LoadPlan activePlan = unit.getActivePlan();
        if (activePlan != null) {
            planNo = activePlan.getPlanNo();
            compartmentCode = activePlan.getItems().stream()
                    .filter(item -> item.getCargoUnit().getId().equals(unit.getId()))
                    .map(item -> item.getCompartment().getCode())
                    .findFirst()
                    .orElse(null);
        }
        List<OffloadEventResponse> events = loadPlanService.offloadEventsOf(unitNo).stream()
                .map(e -> new OffloadEventResponse(e.getCargoUnit().getUnitNo(), e.getPlanNo(), e.getReason(),
                        e.getRecordedAt()))
                .toList();
        return new WhereaboutsResponse(unit.getUnitNo(), unit.getStatus().name(), planNo, compartmentCode,
                events);
    }

    private static CargoUnitResponse toResponse(CargoUnit unit) {
        return new CargoUnitResponse(unit.getId(), unit.getUnitNo(), unit.getWeightKg(), unit.getVolumeM3(),
                unit.getCategory(), unit.getIncompatibleCategories(), unit.getStatus().name());
    }

    static ApiDtos.PlanResponse toPlanResponse(LoadPlan plan) {
        List<ApiDtos.PlanItemResponse> items = plan.getItems().stream()
                .map(item -> new ApiDtos.PlanItemResponse(item.getCargoUnit().getUnitNo(),
                        item.getCompartment().getCode()))
                .toList();
        return new ApiDtos.PlanResponse(plan.getPlanNo(), plan.getStatus().name(),
                plan.getFlight().getId(), items);
    }
}

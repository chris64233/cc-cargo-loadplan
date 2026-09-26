package com.chris64233.cargoloadplan.web;

import com.chris64233.cargoloadplan.service.LoadPlanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.chris64233.cargoloadplan.web.ApiDtos.AdjustPlanRequest;
import static com.chris64233.cargoloadplan.web.ApiDtos.OffloadUnitsRequest;
import static com.chris64233.cargoloadplan.web.ApiDtos.PlanResponse;
import static com.chris64233.cargoloadplan.web.ApiDtos.PreparePlanRequest;
import static com.chris64233.cargoloadplan.web.CargoUnitController.toPlanResponse;

@RestController
@RequestMapping("/api/load-plans")
public class LoadPlanController {

    private final LoadPlanService loadPlanService;

    public LoadPlanController(LoadPlanService loadPlanService) {
        this.loadPlanService = loadPlanService;
    }

    /** 准备配载方案（planNo 幂等）。 */
    @PostMapping
    public PlanResponse prepare(@Valid @RequestBody PreparePlanRequest request) {
        return toPlanResponse(loadPlanService.preparePlan(request.planNo(), request.flightId(),
                toSpecs(request.items())));
    }

    @GetMapping("/{planNo}")
    public PlanResponse getPlan(@PathVariable String planNo) {
        return toPlanResponse(loadPlanService.getPlan(planNo));
    }

    /** 确认配载：校验全部约束并原子锁定货物单元。 */
    @PostMapping("/{planNo}/confirm")
    public PlanResponse confirm(@PathVariable String planNo) {
        return toPlanResponse(loadPlanService.confirmPlan(planNo));
    }

    /** 整组调整：成功后才释放原舱位，失败则原配载不变。 */
    @PostMapping("/{planNo}/adjust")
    public PlanResponse adjust(@PathVariable String planNo, @Valid @RequestBody AdjustPlanRequest request) {
        return toPlanResponse(loadPlanService.adjustPlan(planNo, toSpecs(request.items())));
    }

    /** 航班关闭前从方案中卸载部分货物单元。 */
    @PostMapping("/{planNo}/offload")
    public PlanResponse offload(@PathVariable String planNo, @Valid @RequestBody OffloadUnitsRequest request) {
        return toPlanResponse(loadPlanService.offloadUnits(planNo, request.cargoUnitIds()));
    }

    private static List<LoadPlanService.PlacementSpec> toSpecs(List<ApiDtos.PlacementSpecRequest> items) {
        return items.stream()
                .map(item -> new LoadPlanService.PlacementSpec(item.cargoUnitId(), item.compartmentId()))
                .toList();
    }
}

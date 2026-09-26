package com.chris64233.cargoloadplan.web;

import com.chris64233.cargoloadplan.dto.AdjustPlanRequest;
import com.chris64233.cargoloadplan.dto.CreatePlanRequest;
import com.chris64233.cargoloadplan.dto.PlanResponse;
import com.chris64233.cargoloadplan.service.LoadPlanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LoadPlanController {

    private final LoadPlanService loadPlanService;

    public LoadPlanController(LoadPlanService loadPlanService) {
        this.loadPlanService = loadPlanService;
    }

    /** 准备方案。方案号幂等：重复提交返回既有方案。 */
    @PostMapping("/flights/{flightNo}/plans")
    public PlanResponse createPlan(@PathVariable String flightNo,
                                   @Valid @RequestBody CreatePlanRequest req) {
        return loadPlanService.createPlan(flightNo, req);
    }

    @GetMapping("/plans/{planNo}")
    public PlanResponse getPlan(@PathVariable String planNo) {
        return loadPlanService.getPlan(planNo);
    }

    /** 确认方案。幂等；原子锁定全部货物单元。 */
    @PostMapping("/plans/{planNo}/confirm")
    public PlanResponse confirm(@PathVariable String planNo) {
        return loadPlanService.confirm(planNo);
    }

    /** 整组调整：重新分舱/加入/卸载，成功才生效，失败原配载不变。 */
    @PostMapping("/plans/{planNo}/adjust")
    public PlanResponse adjust(@PathVariable String planNo,
                               @Valid @RequestBody AdjustPlanRequest req) {
        return loadPlanService.adjust(planNo, req);
    }

    /** 整组卸载：释放方案全部货物，方案取消。仅航班关闭前可用。 */
    @PostMapping("/plans/{planNo}/unload")
    public PlanResponse unload(@PathVariable String planNo) {
        return loadPlanService.unloadPlan(planNo);
    }
}

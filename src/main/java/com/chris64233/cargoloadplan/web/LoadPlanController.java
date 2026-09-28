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

    /**
     * 准备临时卸货/替换调整：对调整后的整份方案重新校验并生成新版本（PROPOSED），
     * 不改变货物与舱位占用。校验失败返回 422，原配载不变。
     */
    @PostMapping("/plans/{planNo}/adjust")
    public PlanResponse adjust(@PathVariable String planNo,
                               @Valid @RequestBody AdjustPlanRequest req) {
        return loadPlanService.adjust(planNo, req);
    }

    /**
     * 确认调整：同一事务内切换全部货物与舱位占用，新版本生效、原版本保留为历史。
     * 替换货物不可用或快照失效时整体回滚（409），原配载继续有效。
     */
    @PostMapping("/plans/{planNo}/adjust/confirm")
    public PlanResponse confirmAdjustment(@PathVariable String planNo) {
        return loadPlanService.confirmAdjustment(planNo);
    }

    /** 整组卸载：释放方案全部货物，方案取消。仅航班关闭前可用；重复请求幂等。 */
    @PostMapping("/plans/{planNo}/unload")
    public PlanResponse unload(@PathVariable String planNo) {
        return loadPlanService.unloadPlan(planNo);
    }
}

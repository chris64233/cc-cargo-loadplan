package com.chris64233.cargoloadplan.web;

import com.chris64233.cargoloadplan.dto.AdjustPlanRequest;
import com.chris64233.cargoloadplan.dto.CreatePlanRequest;
import com.chris64233.cargoloadplan.dto.PlanResponse;
import com.chris64233.cargoloadplan.dto.PlanVersionResponse;
import com.chris64233.cargoloadplan.service.LoadPlanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class LoadPlanController {

    private final LoadPlanService loadPlanService;

    public LoadPlanController(LoadPlanService loadPlanService) {
        this.loadPlanService = loadPlanService;
    }

    /** 准备方案（初版）。方案号幂等：重复提交返回既有方案。 */
    @PostMapping("/flights/{flightNo}/plans")
    public PlanResponse createPlan(@PathVariable String flightNo,
                                   @Valid @RequestBody CreatePlanRequest req) {
        return loadPlanService.createPlan(flightNo, req);
    }

    @GetMapping("/plans/{planNo}")
    public PlanResponse getPlan(@PathVariable String planNo) {
        return loadPlanService.getPlan(planNo);
    }

    /** 方案的全部配载版本（含历史/草案），按版本号升序。 */
    @GetMapping("/plans/{planNo}/versions")
    public List<PlanVersionResponse> listVersions(@PathVariable String planNo) {
        return loadPlanService.listVersions(planNo);
    }

    /** 单个配载版本明细（整份装机安排、快照、相对上一版本卸下/加入的货物）。 */
    @GetMapping("/plans/{planNo}/versions/{versionNo}")
    public PlanVersionResponse getVersion(@PathVariable String planNo,
                                          @PathVariable int versionNo) {
        return loadPlanService.getVersion(planNo, versionNo);
    }

    /** 确认方案/待生效调整版本。幂等；在同一事务内原子切换全部货物与舱位占用。 */
    @PostMapping("/plans/{planNo}/confirm")
    public PlanVersionResponse confirm(@PathVariable String planNo) {
        return loadPlanService.confirm(planNo);
    }

    /** 确认指定版本（用于精确确认某次临时卸货/替换货物调整）。 */
    @PostMapping("/plans/{planNo}/versions/{versionNo}/confirm")
    public PlanVersionResponse confirmVersion(@PathVariable String planNo,
                                              @PathVariable int versionNo) {
        return loadPlanService.confirmVersion(planNo, versionNo);
    }

    /**
     * 航班关闭（起飞）前临时卸货/替换货物：生成一个新的 DRAFT 版本并按整份方案重新核算，
     * 不改变实际占用；变更号 changeNo 幂等。占用切换发生在版本确认时。
     */
    @PostMapping("/plans/{planNo}/adjust")
    public PlanVersionResponse prepareAdjust(@PathVariable String planNo,
                                             @Valid @RequestBody AdjustPlanRequest req) {
        return loadPlanService.prepareAdjust(planNo, req);
    }

    /** 整组卸载：释放方案全部货物，方案与当前版本取消。仅航班关闭前可用，幂等。 */
    @PostMapping("/plans/{planNo}/unload")
    public PlanResponse unload(@PathVariable String planNo) {
        return loadPlanService.unloadPlan(planNo);
    }
}

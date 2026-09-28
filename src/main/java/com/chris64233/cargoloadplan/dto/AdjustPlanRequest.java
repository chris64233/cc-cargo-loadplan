package com.chris64233.cargoloadplan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 航班关闭前的临时卸货 / 替换货物调整请求。
 *
 * <ul>
 *   <li>{@code changeNo}：本次变更号，在同一方案内唯一，作为调整的幂等键；
 *       重复提交同一变更号返回已生成的版本，不会重复卸货或重复占用。</li>
 *   <li>{@code assignments}：需要重新分舱的在机货物，或新加入的替换（空闲）货物及其目标舱位；
 *       未出现的在机货物维持原舱位。</li>
 *   <li>{@code unloadUnitNos}：本次临时卸下的在机货物。</li>
 * </ul>
 *
 * 调整只生成一个 DRAFT 新版本并对整份方案重新核算，真正的舱位/货物切换发生在版本确认时；
 * 确认失败（如替换货物已被占用、快照失效、航班已关闭）则原已确认配载继续有效。
 */
public record AdjustPlanRequest(
        @NotBlank String changeNo,
        List<@Valid AssignmentRequest> assignments,
        List<String> unloadUnitNos) {
}

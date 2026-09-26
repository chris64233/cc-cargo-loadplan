package com.chris64233.cargoloadplan.dto;

import jakarta.validation.Valid;

import java.util.List;

/**
 * 整组调整请求。assignments 为本方案货物的新舱位安排（也可加入空闲货物），
 * unloadUnitNos 为从方案中卸载的货物。整体校验通过后一次性生效，失败则原配载不变。
 */
public record AdjustPlanRequest(
        List<@Valid AssignmentRequest> assignments,
        List<String> unloadUnitNos) {
}

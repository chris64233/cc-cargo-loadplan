package com.chris64233.cargoloadplan.dto;

import java.util.List;

/**
 * 配载方案响应。assignments 为当前生效版本（无生效版本时为最近一次准备/调整内容）的
 * 货物安排；currentVersionNo 为当前生效版本号（未确认时为 0）；
 * versions 保留方案的全部版本明细（含已被取代的历史版本）。
 */
public record PlanResponse(
        String planNo,
        String flightNo,
        String status,
        long flightVersionSnapshot,
        int currentVersionNo,
        List<AssignmentRequest> assignments,
        List<PlanVersionResponse> versions) {
}

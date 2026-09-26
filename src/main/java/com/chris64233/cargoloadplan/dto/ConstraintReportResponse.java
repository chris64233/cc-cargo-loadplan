package com.chris64233.cargoloadplan.dto;

import java.util.List;

/** 约束计算结果：当前已确认载重下的整机重心与全部约束校验明细。 */
public record ConstraintReportResponse(
        String flightNo,
        double centerOfGravity,
        double minCg,
        double maxCg,
        boolean withinEnvelope,
        List<String> violations) {
}

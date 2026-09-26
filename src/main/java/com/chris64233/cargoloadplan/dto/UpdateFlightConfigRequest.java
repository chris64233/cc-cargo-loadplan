package com.chris64233.cargoloadplan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** 航班配置变更：重心区间与货舱限制。变更会使准备中的方案快照失效。 */
public record UpdateFlightConfigRequest(
        @Positive double emptyWeight,
        double emptyArm,
        double minCg,
        double maxCg,
        @NotEmpty List<@Valid HoldRequest> holds) {
}

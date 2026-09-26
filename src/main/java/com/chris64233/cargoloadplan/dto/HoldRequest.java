package com.chris64233.cargoloadplan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.Set;

/** 货舱定义。allowedCategories 为空表示不限制货物类别。 */
public record HoldRequest(
        @NotBlank String code,
        @Positive double maxWeight,
        @Positive double maxVolume,
        double arm,
        Set<String> allowedCategories) {
}

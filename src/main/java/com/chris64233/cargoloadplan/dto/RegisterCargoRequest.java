package com.chris64233.cargoloadplan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.Set;

/** 货物单元登记。incompatibleCategories 为不可同舱的货物类别。 */
public record RegisterCargoRequest(
        @NotBlank String unitNo,
        @Positive double weight,
        @Positive double volume,
        @NotBlank String category,
        Set<String> incompatibleCategories) {
}

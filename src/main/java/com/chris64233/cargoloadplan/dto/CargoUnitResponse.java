package com.chris64233.cargoloadplan.dto;

import java.util.Set;

public record CargoUnitResponse(
        String unitNo,
        double weight,
        double volume,
        String category,
        Set<String> incompatibleCategories,
        String status,
        String flightNo,
        String holdCode,
        String planNo,
        long version) {
}

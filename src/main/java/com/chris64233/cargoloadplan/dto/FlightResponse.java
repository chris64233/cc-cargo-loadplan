package com.chris64233.cargoloadplan.dto;

import java.util.List;
import java.util.Set;

public record FlightResponse(
        String flightNo,
        String status,
        double emptyWeight,
        double emptyArm,
        double minCg,
        double maxCg,
        long version,
        List<HoldView> holds) {

    public record HoldView(
            String code,
            double maxWeight,
            double maxVolume,
            double arm,
            Set<String> allowedCategories) {
    }
}

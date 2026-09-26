package com.chris64233.cargoloadplan.dto;

import java.util.List;

/** 舱位用量：已确认装载占用与剩余量。 */
public record HoldUsageResponse(
        String holdCode,
        double maxWeight,
        double maxVolume,
        double usedWeight,
        double usedVolume,
        double remainingWeight,
        double remainingVolume,
        List<String> loadedUnits) {
}

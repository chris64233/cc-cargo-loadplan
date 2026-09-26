package com.chris64233.cargoloadplan.dto;

import java.time.Instant;

public record UnloadEventResponse(
        Long id,
        String flightNo,
        String unitNo,
        String planNo,
        String holdCode,
        double actualWeight,
        Instant recordedAt) {
}

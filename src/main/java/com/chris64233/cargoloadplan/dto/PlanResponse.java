package com.chris64233.cargoloadplan.dto;

import java.util.List;

public record PlanResponse(
        String planNo,
        String flightNo,
        String status,
        long flightVersionSnapshot,
        List<AssignmentRequest> assignments) {
}

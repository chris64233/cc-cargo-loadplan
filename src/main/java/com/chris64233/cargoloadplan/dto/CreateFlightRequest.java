package com.chris64233.cargoloadplan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CreateFlightRequest(
        @NotBlank String flightNo,
        @Positive double emptyWeight,
        double emptyArm,
        double minCg,
        double maxCg,
        @NotEmpty List<@Valid HoldRequest> holds) {
}

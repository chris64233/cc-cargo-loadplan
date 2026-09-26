package com.chris64233.cargoloadplan.dto;

import jakarta.validation.constraints.Positive;

public record UpdateWeightRequest(@Positive double weight) {
}

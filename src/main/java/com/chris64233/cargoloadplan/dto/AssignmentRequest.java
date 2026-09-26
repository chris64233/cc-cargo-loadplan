package com.chris64233.cargoloadplan.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignmentRequest(@NotBlank String unitNo, @NotBlank String holdCode) {
}

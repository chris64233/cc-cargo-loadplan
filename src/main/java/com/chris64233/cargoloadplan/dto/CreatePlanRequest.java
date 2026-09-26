package com.chris64233.cargoloadplan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 准备配载方案。planNo 为幂等键：重复提交返回既有方案。 */
public record CreatePlanRequest(
        @NotBlank String planNo,
        @NotEmpty List<@Valid AssignmentRequest> assignments) {
}

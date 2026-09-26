package com.chris64233.cargoloadplan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** 航班关闭后记录的实际卸载事件。 */
public record RecordUnloadRequest(
        @NotBlank String unitNo,
        @Positive double actualWeight) {
}

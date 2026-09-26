package com.chris64233.cargoloadplan.dto;

import java.util.List;

public record ErrorResponse(String message, List<String> violations) {
}

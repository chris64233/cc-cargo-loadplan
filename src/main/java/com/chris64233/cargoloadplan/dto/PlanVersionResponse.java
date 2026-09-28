package com.chris64233.cargoloadplan.dto;

import java.time.Instant;
import java.util.List;

/**
 * 配载版本明细。记录版本序号、状态（PROPOSED/ACTIVE/SUPERSEDED）、
 * 航班配置版本快照、生成/生效时间以及该版本下完整的货物→货舱安排。
 */
public record PlanVersionResponse(
        int versionNo,
        String status,
        long flightVersionSnapshot,
        Instant createdAt,
        Instant activatedAt,
        List<AssignmentRequest> items) {
}

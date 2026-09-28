package com.chris64233.cargoloadplan.dto;

import java.time.Instant;
import java.util.List;

/**
 * 配载版本视图。assignments 为该版本整份装机方案的完整目标安排；
 * unloadedUnitNos / addedUnitNos 为相对上一版本卸下 / 新加入的货物，便于核对临时卸货与替换。
 */
public record PlanVersionResponse(
        String planNo,
        int versionNo,
        String changeNo,
        String status,
        long flightVersionSnapshot,
        Instant createdAt,
        List<AssignmentRequest> assignments,
        List<String> unloadedUnitNos,
        List<String> addedUnitNos) {
}

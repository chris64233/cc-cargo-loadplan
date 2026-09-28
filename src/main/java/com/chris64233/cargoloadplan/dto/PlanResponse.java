package com.chris64233.cargoloadplan.dto;

import java.util.List;

/**
 * 配载方案视图。assignments 为当前生效版本的整份装机安排；
 * versions 列出全部版本（含已取代/草案），明细可经版本接口查询。
 */
public record PlanResponse(
        String planNo,
        String flightNo,
        String status,
        int currentVersionNo,
        List<AssignmentRequest> assignments,
        List<VersionSummary> versions) {

    /** 版本概要：明细（完整装载安排、快照、增减货物）见版本详情接口。 */
    public record VersionSummary(int versionNo, String changeNo, String status) {
    }
}

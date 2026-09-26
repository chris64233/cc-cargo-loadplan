package com.chris64233.cargoloadplan.dto;

import java.util.List;

/** 货物去向查询结果。 */
public record WhereaboutsResponse(
        String unitNo,
        String status,
        String flightNo,
        String holdCode,
        String planNo,
        List<UnloadEventResponse> unloadEvents) {
}

package com.chris64233.cargoloadplan.dto;

import jakarta.validation.Valid;

import java.util.List;

/**
 * 起飞前临时卸货/替换调整请求。
 * assignments 为调整后的货物安排：原方案货物可重新分舱，也可加入空闲的替换货物；
 * unloadUnitNos 为从方案中临时卸下的原方案货物。
 *
 * 调整分两步：POST /adjust 只生成待确认的新版本（PROPOSED）并对整份调整后方案做完整
 * 校验，不改变货物与舱位占用；POST /adjust/confirm 才在同一事务内切换全部货物与舱位。
 */
public record AdjustPlanRequest(
        List<@Valid AssignmentRequest> assignments,
        List<String> unloadUnitNos) {
}

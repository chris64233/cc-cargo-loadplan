package com.chris64233.cargoloadplan.domain;

/** 配载方案状态：DRAFT 准备中，CONFIRMED 已确认（活动方案），CANCELLED 已取消。 */
public enum PlanStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED
}

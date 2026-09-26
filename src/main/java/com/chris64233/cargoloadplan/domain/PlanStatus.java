package com.chris64233.cargoloadplan.domain;

/** 配载方案状态：草稿可确认，确认后占用舱位，取消后不再有效。 */
public enum PlanStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED
}

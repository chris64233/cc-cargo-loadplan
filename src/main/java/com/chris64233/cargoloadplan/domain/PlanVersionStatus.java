package com.chris64233.cargoloadplan.domain;

/**
 * 配载版本状态：
 * PROPOSED 调整后生成的候选版本，尚未确认（货物与舱位占用仍停留在 ACTIVE 版本）；
 * ACTIVE 当前生效版本（货物按本版本锁定在对应货舱）；
 * SUPERSEDED 已被后续版本取代（或方案整组卸载），明细永久保留供追溯。
 */
public enum PlanVersionStatus {
    PROPOSED,
    ACTIVE,
    SUPERSEDED
}

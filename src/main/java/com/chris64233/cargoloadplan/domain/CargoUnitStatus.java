package com.chris64233.cargoloadplan.domain;

/** 货物单元状态。 */
public enum CargoUnitStatus {
    /** 可配载 */
    AVAILABLE,
    /** 已属于某个活动配载方案（草稿或已确认） */
    ALLOCATED,
    /** 已实际卸载 */
    OFFLOADED
}

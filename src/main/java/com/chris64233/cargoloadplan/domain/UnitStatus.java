package com.chris64233.cargoloadplan.domain;

/** 货物单元状态：AVAILABLE 空闲，LOCKED 被活动方案锁定，UNLOADED 已实际卸载。 */
public enum UnitStatus {
    AVAILABLE,
    LOCKED,
    UNLOADED
}

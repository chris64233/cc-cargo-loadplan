package com.chris64233.cargoloadplan.domain;

/** 航班状态：OPEN 可配载/调整，CLOSED 关闭后只能记录实际卸载事件。 */
public enum FlightStatus {
    OPEN,
    CLOSED
}

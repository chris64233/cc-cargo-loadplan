package com.chris64233.cargoloadplan.domain;

/**
 * 配载版本状态：
 * DRAFT 准备中（临时卸货/替换货物的调整草案，尚未生效）；
 * CONFIRMED 已确认生效（当前装机方案）；
 * SUPERSEDED 已被更新的已确认版本取代（历史版本，明细保留）；
 * CANCELLED 调整草案被取消/被新草案取代，或方案整组卸载（明细保留）。
 */
public enum VersionStatus {
    DRAFT,
    CONFIRMED,
    SUPERSEDED,
    CANCELLED
}

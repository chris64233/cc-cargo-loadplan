package com.chris64233.cargoloadplan.service;

/** 业务规则冲突（舱位超限、互斥、重心越界、方案过期、航班已关闭等）。 */
public class LoadPlanException extends RuntimeException {

    private final String code;

    public LoadPlanException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

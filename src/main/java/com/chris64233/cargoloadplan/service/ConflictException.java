package com.chris64233.cargoloadplan.service;

/** 状态冲突：如方案快照失效、货物被占用、航班已关闭等。 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}

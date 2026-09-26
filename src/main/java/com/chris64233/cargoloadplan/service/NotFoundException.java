package com.chris64233.cargoloadplan.service;

/** 资源不存在。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}

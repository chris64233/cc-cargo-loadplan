package com.chris64233.cargoloadplan.service;

import java.util.List;

/** 配载约束不满足：载重/体积/类别/互斥/重心等，携带全部违规明细。 */
public class LoadConstraintException extends RuntimeException {

    private final List<String> violations;

    public LoadConstraintException(List<String> violations) {
        super("配载约束不满足: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}

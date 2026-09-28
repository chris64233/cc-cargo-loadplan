package com.chris64233.cargoloadplan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/**
 * 配载版本明细中的一条货物安排：货物单元 → 货舱，
 * 并记录该版本生成时货物单元的乐观锁版本快照（重量/体积等信息以此为准）。
 */
@Entity
public class PlanVersionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_version_id")
    private PlanVersion planVersion;

    @Column(nullable = false)
    private String unitNo;

    @Column(nullable = false)
    private String holdCode;

    /** 生成本版本时货物单元的版本快照 */
    @Column(nullable = false)
    private long unitVersionSnapshot;

    protected PlanVersionItem() {
    }

    public PlanVersionItem(PlanVersion planVersion, String unitNo, String holdCode,
                           long unitVersionSnapshot) {
        this.planVersion = planVersion;
        this.unitNo = unitNo;
        this.holdCode = holdCode;
        this.unitVersionSnapshot = unitVersionSnapshot;
    }

    public Long getId() {
        return id;
    }

    public PlanVersion getPlanVersion() {
        return planVersion;
    }

    void setPlanVersion(PlanVersion planVersion) {
        this.planVersion = planVersion;
    }

    public String getUnitNo() {
        return unitNo;
    }

    public String getHoldCode() {
        return holdCode;
    }

    public long getUnitVersionSnapshot() {
        return unitVersionSnapshot;
    }
}

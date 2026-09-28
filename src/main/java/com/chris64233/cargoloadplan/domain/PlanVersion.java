package com.chris64233.cargoloadplan.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 配载方案的一个版本明细。方案首次确认形成版本 1；之后每次起飞前临时卸货/替换/重新分舱
 * 的调整都会生成新版本（PROPOSED），确认切换成功后新版本成为 ACTIVE，原版本置为
 * SUPERSEDED。被取代的版本明细永久保留，用于还原任意时点的装机方案与审计追溯。
 *
 * 版本内记录航班配置版本快照与每条货物安排的货物版本快照；确认时逐一比对，
 * 保证"航班起飞、货物被其他方案占用、调整确认"并发时只可能有一个结果。
 */
@Entity
public class PlanVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private LoadPlan plan;

    /** 方案内版本序号，从 1 开始；同一方案内唯一 */
    @Column(nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanVersionStatus status = PlanVersionStatus.PROPOSED;

    /** 生成本版本时的航班配置版本快照 */
    @Column(nullable = false)
    private long flightVersionSnapshot;

    @Column(nullable = false)
    private Instant createdAt;

    /** 成为 ACTIVE 的时间；PROPOSED 期间为空 */
    private Instant activatedAt;

    @OneToMany(mappedBy = "planVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PlanVersionItem> items = new ArrayList<>();

    protected PlanVersion() {
    }

    public PlanVersion(LoadPlan plan, int versionNo, long flightVersionSnapshot, Instant createdAt) {
        this.plan = plan;
        this.versionNo = versionNo;
        this.flightVersionSnapshot = flightVersionSnapshot;
        this.createdAt = createdAt;
    }

    public void addItem(PlanVersionItem item) {
        item.setPlanVersion(this);
        this.items.add(item);
    }

    /** 确认切换：本版本成为当前生效版本。 */
    public void activate(Instant now) {
        this.status = PlanVersionStatus.ACTIVE;
        this.activatedAt = now;
    }

    /** 被更新版本取代（或方案整组卸载），明细保留。 */
    public void supersede() {
        this.status = PlanVersionStatus.SUPERSEDED;
    }

    public Long getId() {
        return id;
    }

    public LoadPlan getPlan() {
        return plan;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public PlanVersionStatus getStatus() {
        return status;
    }

    public long getFlightVersionSnapshot() {
        return flightVersionSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public List<PlanVersionItem> getItems() {
        return items;
    }
}

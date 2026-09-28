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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 配载方案的一个版本，是某一时点整份装机方案的不可变明细（确认后不再修改）。
 *
 * <p>初版方案准备时生成版本 1（changeNo 为空）；航班关闭前对已确认配载做临时卸货/
 * 替换货物的调整时生成新的 DRAFT 版本，记录完整目标装载安排与航班配置、货物版本快照。
 * 新版本确认成功后成为 CONFIRMED，旧的当前版本变为 SUPERSEDED；确认失败则草案作废，
 * 旧的 CONFIRMED 版本继续有效。所有版本明细都保留，供追溯。
 *
 * <p>(plan, versionNo) 与 (plan, changeNo) 均唯一：versionNo 保证版本可追溯，
 * changeNo 作为调整请求的幂等键，重复提交同一变更号返回既有版本。
 */
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uk_plan_version_no", columnNames = {"plan_id", "version_no"}),
        @UniqueConstraint(name = "uk_plan_change_no", columnNames = {"plan_id", "change_no"})
})
public class PlanVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private LoadPlan plan;

    /** 版本号，从 1 开始，同一方案内单调递增 */
    @Column(name = "version_no", nullable = false)
    private int versionNo;

    /** 调整变更号（幂等键）；初版为 null */
    @Column(name = "change_no")
    private String changeNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VersionStatus status = VersionStatus.DRAFT;

    /** 准备该版本时的航班配置版本 */
    @Column(nullable = false)
    private long flightVersionSnapshot;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "planVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PlanAssignment> assignments = new ArrayList<>();

    protected PlanVersion() {
    }

    public PlanVersion(LoadPlan plan, int versionNo, String changeNo, long flightVersionSnapshot,
                       Instant createdAt) {
        this.plan = plan;
        this.versionNo = versionNo;
        this.changeNo = changeNo;
        this.flightVersionSnapshot = flightVersionSnapshot;
        this.createdAt = createdAt;
    }

    public void addAssignment(PlanAssignment assignment) {
        assignment.setPlanVersion(this);
        this.assignments.add(assignment);
    }

    public void markConfirmed() {
        this.status = VersionStatus.CONFIRMED;
    }

    public void markSuperseded() {
        this.status = VersionStatus.SUPERSEDED;
    }

    public void markCancelled() {
        this.status = VersionStatus.CANCELLED;
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

    public String getChangeNo() {
        return changeNo;
    }

    public VersionStatus getStatus() {
        return status;
    }

    public long getFlightVersionSnapshot() {
        return flightVersionSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<PlanAssignment> getAssignments() {
        return assignments;
    }
}

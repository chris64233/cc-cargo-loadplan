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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 配载方案。planNo 全局唯一，作为幂等键：重复创建/确认同一方案号返回既有结果。
 *
 * <p>方案由一组不可变的 {@link PlanVersion} 构成：初版准备时生成版本 1；航班关闭前的
 * 临时卸货/替换货物调整会生成新的版本，确认成功后 {@link #currentVersion} 指向新版本，
 * 旧版本保留为 SUPERSEDED。方案状态（DRAFT/CONFIRMED/CANCELLED）始终与当前版本对齐。
 */
@Entity
public class LoadPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String planNo;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id")
    private Flight flight;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanStatus status = PlanStatus.DRAFT;

    /** 全部版本（含历史/草案/已取消），明细保留供追溯。不做孤儿删除，避免误删历史。 */
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL)
    private List<PlanVersion> versions = new ArrayList<>();

    /** 当前生效版本：方案处于 DRAFT 时指向准备中的初版，CONFIRMED 时指向最新已确认版本，
     *  CANCELLED 时保留最后一个版本。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_version_id")
    private PlanVersion currentVersion;

    protected LoadPlan() {
    }

    public LoadPlan(String planNo, Flight flight) {
        this.planNo = planNo;
        this.flight = flight;
    }

    public void addVersion(PlanVersion version) {
        this.versions.add(version);
    }

    public Long getId() {
        return id;
    }

    public String getPlanNo() {
        return planNo;
    }

    public Flight getFlight() {
        return flight;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public void setStatus(PlanStatus status) {
        this.status = status;
    }

    public List<PlanVersion> getVersions() {
        return versions;
    }

    public PlanVersion getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(PlanVersion currentVersion) {
        this.currentVersion = currentVersion;
    }

    /** 下一个版本号 = 现有版本号最大值 + 1。 */
    public int nextVersionNo() {
        return versions.stream().map(PlanVersion::getVersionNo)
                .max(Comparator.naturalOrder()).orElse(0) + 1;
    }

    /** 待生效的调整草案（同一方案最多一个 DRAFT）。 */
    public PlanVersion findDraftVersion() {
        return versions.stream()
                .filter(v -> v.getStatus() == VersionStatus.DRAFT && v.getChangeNo() != null)
                .findFirst().orElse(null);
    }
}

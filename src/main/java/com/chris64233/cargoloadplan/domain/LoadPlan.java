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
import java.util.List;

/**
 * 配载方案。planNo 全局唯一，作为幂等键：重复创建/确认同一方案号返回既有结果。
 * 方案准备时记录航班配置版本与每个货物单元的版本快照，确认前任一发生变化则拒绝确认。
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

    /** 准备方案时的航班配置版本 */
    @Column(nullable = false)
    private long flightVersionSnapshot;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PlanAssignment> assignments = new ArrayList<>();

    /**
     * 历次配载版本明细（首次确认 = 版本 1，之后每次临时卸货/替换调整追加新版本）。
     * 被取代的版本同样保留，用于还原任意时点的装机方案。
     */
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PlanVersion> versions = new ArrayList<>();

    protected LoadPlan() {
    }

    public LoadPlan(String planNo, Flight flight, long flightVersionSnapshot) {
        this.planNo = planNo;
        this.flight = flight;
        this.flightVersionSnapshot = flightVersionSnapshot;
    }

    public void addAssignment(PlanAssignment assignment) {
        assignment.setPlan(this);
        this.assignments.add(assignment);
    }

    public void addVersion(PlanVersion version) {
        this.versions.add(version);
    }

    /** 下一个版本序号（首次确认为版本 1）。 */
    public int nextVersionNo() {
        return versions.size() + 1;
    }

    /** 当前生效（ACTIVE）版本；尚未确认或已整组卸载时为空。 */
    public java.util.Optional<PlanVersion> activeVersion() {
        return versions.stream()
                .filter(v -> v.getStatus() == PlanVersionStatus.ACTIVE)
                .findFirst();
    }

    /** 待确认（PROPOSED）的调整候选版本，同一时刻至多一个。 */
    public java.util.Optional<PlanVersion> proposedVersion() {
        return versions.stream()
                .filter(v -> v.getStatus() == PlanVersionStatus.PROPOSED)
                .findFirst();
    }

    public List<PlanVersion> getVersions() {
        return versions;
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

    public long getFlightVersionSnapshot() {
        return flightVersionSnapshot;
    }

    public void setFlightVersionSnapshot(long flightVersionSnapshot) {
        this.flightVersionSnapshot = flightVersionSnapshot;
    }

    public List<PlanAssignment> getAssignments() {
        return assignments;
    }
}

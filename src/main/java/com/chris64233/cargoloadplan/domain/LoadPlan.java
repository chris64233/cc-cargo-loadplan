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

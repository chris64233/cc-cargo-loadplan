package com.chris64233.cargoloadplan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.Instant;

/** 实际卸载事件。航班关闭后只允许记录此类事件，方案本身不可再修改。 */
@Entity
public class UnloadEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id")
    private Flight flight;

    @Column(nullable = false)
    private String unitNo;

    @Column(nullable = false)
    private String planNo;

    @Column(nullable = false)
    private String holdCode;

    /** 实际卸载重量 */
    @Column(nullable = false)
    private double actualWeight;

    @Column(nullable = false)
    private Instant recordedAt;

    protected UnloadEvent() {
    }

    public UnloadEvent(Flight flight, String unitNo, String planNo, String holdCode, double actualWeight, Instant recordedAt) {
        this.flight = flight;
        this.unitNo = unitNo;
        this.planNo = planNo;
        this.holdCode = holdCode;
        this.actualWeight = actualWeight;
        this.recordedAt = recordedAt;
    }

    public Long getId() {
        return id;
    }

    public Flight getFlight() {
        return flight;
    }

    public String getUnitNo() {
        return unitNo;
    }

    public String getPlanNo() {
        return planNo;
    }

    public String getHoldCode() {
        return holdCode;
    }

    public double getActualWeight() {
        return actualWeight;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}

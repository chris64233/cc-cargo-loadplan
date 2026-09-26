package com.chris64233.cargoloadplan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 实际卸载事件：航班关闭后只允许记录此类事件，方案本身不可再修改。
 */
@Entity
@Table(name = "offload_events")
public class OffloadEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id")
    private Flight flight;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "cargo_unit_id")
    private CargoUnit cargoUnit;

    /** 卸载时货物所属的方案号（仅作追溯，不修改方案）。 */
    @Column(nullable = false)
    private String planNo;

    private String reason;

    @Column(nullable = false)
    private Instant recordedAt = Instant.now();

    protected OffloadEvent() {
    }

    public OffloadEvent(Flight flight, CargoUnit cargoUnit, String planNo, String reason) {
        this.flight = flight;
        this.cargoUnit = cargoUnit;
        this.planNo = planNo;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public Flight getFlight() {
        return flight;
    }

    public CargoUnit getCargoUnit() {
        return cargoUnit;
    }

    public String getPlanNo() {
        return planNo;
    }

    public String getReason() {
        return reason;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}

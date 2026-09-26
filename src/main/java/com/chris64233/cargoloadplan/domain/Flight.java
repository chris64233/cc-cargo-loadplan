package com.chris64233.cargoloadplan.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.ArrayList;
import java.util.List;

/**
 * 航班：记录整机重心包线、基础重量/力矩以及各货舱。
 */
@Entity
@Table(name = "flights")
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String flightNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightStatus status = FlightStatus.OPEN;

    /** 空机重量（kg），重心计算基准。 */
    @Column(nullable = false)
    private double baseWeightKg;

    /** 空机力矩（kg·m）。 */
    @Column(nullable = false)
    private double baseMoment;

    /** 重心包线下限（m）。 */
    @Column(nullable = false)
    private double minCg;

    /** 重心包线上限（m）。 */
    @Column(nullable = false)
    private double maxCg;

    @Version
    private long version;

    @OneToMany(mappedBy = "flight", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Compartment> compartments = new ArrayList<>();

    protected Flight() {
    }

    public Flight(String flightNo, double baseWeightKg, double baseMoment, double minCg, double maxCg) {
        this.flightNo = flightNo;
        this.baseWeightKg = baseWeightKg;
        this.baseMoment = baseMoment;
        this.minCg = minCg;
        this.maxCg = maxCg;
    }

    public void addCompartment(Compartment compartment) {
        compartments.add(compartment);
        compartment.setFlight(this);
    }

    public Long getId() {
        return id;
    }

    public String getFlightNo() {
        return flightNo;
    }

    public FlightStatus getStatus() {
        return status;
    }

    public void setStatus(FlightStatus status) {
        this.status = status;
    }

    public double getBaseWeightKg() {
        return baseWeightKg;
    }

    public double getBaseMoment() {
        return baseMoment;
    }

    public double getMinCg() {
        return minCg;
    }

    public double getMaxCg() {
        return maxCg;
    }

    public long getVersion() {
        return version;
    }

    public List<Compartment> getCompartments() {
        return compartments;
    }
}

package com.chris64233.cargoloadplan.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.Set;

/**
 * 货舱：记录最大重量、最大体积、允许货物类别和站位（用于重心力矩计算）。
 */
@Entity
@Table(name = "compartments")
public class Compartment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id")
    private Flight flight;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private double maxWeightKg;

    @Column(nullable = false)
    private double maxVolumeM3;

    /** 站位力臂（m），货物力矩 = 重量 × 力臂。 */
    @Column(nullable = false)
    private double positionArm;

    @ElementCollection
    @CollectionTable(name = "compartment_allowed_categories", joinColumns = @JoinColumn(name = "compartment_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private Set<CargoCategory> allowedCategories = new HashSet<>();

    protected Compartment() {
    }

    public Compartment(String code, double maxWeightKg, double maxVolumeM3, double positionArm,
                       Set<CargoCategory> allowedCategories) {
        this.code = code;
        this.maxWeightKg = maxWeightKg;
        this.maxVolumeM3 = maxVolumeM3;
        this.positionArm = positionArm;
        this.allowedCategories = new HashSet<>(allowedCategories);
    }

    public Long getId() {
        return id;
    }

    public Flight getFlight() {
        return flight;
    }

    void setFlight(Flight flight) {
        this.flight = flight;
    }

    public String getCode() {
        return code;
    }

    public double getMaxWeightKg() {
        return maxWeightKg;
    }

    public void setMaxWeightKg(double maxWeightKg) {
        this.maxWeightKg = maxWeightKg;
    }

    public double getMaxVolumeM3() {
        return maxVolumeM3;
    }

    public void setMaxVolumeM3(double maxVolumeM3) {
        this.maxVolumeM3 = maxVolumeM3;
    }

    public double getPositionArm() {
        return positionArm;
    }

    public Set<CargoCategory> getAllowedCategories() {
        return allowedCategories;
    }
}

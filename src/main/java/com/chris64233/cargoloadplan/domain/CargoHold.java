package com.chris64233.cargoloadplan.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.util.HashSet;
import java.util.Set;

/**
 * 货舱。记录最大重量、最大体积、允许装载的货物类别（空集合表示不限制类别）
 * 以及舱位力臂（用于整机重心计算）。
 */
@Entity
public class CargoHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id")
    private Flight flight;

    /** 舱位代码，如 FWD / AFT / BULK */
    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private double maxWeight;

    @Column(nullable = false)
    private double maxVolume;

    /** 舱位力臂，重心 = Σ(重量 × 力臂) / Σ重量 */
    @Column(nullable = false)
    private double arm;

    /** 允许装载的货物类别；为空表示不限制 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cargo_hold_allowed_categories", joinColumns = @JoinColumn(name = "hold_id"))
    @Column(name = "category")
    private Set<String> allowedCategories = new HashSet<>();

    protected CargoHold() {
    }

    public CargoHold(String code, double maxWeight, double maxVolume, double arm, Set<String> allowedCategories) {
        this.code = code;
        this.maxWeight = maxWeight;
        this.maxVolume = maxVolume;
        this.arm = arm;
        if (allowedCategories != null) {
            this.allowedCategories = new HashSet<>(allowedCategories);
        }
    }

    public void updateLimits(double maxWeight, double maxVolume, double arm, Set<String> allowedCategories) {
        this.maxWeight = maxWeight;
        this.maxVolume = maxVolume;
        this.arm = arm;
        this.allowedCategories = allowedCategories == null ? new HashSet<>() : new HashSet<>(allowedCategories);
    }

    public boolean allows(String category) {
        return allowedCategories.isEmpty() || allowedCategories.contains(category);
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

    public double getMaxWeight() {
        return maxWeight;
    }

    public double getMaxVolume() {
        return maxVolume;
    }

    public double getArm() {
        return arm;
    }

    public Set<String> getAllowedCategories() {
        return allowedCategories;
    }
}

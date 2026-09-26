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
import jakarta.persistence.Version;

import java.util.HashSet;
import java.util.Set;

/**
 * 货物单元：记录重量、体积、类别与不可同舱规则。
 * 一个货物单元同一时间只能属于一个活动配载方案。
 */
@Entity
@Table(name = "cargo_units")
public class CargoUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String unitNo;

    @Column(nullable = false)
    private double weightKg;

    @Column(nullable = false)
    private double volumeM3;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CargoCategory category;

    /** 不可同舱规则：本单元不允许与这些类别的货物放在同一货舱。 */
    @ElementCollection
    @CollectionTable(name = "cargo_unit_incompatible_categories", joinColumns = @JoinColumn(name = "cargo_unit_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private Set<CargoCategory> incompatibleCategories = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CargoUnitStatus status = CargoUnitStatus.AVAILABLE;

    /** 当前所属的活动配载方案（草稿或已确认），为空表示未配载。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_plan_id")
    private LoadPlan activePlan;

    @Version
    private long version;

    protected CargoUnit() {
    }

    public CargoUnit(String unitNo, double weightKg, double volumeM3, CargoCategory category,
                     Set<CargoCategory> incompatibleCategories) {
        this.unitNo = unitNo;
        this.weightKg = weightKg;
        this.volumeM3 = volumeM3;
        this.category = category;
        this.incompatibleCategories = new HashSet<>(incompatibleCategories);
    }

    public void assignTo(LoadPlan plan) {
        this.activePlan = plan;
        this.status = CargoUnitStatus.ALLOCATED;
    }

    public void release() {
        this.activePlan = null;
        this.status = CargoUnitStatus.AVAILABLE;
    }

    public Long getId() {
        return id;
    }

    public String getUnitNo() {
        return unitNo;
    }

    public double getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(double weightKg) {
        this.weightKg = weightKg;
    }

    public double getVolumeM3() {
        return volumeM3;
    }

    public void setVolumeM3(double volumeM3) {
        this.volumeM3 = volumeM3;
    }

    public CargoCategory getCategory() {
        return category;
    }

    public Set<CargoCategory> getIncompatibleCategories() {
        return incompatibleCategories;
    }

    public CargoUnitStatus getStatus() {
        return status;
    }

    public void setStatus(CargoUnitStatus status) {
        this.status = status;
    }

    public LoadPlan getActivePlan() {
        return activePlan;
    }
}

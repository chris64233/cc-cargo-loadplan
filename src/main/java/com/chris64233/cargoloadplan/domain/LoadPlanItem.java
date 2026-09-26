package com.chris64233.cargoloadplan.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 配载方案明细：一个货物单元放入一个货舱。
 */
@Entity
@Table(name = "load_plan_items")
public class LoadPlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private LoadPlan plan;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "cargo_unit_id")
    private CargoUnit cargoUnit;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "compartment_id")
    private Compartment compartment;

    protected LoadPlanItem() {
    }

    public LoadPlanItem(LoadPlan plan, CargoUnit cargoUnit, Compartment compartment) {
        this.plan = plan;
        this.cargoUnit = cargoUnit;
        this.compartment = compartment;
    }

    public Long getId() {
        return id;
    }

    public LoadPlan getPlan() {
        return plan;
    }

    void setPlan(LoadPlan plan) {
        this.plan = plan;
    }

    public CargoUnit getCargoUnit() {
        return cargoUnit;
    }

    public Compartment getCompartment() {
        return compartment;
    }
}

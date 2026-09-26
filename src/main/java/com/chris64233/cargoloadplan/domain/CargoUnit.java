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
import jakarta.persistence.Version;

import java.util.HashSet;
import java.util.Set;

/**
 * 货物单元。一个单元最多属于一个活动（已确认）配载方案。
 * version 为乐观锁版本：准备方案时记录快照，确认前若重量等信息被修改则版本不一致，
 * 旧方案不能确认；并发确认/改重时由乐观锁保证最多一个事务成功。
 */
@Entity
public class CargoUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String unitNo;

    @Column(nullable = false)
    private double weight;

    @Column(nullable = false)
    private double volume;

    @Column(nullable = false)
    private String category;

    /** 不可同舱规则：本单元不能与这些类别的货物装在同一货舱（双向判定） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cargo_unit_incompatible_categories", joinColumns = @JoinColumn(name = "unit_id"))
    @Column(name = "category")
    private Set<String> incompatibleCategories = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UnitStatus status = UnitStatus.AVAILABLE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id")
    private Flight flight;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private CargoHold hold;

    /** 锁定本单元的活动方案号 */
    private String planNo;

    @Version
    private long version;

    protected CargoUnit() {
    }

    public CargoUnit(String unitNo, double weight, double volume, String category, Set<String> incompatibleCategories) {
        this.unitNo = unitNo;
        this.weight = weight;
        this.volume = volume;
        this.category = category;
        if (incompatibleCategories != null) {
            this.incompatibleCategories = new HashSet<>(incompatibleCategories);
        }
    }

    /** 原子锁定到指定航班/货舱/方案。 */
    public void lock(Flight flight, CargoHold hold, String planNo) {
        this.flight = flight;
        this.hold = hold;
        this.planNo = planNo;
        this.status = UnitStatus.LOCKED;
    }

    /** 释放舱位，回到空闲状态。 */
    public void release() {
        this.flight = null;
        this.hold = null;
        this.planNo = null;
        this.status = UnitStatus.AVAILABLE;
    }

    /** 记录实际卸载：保留航班/舱位/方案信息供去向查询。 */
    public void markUnloaded() {
        this.status = UnitStatus.UNLOADED;
    }

    public void changeWeight(double weight) {
        this.weight = weight;
    }

    public Long getId() {
        return id;
    }

    public String getUnitNo() {
        return unitNo;
    }

    public double getWeight() {
        return weight;
    }

    public double getVolume() {
        return volume;
    }

    public String getCategory() {
        return category;
    }

    public Set<String> getIncompatibleCategories() {
        return incompatibleCategories;
    }

    public UnitStatus getStatus() {
        return status;
    }

    public Flight getFlight() {
        return flight;
    }

    public CargoHold getHold() {
        return hold;
    }

    public String getPlanNo() {
        return planNo;
    }

    public long getVersion() {
        return version;
    }
}

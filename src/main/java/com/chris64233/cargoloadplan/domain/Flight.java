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

import java.util.ArrayList;
import java.util.List;

/**
 * 航班。记录空机重量/力臂与整机重心允许区间。
 * version 为配置版本号：任何航班配置（重心区间、货舱限制）变更都会使其递增，
 * 从而使准备中的配载方案快照失效。所有修改航班的操作都先对航班行加悲观写锁，
 * 因此版本号可以安全地手动递增。
 */
@Entity
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String flightNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightStatus status = FlightStatus.OPEN;

    /** 空机重量 */
    @Column(nullable = false)
    private double emptyWeight;

    /** 空机重心力臂 */
    @Column(nullable = false)
    private double emptyArm;

    /** 整机重心允许区间下限 */
    @Column(nullable = false)
    private double minCg;

    /** 整机重心允许区间上限 */
    @Column(nullable = false)
    private double maxCg;

    /** 配置版本号，配置变更时递增，用于方案快照失效检测 */
    @Column(nullable = false)
    private long version = 0;

    @OneToMany(mappedBy = "flight", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CargoHold> holds = new ArrayList<>();

    protected Flight() {
    }

    public Flight(String flightNo, double emptyWeight, double emptyArm, double minCg, double maxCg) {
        this.flightNo = flightNo;
        this.emptyWeight = emptyWeight;
        this.emptyArm = emptyArm;
        this.minCg = minCg;
        this.maxCg = maxCg;
    }

    public void addHold(CargoHold hold) {
        hold.setFlight(this);
        this.holds.add(hold);
    }

    /** 应用新配置并递增配置版本号。 */
    public void applyConfig(double emptyWeight, double emptyArm, double minCg, double maxCg) {
        this.emptyWeight = emptyWeight;
        this.emptyArm = emptyArm;
        this.minCg = minCg;
        this.maxCg = maxCg;
        this.version++;
    }

    public void close() {
        this.status = FlightStatus.CLOSED;
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

    public double getEmptyWeight() {
        return emptyWeight;
    }

    public double getEmptyArm() {
        return emptyArm;
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

    public List<CargoHold> getHolds() {
        return holds;
    }
}

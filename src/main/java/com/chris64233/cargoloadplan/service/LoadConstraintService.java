package com.chris64233.cargoloadplan.service;

import com.chris64233.cargoloadplan.domain.CargoCategory;
import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.domain.Compartment;
import com.chris64233.cargoloadplan.domain.Flight;
import com.chris64233.cargoloadplan.domain.LoadPlanItem;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配载约束计算：货舱重量/体积/类别限制、互斥货物同舱检查、整机重心包线，
 * 以及方案准备时的配置指纹（用于检测准备期间的变化）。
 */
@Service
public class LoadConstraintService {

    private static final double EPSILON = 1e-6;

    /** 一条待校验的装载指令：货物单元 → 货舱。 */
    public record Placement(CargoUnit unit, Compartment compartment) {
    }

    /** 重心计算结果。 */
    public record CgResult(double totalWeightKg, double totalMoment, double cg, boolean withinEnvelope) {
    }

    /**
     * 校验一组装载指令。otherConfirmedItems 为同航班其他已确认方案的明细，
     * 舱位占用与互斥检查需要把它们一并计入。
     */
    public void validatePlacements(Flight flight, List<Placement> placements,
                                   List<LoadPlanItem> otherConfirmedItems) {
        List<String> violations = new ArrayList<>();
        Map<Long, Compartment> compartmentsById = flight.getCompartments().stream()
                .collect(Collectors.toMap(Compartment::getId, Function.identity()));

        for (Placement placement : placements) {
            Compartment compartment = compartmentsById.get(placement.compartment().getId());
            if (compartment == null) {
                violations.add("compartment " + placement.compartment().getId()
                        + " does not belong to flight " + flight.getFlightNo());
                continue;
            }
            if (!compartment.getAllowedCategories().contains(placement.unit().getCategory())) {
                violations.add("unit " + placement.unit().getUnitNo() + " category "
                        + placement.unit().getCategory() + " is not allowed in compartment "
                        + compartment.getCode());
            }
        }

        for (Compartment compartment : flight.getCompartments()) {
            List<CargoUnit> unitsInCompartment = new ArrayList<>();
            for (LoadPlanItem item : otherConfirmedItems) {
                if (item.getCompartment().getId().equals(compartment.getId())) {
                    unitsInCompartment.add(item.getCargoUnit());
                }
            }
            for (Placement placement : placements) {
                if (placement.compartment().getId().equals(compartment.getId())) {
                    unitsInCompartment.add(placement.unit());
                }
            }
            if (unitsInCompartment.isEmpty()) {
                continue;
            }
            double totalWeight = unitsInCompartment.stream().mapToDouble(CargoUnit::getWeightKg).sum();
            double totalVolume = unitsInCompartment.stream().mapToDouble(CargoUnit::getVolumeM3).sum();
            if (totalWeight > compartment.getMaxWeightKg() + EPSILON) {
                violations.add("compartment " + compartment.getCode() + " overweight: "
                        + totalWeight + "kg > " + compartment.getMaxWeightKg() + "kg");
            }
            if (totalVolume > compartment.getMaxVolumeM3() + EPSILON) {
                violations.add("compartment " + compartment.getCode() + " over volume: "
                        + totalVolume + "m3 > " + compartment.getMaxVolumeM3() + "m3");
            }
            for (int i = 0; i < unitsInCompartment.size(); i++) {
                for (int j = i + 1; j < unitsInCompartment.size(); j++) {
                    CargoUnit a = unitsInCompartment.get(i);
                    CargoUnit b = unitsInCompartment.get(j);
                    if (a.getIncompatibleCategories().contains(b.getCategory())
                            || b.getIncompatibleCategories().contains(a.getCategory())) {
                        violations.add("units " + a.getUnitNo() + " and " + b.getUnitNo()
                                + " are incompatible and cannot share compartment " + compartment.getCode());
                    }
                }
            }
        }

        CgResult cg = computeCg(flight, otherConfirmedItems, placements);
        if (!cg.withinEnvelope()) {
            violations.add("center of gravity " + cg.cg() + " is outside envelope ["
                    + flight.getMinCg() + ", " + flight.getMaxCg() + "]");
        }

        if (!violations.isEmpty()) {
            throw new LoadPlanException("CONSTRAINT_VIOLATION", String.join("; ", violations));
        }
    }

    /** 当前已确认装载下的整机重心。 */
    public CgResult computeCg(Flight flight, List<LoadPlanItem> confirmedItems) {
        return computeCg(flight, confirmedItems, List.of());
    }

    public CgResult computeCg(Flight flight, List<LoadPlanItem> confirmedItems, List<Placement> extraPlacements) {
        double totalWeight = flight.getBaseWeightKg();
        double totalMoment = flight.getBaseMoment();
        for (LoadPlanItem item : confirmedItems) {
            totalWeight += item.getCargoUnit().getWeightKg();
            totalMoment += item.getCargoUnit().getWeightKg() * item.getCompartment().getPositionArm();
        }
        for (Placement placement : extraPlacements) {
            totalWeight += placement.unit().getWeightKg();
            totalMoment += placement.unit().getWeightKg() * placement.compartment().getPositionArm();
        }
        double cg = totalWeight > EPSILON ? totalMoment / totalWeight : 0.0;
        boolean within = cg >= flight.getMinCg() - EPSILON && cg <= flight.getMaxCg() + EPSILON;
        return new CgResult(totalWeight, totalMoment, cg, within);
    }

    /**
     * 配置指纹：航班重心参数 + 全部货舱限制 + 方案内货物单元的重量/体积/类别/互斥规则。
     * 方案准备时计算并保存，确认时重算比对，不一致说明准备期间配置或货物已变化。
     */
    public String fingerprint(Flight flight, Collection<CargoUnit> units) {
        StringBuilder sb = new StringBuilder();
        sb.append("F|").append(flight.getBaseWeightKg())
                .append('|').append(flight.getBaseMoment())
                .append('|').append(flight.getMinCg())
                .append('|').append(flight.getMaxCg()).append('\n');
        flight.getCompartments().stream()
                .sorted(Comparator.comparing(Compartment::getCode))
                .forEach(c -> sb.append("C|").append(c.getCode())
                        .append('|').append(c.getMaxWeightKg())
                        .append('|').append(c.getMaxVolumeM3())
                        .append('|').append(c.getPositionArm())
                        .append('|').append(sortedCategories(c.getAllowedCategories()))
                        .append('\n'));
        units.stream()
                .sorted(Comparator.comparing(CargoUnit::getUnitNo))
                .forEach(u -> sb.append("U|").append(u.getUnitNo())
                        .append('|').append(u.getWeightKg())
                        .append('|').append(u.getVolumeM3())
                        .append('|').append(u.getCategory())
                        .append('|').append(sortedCategories(u.getIncompatibleCategories()))
                        .append('\n'));
        return sha256Hex(sb.toString());
    }

    private static String sortedCategories(Collection<CargoCategory> categories) {
        return categories.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

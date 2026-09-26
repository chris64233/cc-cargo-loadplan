package com.chris64233.cargoloadplan.service;

import com.chris64233.cargoloadplan.domain.CargoHold;
import com.chris64233.cargoloadplan.domain.Flight;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配载约束计算。对一组"货物单元 → 货舱"的前瞻装载结果做全量校验：
 * 货舱存在性、重量/体积上限、允许类别、互斥货物不可同舱、整机重心区间。
 */
@Component
public class ConstraintValidator {

    /** 一条前瞻装载项。 */
    public record LoadItem(String unitNo, String holdCode, double weight, double volume,
                           String category, Set<String> incompatibleCategories) {
    }

    /** 整机重心 = (空机重量 × 空机力臂 + Σ货物重量 × 舱位力臂) / 总重量。 */
    public double computeCg(Flight flight, List<LoadItem> items) {
        Map<String, CargoHold> holds = holdsByCode(flight);
        double moment = flight.getEmptyWeight() * flight.getEmptyArm();
        double totalWeight = flight.getEmptyWeight();
        for (LoadItem item : items) {
            CargoHold hold = holds.get(item.holdCode());
            if (hold == null) {
                continue; // 舱位不存在的问题由 validate 报告
            }
            moment += item.weight() * hold.getArm();
            totalWeight += item.weight();
        }
        return totalWeight == 0 ? 0 : moment / totalWeight;
    }

    /** 返回全部违规明细；空列表表示约束全部满足。 */
    public List<String> validate(Flight flight, List<LoadItem> items) {
        List<String> violations = new ArrayList<>();
        Map<String, CargoHold> holds = holdsByCode(flight);

        Map<String, List<LoadItem>> byHold = new LinkedHashMap<>();
        for (LoadItem item : items) {
            byHold.computeIfAbsent(item.holdCode(), k -> new ArrayList<>()).add(item);
        }

        for (Map.Entry<String, List<LoadItem>> entry : byHold.entrySet()) {
            String holdCode = entry.getKey();
            List<LoadItem> holdItems = entry.getValue();
            CargoHold hold = holds.get(holdCode);
            if (hold == null) {
                violations.add("货舱不存在: " + holdCode);
                continue;
            }
            double weight = holdItems.stream().mapToDouble(LoadItem::weight).sum();
            double volume = holdItems.stream().mapToDouble(LoadItem::volume).sum();
            if (weight > hold.getMaxWeight()) {
                violations.add("货舱 " + holdCode + " 超重: " + weight + " > " + hold.getMaxWeight());
            }
            if (volume > hold.getMaxVolume()) {
                violations.add("货舱 " + holdCode + " 超体积: " + volume + " > " + hold.getMaxVolume());
            }
            for (LoadItem item : holdItems) {
                if (!hold.allows(item.category())) {
                    violations.add("货舱 " + holdCode + " 不允许类别 " + item.category()
                            + " (货物 " + item.unitNo() + ")");
                }
            }
            for (int i = 0; i < holdItems.size(); i++) {
                for (int j = i + 1; j < holdItems.size(); j++) {
                    LoadItem a = holdItems.get(i);
                    LoadItem b = holdItems.get(j);
                    if (a.incompatibleCategories().contains(b.category())
                            || b.incompatibleCategories().contains(a.category())) {
                        violations.add("货物 " + a.unitNo() + " 与 " + b.unitNo()
                                + " 不可同舱 (货舱 " + holdCode + ")");
                    }
                }
            }
        }

        double cg = computeCg(flight, items);
        if (cg < flight.getMinCg() || cg > flight.getMaxCg()) {
            violations.add("整机重心 " + cg + " 超出允许区间 [" + flight.getMinCg()
                    + ", " + flight.getMaxCg() + "]");
        }
        return violations;
    }

    private Map<String, CargoHold> holdsByCode(Flight flight) {
        return flight.getHolds().stream()
                .collect(Collectors.toMap(CargoHold::getCode, Function.identity()));
    }
}

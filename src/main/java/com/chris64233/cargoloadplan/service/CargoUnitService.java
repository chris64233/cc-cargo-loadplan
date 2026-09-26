package com.chris64233.cargoloadplan.service;

import com.chris64233.cargoloadplan.domain.CargoCategory;
import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.repository.CargoUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 货物单元登记与维护。
 */
@Service
public class CargoUnitService {

    private final CargoUnitRepository cargoUnits;

    public CargoUnitService(CargoUnitRepository cargoUnits) {
        this.cargoUnits = cargoUnits;
    }

    @Transactional
    public CargoUnit register(String unitNo, double weightKg, double volumeM3, CargoCategory category,
                              Set<CargoCategory> incompatibleCategories) {
        return cargoUnits.save(new CargoUnit(unitNo, weightKg, volumeM3, category,
                incompatibleCategories == null ? Set.of() : incompatibleCategories));
    }

    /** 修改重量/体积：已准备但未确认的方案将因指纹变化而无法确认。 */
    @Transactional
    public CargoUnit update(String unitNo, Double weightKg, Double volumeM3) {
        CargoUnit unit = cargoUnits.findByUnitNo(unitNo)
                .orElseThrow(() -> new NotFoundException("cargo unit " + unitNo + " not found"));
        if (weightKg != null) {
            unit.setWeightKg(weightKg);
        }
        if (volumeM3 != null) {
            unit.setVolumeM3(volumeM3);
        }
        return cargoUnits.save(unit);
    }

    @Transactional(readOnly = true)
    public CargoUnit getByUnitNo(String unitNo) {
        return cargoUnits.findByUnitNo(unitNo)
                .orElseThrow(() -> new NotFoundException("cargo unit " + unitNo + " not found"));
    }
}

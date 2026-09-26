package com.chris64233.cargoloadplan.repository;

import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.domain.UnitStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CargoUnitRepository extends JpaRepository<CargoUnit, Long> {

    Optional<CargoUnit> findByUnitNo(String unitNo);

    List<CargoUnit> findByFlightIdAndStatus(Long flightId, UnitStatus status);

    List<CargoUnit> findByPlanNoAndStatus(String planNo, UnitStatus status);
}

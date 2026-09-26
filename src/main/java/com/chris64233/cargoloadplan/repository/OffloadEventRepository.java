package com.chris64233.cargoloadplan.repository;

import com.chris64233.cargoloadplan.domain.OffloadEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OffloadEventRepository extends JpaRepository<OffloadEvent, Long> {

    List<OffloadEvent> findByCargoUnit_UnitNoOrderByRecordedAt(String unitNo);

    List<OffloadEvent> findByFlight_IdOrderByRecordedAt(Long flightId);
}

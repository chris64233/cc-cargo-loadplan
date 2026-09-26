package com.chris64233.cargoloadplan.repository;

import com.chris64233.cargoloadplan.domain.UnloadEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UnloadEventRepository extends JpaRepository<UnloadEvent, Long> {

    List<UnloadEvent> findByUnitNoOrderByRecordedAtAsc(String unitNo);

    List<UnloadEvent> findByFlightIdOrderByRecordedAtAsc(Long flightId);
}

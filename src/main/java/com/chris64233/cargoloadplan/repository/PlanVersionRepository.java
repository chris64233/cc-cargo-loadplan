package com.chris64233.cargoloadplan.repository;

import com.chris64233.cargoloadplan.domain.PlanVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanVersionRepository extends JpaRepository<PlanVersion, Long> {

    List<PlanVersion> findByPlanIdOrderByVersionNoAsc(Long planId);

    Optional<PlanVersion> findByPlanIdAndVersionNo(Long planId, int versionNo);

    Optional<PlanVersion> findByPlanIdAndChangeNo(Long planId, String changeNo);
}

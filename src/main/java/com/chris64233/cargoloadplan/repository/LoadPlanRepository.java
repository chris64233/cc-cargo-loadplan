package com.chris64233.cargoloadplan.repository;

import com.chris64233.cargoloadplan.domain.LoadPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoadPlanRepository extends JpaRepository<LoadPlan, Long> {

    Optional<LoadPlan> findByPlanNo(String planNo);
}

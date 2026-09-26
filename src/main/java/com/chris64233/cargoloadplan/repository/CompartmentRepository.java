package com.chris64233.cargoloadplan.repository;

import com.chris64233.cargoloadplan.domain.Compartment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompartmentRepository extends JpaRepository<Compartment, Long> {
}

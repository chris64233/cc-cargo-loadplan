package com.chris64233.cargoloadplan.repository;

import com.chris64233.cargoloadplan.domain.Flight;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FlightRepository extends JpaRepository<Flight, Long> {

    Optional<Flight> findByFlightNo(String flightNo);

    /** 悲观写锁：串行化同一航班上的确认/调整/卸载/关闭等变更操作。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Flight f where f.id = :id")
    Optional<Flight> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Flight f where f.flightNo = :flightNo")
    Optional<Flight> findByFlightNoForUpdate(@Param("flightNo") String flightNo);
}

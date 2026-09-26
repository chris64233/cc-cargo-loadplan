package com.chris64233.cargoloadplan.service;

import com.chris64233.cargoloadplan.domain.CargoCategory;
import com.chris64233.cargoloadplan.domain.Compartment;
import com.chris64233.cargoloadplan.domain.Flight;
import com.chris64233.cargoloadplan.domain.FlightStatus;
import com.chris64233.cargoloadplan.repository.CompartmentRepository;
import com.chris64233.cargoloadplan.repository.FlightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 航班与货舱配置管理。
 */
@Service
public class FlightService {

    private final FlightRepository flights;
    private final CompartmentRepository compartments;

    public FlightService(FlightRepository flights, CompartmentRepository compartments) {
        this.flights = flights;
        this.compartments = compartments;
    }

    @Transactional
    public Flight createFlight(String flightNo, double baseWeightKg, double baseMoment,
                               double minCg, double maxCg) {
        if (minCg > maxCg) {
            throw new LoadPlanException("INVALID_ENVELOPE", "minCg must not exceed maxCg");
        }
        return flights.save(new Flight(flightNo, baseWeightKg, baseMoment, minCg, maxCg));
    }

    @Transactional
    public Compartment addCompartment(Long flightId, String code, double maxWeightKg, double maxVolumeM3,
                                      double positionArm, Set<CargoCategory> allowedCategories) {
        Flight flight = flights.findByIdForUpdate(flightId)
                .orElseThrow(() -> new NotFoundException("flight " + flightId + " not found"));
        Compartment compartment = new Compartment(code, maxWeightKg, maxVolumeM3, positionArm, allowedCategories);
        flight.addCompartment(compartment);
        return compartments.save(compartment);
    }

    /** 修改货舱限制：已准备但未确认的方案将因指纹变化而无法确认。 */
    @Transactional
    public Compartment updateCompartment(Long compartmentId, Double maxWeightKg, Double maxVolumeM3) {
        Compartment compartment = compartments.findById(compartmentId)
                .orElseThrow(() -> new NotFoundException("compartment " + compartmentId + " not found"));
        if (maxWeightKg != null) {
            compartment.setMaxWeightKg(maxWeightKg);
        }
        if (maxVolumeM3 != null) {
            compartment.setMaxVolumeM3(maxVolumeM3);
        }
        return compartments.save(compartment);
    }

    /** 关闭航班：之后方案不可修改，只能记录实际卸载事件。 */
    @Transactional
    public Flight closeFlight(Long flightId) {
        Flight flight = flights.findByIdForUpdate(flightId)
                .orElseThrow(() -> new NotFoundException("flight " + flightId + " not found"));
        if (flight.getStatus() == FlightStatus.CLOSED) {
            throw new LoadPlanException("FLIGHT_CLOSED",
                    "flight " + flight.getFlightNo() + " is already closed");
        }
        flight.setStatus(FlightStatus.CLOSED);
        return flights.save(flight);
    }

    @Transactional(readOnly = true)
    public Flight getFlight(Long flightId) {
        return flights.findById(flightId)
                .orElseThrow(() -> new NotFoundException("flight " + flightId + " not found"));
    }
}

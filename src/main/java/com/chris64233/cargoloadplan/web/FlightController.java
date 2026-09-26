package com.chris64233.cargoloadplan.web;

import com.chris64233.cargoloadplan.domain.Compartment;
import com.chris64233.cargoloadplan.domain.Flight;
import com.chris64233.cargoloadplan.domain.OffloadEvent;
import com.chris64233.cargoloadplan.service.FlightService;
import com.chris64233.cargoloadplan.service.LoadConstraintService;
import com.chris64233.cargoloadplan.service.LoadPlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.chris64233.cargoloadplan.web.ApiDtos.AddCompartmentRequest;
import static com.chris64233.cargoloadplan.web.ApiDtos.CompartmentResponse;
import static com.chris64233.cargoloadplan.web.ApiDtos.CompartmentUsageResponse;
import static com.chris64233.cargoloadplan.web.ApiDtos.ConstraintResponse;
import static com.chris64233.cargoloadplan.web.ApiDtos.CreateFlightRequest;
import static com.chris64233.cargoloadplan.web.ApiDtos.FlightResponse;
import static com.chris64233.cargoloadplan.web.ApiDtos.OffloadEventRequest;
import static com.chris64233.cargoloadplan.web.ApiDtos.OffloadEventResponse;
import static com.chris64233.cargoloadplan.web.ApiDtos.UpdateCompartmentRequest;

@RestController
@RequestMapping("/api/flights")
public class FlightController {

    private final FlightService flightService;
    private final LoadPlanService loadPlanService;

    public FlightController(FlightService flightService, LoadPlanService loadPlanService) {
        this.flightService = flightService;
        this.loadPlanService = loadPlanService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlightResponse createFlight(@Valid @RequestBody CreateFlightRequest request) {
        Flight flight = flightService.createFlight(request.flightNo(), request.baseWeightKg(),
                request.baseMoment(), request.minCg(), request.maxCg());
        return toResponse(flight);
    }

    @GetMapping("/{flightId}")
    public FlightResponse getFlight(@PathVariable Long flightId) {
        return toResponse(flightService.getFlight(flightId));
    }

    @PostMapping("/{flightId}/compartments")
    @ResponseStatus(HttpStatus.CREATED)
    public CompartmentResponse addCompartment(@PathVariable Long flightId,
                                              @Valid @RequestBody AddCompartmentRequest request) {
        return toResponse(flightService.addCompartment(flightId, request.code(), request.maxWeightKg(),
                request.maxVolumeM3(), request.positionArm(), request.allowedCategories()));
    }

    @PatchMapping("/compartments/{compartmentId}")
    public CompartmentResponse updateCompartment(@PathVariable Long compartmentId,
                                                 @Valid @RequestBody UpdateCompartmentRequest request) {
        return toResponse(flightService.updateCompartment(compartmentId, request.maxWeightKg(),
                request.maxVolumeM3()));
    }

    @PostMapping("/{flightId}/close")
    public FlightResponse closeFlight(@PathVariable Long flightId) {
        return toResponse(flightService.closeFlight(flightId));
    }

    /** 各货舱用量查询。 */
    @GetMapping("/{flightId}/usage")
    public List<CompartmentUsageResponse> compartmentUsage(@PathVariable Long flightId) {
        return loadPlanService.compartmentUsage(flightId).stream()
                .map(u -> new CompartmentUsageResponse(u.compartmentCode(), u.maxWeightKg(), u.usedWeightKg(),
                        u.remainingWeightKg(), u.maxVolumeM3(), u.usedVolumeM3(), u.remainingVolumeM3()))
                .toList();
    }

    /** 约束计算查询：当前已确认装载下的整机重心与包线校验。 */
    @GetMapping("/{flightId}/constraints")
    public ConstraintResponse constraints(@PathVariable Long flightId) {
        Flight flight = flightService.getFlight(flightId);
        LoadConstraintService.CgResult result = loadPlanService.constraintComputation(flightId);
        return new ConstraintResponse(result.totalWeightKg(), result.totalMoment(), result.cg(),
                flight.getMinCg(), flight.getMaxCg(), result.withinEnvelope());
    }

    /** 航班关闭后记录实际卸载事件。 */
    @PostMapping("/{flightId}/offload-events")
    @ResponseStatus(HttpStatus.CREATED)
    public OffloadEventResponse recordOffloadEvent(@PathVariable Long flightId,
                                                   @Valid @RequestBody OffloadEventRequest request) {
        OffloadEvent event = loadPlanService.recordOffloadEvent(flightId, request.unitNo(), request.reason());
        return new OffloadEventResponse(event.getCargoUnit().getUnitNo(), event.getPlanNo(),
                event.getReason(), event.getRecordedAt());
    }

    private static FlightResponse toResponse(Flight flight) {
        return new FlightResponse(flight.getId(), flight.getFlightNo(), flight.getStatus().name());
    }

    private static CompartmentResponse toResponse(Compartment compartment) {
        return new CompartmentResponse(compartment.getId(), compartment.getFlight().getId(),
                compartment.getCode(), compartment.getMaxWeightKg(), compartment.getMaxVolumeM3(),
                compartment.getPositionArm(), compartment.getAllowedCategories());
    }
}

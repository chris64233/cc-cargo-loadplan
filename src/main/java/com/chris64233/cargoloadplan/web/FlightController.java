package com.chris64233.cargoloadplan.web;

import com.chris64233.cargoloadplan.dto.ConstraintReportResponse;
import com.chris64233.cargoloadplan.dto.CreateFlightRequest;
import com.chris64233.cargoloadplan.dto.FlightResponse;
import com.chris64233.cargoloadplan.dto.HoldUsageResponse;
import com.chris64233.cargoloadplan.dto.RecordUnloadRequest;
import com.chris64233.cargoloadplan.dto.UnloadEventResponse;
import com.chris64233.cargoloadplan.dto.UpdateFlightConfigRequest;
import com.chris64233.cargoloadplan.service.FlightService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/flights")
public class FlightController {

    private final FlightService flightService;

    public FlightController(FlightService flightService) {
        this.flightService = flightService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlightResponse create(@Valid @RequestBody CreateFlightRequest req) {
        return flightService.createFlight(req);
    }

    @GetMapping("/{flightNo}")
    public FlightResponse get(@PathVariable String flightNo) {
        return flightService.getFlight(flightNo);
    }

    @PutMapping("/{flightNo}/config")
    public FlightResponse updateConfig(@PathVariable String flightNo,
                                       @Valid @RequestBody UpdateFlightConfigRequest req) {
        return flightService.updateConfig(flightNo, req);
    }

    @PostMapping("/{flightNo}/close")
    public FlightResponse close(@PathVariable String flightNo) {
        return flightService.close(flightNo);
    }

    @GetMapping("/{flightNo}/hold-usage")
    public List<HoldUsageResponse> holdUsage(@PathVariable String flightNo) {
        return flightService.holdUsage(flightNo);
    }

    @GetMapping("/{flightNo}/constraints")
    public ConstraintReportResponse constraints(@PathVariable String flightNo) {
        return flightService.constraints(flightNo);
    }

    @PostMapping("/{flightNo}/unload-events")
    @ResponseStatus(HttpStatus.CREATED)
    public UnloadEventResponse recordUnload(@PathVariable String flightNo,
                                            @Valid @RequestBody RecordUnloadRequest req) {
        return flightService.recordUnload(flightNo, req);
    }
}

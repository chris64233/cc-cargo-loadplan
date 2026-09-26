package com.chris64233.cargoloadplan.web;

import com.chris64233.cargoloadplan.dto.CargoUnitResponse;
import com.chris64233.cargoloadplan.dto.RegisterCargoRequest;
import com.chris64233.cargoloadplan.dto.UpdateWeightRequest;
import com.chris64233.cargoloadplan.dto.WhereaboutsResponse;
import com.chris64233.cargoloadplan.service.CargoService;
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

@RestController
@RequestMapping("/api/cargo-units")
public class CargoUnitController {

    private final CargoService cargoService;

    public CargoUnitController(CargoService cargoService) {
        this.cargoService = cargoService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CargoUnitResponse register(@Valid @RequestBody RegisterCargoRequest req) {
        return cargoService.register(req);
    }

    @GetMapping("/{unitNo}")
    public CargoUnitResponse get(@PathVariable String unitNo) {
        return cargoService.getUnit(unitNo);
    }

    @PutMapping("/{unitNo}/weight")
    public CargoUnitResponse updateWeight(@PathVariable String unitNo,
                                          @Valid @RequestBody UpdateWeightRequest req) {
        return cargoService.updateWeight(unitNo, req);
    }

    @GetMapping("/{unitNo}/whereabouts")
    public WhereaboutsResponse whereabouts(@PathVariable String unitNo) {
        return cargoService.whereabouts(unitNo);
    }
}

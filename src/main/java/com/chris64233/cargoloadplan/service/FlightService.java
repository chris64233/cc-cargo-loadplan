package com.chris64233.cargoloadplan.service;

import com.chris64233.cargoloadplan.domain.CargoHold;
import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.domain.Flight;
import com.chris64233.cargoloadplan.domain.FlightStatus;
import com.chris64233.cargoloadplan.domain.UnitStatus;
import com.chris64233.cargoloadplan.domain.UnloadEvent;
import com.chris64233.cargoloadplan.dto.ConstraintReportResponse;
import com.chris64233.cargoloadplan.dto.CreateFlightRequest;
import com.chris64233.cargoloadplan.dto.FlightResponse;
import com.chris64233.cargoloadplan.dto.HoldRequest;
import com.chris64233.cargoloadplan.dto.HoldUsageResponse;
import com.chris64233.cargoloadplan.dto.RecordUnloadRequest;
import com.chris64233.cargoloadplan.dto.UnloadEventResponse;
import com.chris64233.cargoloadplan.dto.UpdateFlightConfigRequest;
import com.chris64233.cargoloadplan.repository.CargoUnitRepository;
import com.chris64233.cargoloadplan.repository.FlightRepository;
import com.chris64233.cargoloadplan.repository.UnloadEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FlightService {

    private final FlightRepository flights;
    private final CargoUnitRepository units;
    private final UnloadEventRepository unloadEvents;
    private final ConstraintValidator validator;

    public FlightService(FlightRepository flights, CargoUnitRepository units,
                         UnloadEventRepository unloadEvents, ConstraintValidator validator) {
        this.flights = flights;
        this.units = units;
        this.unloadEvents = unloadEvents;
        this.validator = validator;
    }

    @Transactional
    public FlightResponse createFlight(CreateFlightRequest req) {
        flights.findByFlightNo(req.flightNo()).ifPresent(f -> {
            throw new ConflictException("航班号已存在: " + req.flightNo());
        });
        Flight flight = new Flight(req.flightNo(), req.emptyWeight(), req.emptyArm(),
                req.minCg(), req.maxCg());
        for (HoldRequest h : req.holds()) {
            flight.addHold(new CargoHold(h.code(), h.maxWeight(), h.maxVolume(), h.arm(),
                    h.allowedCategories()));
        }
        flights.save(flight);
        return toResponse(flight);
    }

    /** 变更航班配置（重心区间、货舱限制）。配置版本号递增，准备中的方案快照随之失效。 */
    @Transactional
    public FlightResponse updateConfig(String flightNo, UpdateFlightConfigRequest req) {
        Flight flight = lockFlight(flightNo);
        requireOpen(flight);
        flight.applyConfig(req.emptyWeight(), req.emptyArm(), req.minCg(), req.maxCg());
        Map<String, CargoHold> existing = flight.getHolds().stream()
                .collect(Collectors.toMap(CargoHold::getCode, Function.identity()));
        for (HoldRequest h : req.holds()) {
            CargoHold hold = existing.get(h.code());
            if (hold == null) {
                flight.addHold(new CargoHold(h.code(), h.maxWeight(), h.maxVolume(), h.arm(),
                        h.allowedCategories()));
            } else {
                hold.updateLimits(h.maxWeight(), h.maxVolume(), h.arm(), h.allowedCategories());
            }
        }
        return toResponse(flight);
    }

    @Transactional
    public FlightResponse close(String flightNo) {
        Flight flight = lockFlight(flightNo);
        requireOpen(flight);
        flight.close();
        return toResponse(flight);
    }

    @Transactional(readOnly = true)
    public FlightResponse getFlight(String flightNo) {
        return toResponse(findFlight(flightNo));
    }

    /** 舱位用量：按货舱汇总已确认装载的重量/体积占用与剩余量。 */
    @Transactional(readOnly = true)
    public List<HoldUsageResponse> holdUsage(String flightNo) {
        Flight flight = findFlight(flightNo);
        List<CargoUnit> loaded = units.findByFlightIdAndStatus(flight.getId(), UnitStatus.LOCKED);
        Map<String, List<CargoUnit>> byHold = loaded.stream()
                .collect(Collectors.groupingBy(u -> u.getHold().getCode()));
        List<HoldUsageResponse> result = new ArrayList<>();
        for (CargoHold hold : flight.getHolds()) {
            List<CargoUnit> holdUnits = byHold.getOrDefault(hold.getCode(), List.of());
            double usedWeight = holdUnits.stream().mapToDouble(CargoUnit::getWeight).sum();
            double usedVolume = holdUnits.stream().mapToDouble(CargoUnit::getVolume).sum();
            result.add(new HoldUsageResponse(hold.getCode(), hold.getMaxWeight(), hold.getMaxVolume(),
                    usedWeight, usedVolume,
                    hold.getMaxWeight() - usedWeight, hold.getMaxVolume() - usedVolume,
                    holdUnits.stream().map(CargoUnit::getUnitNo).sorted().toList()));
        }
        return result;
    }

    /** 约束计算：当前已确认载重下的整机重心与全部约束校验明细。 */
    @Transactional(readOnly = true)
    public ConstraintReportResponse constraints(String flightNo) {
        Flight flight = findFlight(flightNo);
        List<ConstraintValidator.LoadItem> items = units
                .findByFlightIdAndStatus(flight.getId(), UnitStatus.LOCKED).stream()
                .map(u -> new ConstraintValidator.LoadItem(u.getUnitNo(), u.getHold().getCode(),
                        u.getWeight(), u.getVolume(), u.getCategory(), u.getIncompatibleCategories()))
                .toList();
        double cg = validator.computeCg(flight, items);
        List<String> violations = validator.validate(flight, items);
        boolean within = cg >= flight.getMinCg() && cg <= flight.getMaxCg();
        return new ConstraintReportResponse(flightNo, cg, flight.getMinCg(), flight.getMaxCg(),
                within, violations);
    }

    /** 航班关闭后记录实际卸载事件；这是关闭后唯一允许的写操作。 */
    @Transactional
    public UnloadEventResponse recordUnload(String flightNo, RecordUnloadRequest req) {
        Flight flight = lockFlight(flightNo);
        if (flight.getStatus() != FlightStatus.CLOSED) {
            throw new ConflictException("航班未关闭，不能记录实际卸载事件");
        }
        CargoUnit unit = units.findByUnitNo(req.unitNo())
                .orElseThrow(() -> new NotFoundException("货物单元不存在: " + req.unitNo()));
        if (unit.getStatus() != UnitStatus.LOCKED || unit.getFlight() == null
                || !unit.getFlight().getId().equals(flight.getId())) {
            throw new ConflictException("货物 " + req.unitNo() + " 未装载在航班 " + flightNo + " 上");
        }
        UnloadEvent event = new UnloadEvent(flight, unit.getUnitNo(), unit.getPlanNo(),
                unit.getHold().getCode(), req.actualWeight(), Instant.now());
        unloadEvents.save(event);
        unit.markUnloaded();
        return toResponse(event);
    }

    private Flight findFlight(String flightNo) {
        return flights.findByFlightNo(flightNo)
                .orElseThrow(() -> new NotFoundException("航班不存在: " + flightNo));
    }

    private Flight lockFlight(String flightNo) {
        return flights.findByFlightNoForUpdate(flightNo)
                .orElseThrow(() -> new NotFoundException("航班不存在: " + flightNo));
    }

    private void requireOpen(Flight flight) {
        if (flight.getStatus() != FlightStatus.OPEN) {
            throw new ConflictException("航班已关闭，方案不可修改: " + flight.getFlightNo());
        }
    }

    private FlightResponse toResponse(Flight flight) {
        return new FlightResponse(flight.getFlightNo(), flight.getStatus().name(),
                flight.getEmptyWeight(), flight.getEmptyArm(), flight.getMinCg(), flight.getMaxCg(),
                flight.getVersion(),
                flight.getHolds().stream()
                        .map(h -> new FlightResponse.HoldView(h.getCode(), h.getMaxWeight(),
                                h.getMaxVolume(), h.getArm(), h.getAllowedCategories()))
                        .toList());
    }

    private UnloadEventResponse toResponse(UnloadEvent event) {
        return new UnloadEventResponse(event.getId(), event.getFlight().getFlightNo(),
                event.getUnitNo(), event.getPlanNo(), event.getHoldCode(),
                event.getActualWeight(), event.getRecordedAt());
    }
}

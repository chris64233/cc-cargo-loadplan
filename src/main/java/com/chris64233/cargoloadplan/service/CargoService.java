package com.chris64233.cargoloadplan.service;

import com.chris64233.cargoloadplan.domain.CargoUnit;
import com.chris64233.cargoloadplan.domain.UnitStatus;
import com.chris64233.cargoloadplan.dto.CargoUnitResponse;
import com.chris64233.cargoloadplan.dto.RegisterCargoRequest;
import com.chris64233.cargoloadplan.dto.UnloadEventResponse;
import com.chris64233.cargoloadplan.dto.UpdateWeightRequest;
import com.chris64233.cargoloadplan.dto.WhereaboutsResponse;
import com.chris64233.cargoloadplan.repository.CargoUnitRepository;
import com.chris64233.cargoloadplan.repository.UnloadEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CargoService {

    private final CargoUnitRepository units;
    private final UnloadEventRepository unloadEvents;

    public CargoService(CargoUnitRepository units, UnloadEventRepository unloadEvents) {
        this.units = units;
        this.unloadEvents = unloadEvents;
    }

    @Transactional
    public CargoUnitResponse register(RegisterCargoRequest req) {
        units.findByUnitNo(req.unitNo()).ifPresent(u -> {
            throw new ConflictException("货物单元号已存在: " + req.unitNo());
        });
        CargoUnit unit = new CargoUnit(req.unitNo(), req.weight(), req.volume(), req.category(),
                req.incompatibleCategories());
        units.save(unit);
        return toResponse(unit);
    }

    /**
     * 修改货物重量。仅空闲货物可改；修改会使版本号递增，
     * 准备中的方案因快照失效而不能确认。
     */
    @Transactional
    public CargoUnitResponse updateWeight(String unitNo, UpdateWeightRequest req) {
        CargoUnit unit = findUnit(unitNo);
        if (unit.getStatus() != UnitStatus.AVAILABLE) {
            throw new ConflictException("货物 " + unitNo + " 已锁定或已卸载，不能修改重量");
        }
        unit.changeWeight(req.weight());
        return toResponse(unit);
    }

    @Transactional(readOnly = true)
    public CargoUnitResponse getUnit(String unitNo) {
        return toResponse(findUnit(unitNo));
    }

    /** 货物去向：当前状态、所在航班/货舱/方案及全部实际卸载事件。 */
    @Transactional(readOnly = true)
    public WhereaboutsResponse whereabouts(String unitNo) {
        CargoUnit unit = findUnit(unitNo);
        var events = unloadEvents.findByUnitNoOrderByRecordedAtAsc(unitNo).stream()
                .map(e -> new UnloadEventResponse(e.getId(), e.getFlight().getFlightNo(),
                        e.getUnitNo(), e.getPlanNo(), e.getHoldCode(), e.getActualWeight(),
                        e.getRecordedAt()))
                .toList();
        return new WhereaboutsResponse(unit.getUnitNo(), unit.getStatus().name(),
                unit.getFlight() == null ? null : unit.getFlight().getFlightNo(),
                unit.getHold() == null ? null : unit.getHold().getCode(),
                unit.getPlanNo(), events);
    }

    private CargoUnit findUnit(String unitNo) {
        return units.findByUnitNo(unitNo)
                .orElseThrow(() -> new NotFoundException("货物单元不存在: " + unitNo));
    }

    private CargoUnitResponse toResponse(CargoUnit unit) {
        return new CargoUnitResponse(unit.getUnitNo(), unit.getWeight(), unit.getVolume(),
                unit.getCategory(), unit.getIncompatibleCategories(), unit.getStatus().name(),
                unit.getFlight() == null ? null : unit.getFlight().getFlightNo(),
                unit.getHold() == null ? null : unit.getHold().getCode(),
                unit.getPlanNo(), unit.getVersion());
    }
}

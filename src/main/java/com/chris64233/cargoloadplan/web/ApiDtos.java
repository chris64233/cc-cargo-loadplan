package com.chris64233.cargoloadplan.web;

import com.chris64233.cargoloadplan.domain.CargoCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** REST API 请求/响应 DTO。 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record CreateFlightRequest(@NotBlank String flightNo, @Positive double baseWeightKg,
                                      double baseMoment, double minCg, double maxCg) {
    }

    public record FlightResponse(Long id, String flightNo, String status) {
    }

    public record AddCompartmentRequest(@NotBlank String code, @Positive double maxWeightKg,
                                        @Positive double maxVolumeM3, double positionArm,
                                        @NotEmpty Set<CargoCategory> allowedCategories) {
    }

    public record UpdateCompartmentRequest(@Positive Double maxWeightKg, @Positive Double maxVolumeM3) {
    }

    public record CompartmentResponse(Long id, Long flightId, String code, double maxWeightKg,
                                      double maxVolumeM3, double positionArm,
                                      Set<CargoCategory> allowedCategories) {
    }

    public record RegisterCargoRequest(@NotBlank String unitNo, @Positive double weightKg,
                                       @Positive double volumeM3, @NotNull CargoCategory category,
                                       Set<CargoCategory> incompatibleCategories) {
    }

    public record UpdateCargoRequest(@Positive Double weightKg, @Positive Double volumeM3) {
    }

    public record CargoUnitResponse(Long id, String unitNo, double weightKg, double volumeM3,
                                    CargoCategory category, Set<CargoCategory> incompatibleCategories,
                                    String status) {
    }

    public record PlacementSpecRequest(@NotNull Long cargoUnitId, @NotNull Long compartmentId) {
    }

    public record PreparePlanRequest(@NotBlank String planNo, @NotNull Long flightId,
                                     @NotEmpty List<PlacementSpecRequest> items) {
    }

    public record AdjustPlanRequest(@NotNull List<PlacementSpecRequest> items) {
    }

    public record OffloadUnitsRequest(@NotEmpty List<Long> cargoUnitIds) {
    }

    public record PlanItemResponse(String unitNo, String compartmentCode) {
    }

    public record PlanResponse(String planNo, String status, Long flightId, List<PlanItemResponse> items) {
    }

    public record CompartmentUsageResponse(String compartmentCode, double maxWeightKg, double usedWeightKg,
                                           double remainingWeightKg, double maxVolumeM3, double usedVolumeM3,
                                           double remainingVolumeM3) {
    }

    public record ConstraintResponse(double totalWeightKg, double totalMoment, double cg,
                                     double minCg, double maxCg, boolean withinEnvelope) {
    }

    public record OffloadEventRequest(@NotBlank String unitNo, String reason) {
    }

    public record OffloadEventResponse(String unitNo, String planNo, String reason, Instant recordedAt) {
    }

    public record WhereaboutsResponse(String unitNo, String status, String planNo, String compartmentCode,
                                      List<OffloadEventResponse> offloadEvents) {
    }

    public record ErrorResponse(String code, String message) {
    }
}

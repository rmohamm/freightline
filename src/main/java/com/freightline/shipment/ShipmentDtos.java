package com.freightline.shipment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request and response types for the shipment API. */
public final class ShipmentDtos {

    private ShipmentDtos() {
    }

    public record CreateShipmentRequest(
            @NotBlank String origin,
            @NotBlank String destination,
            @NotBlank String originTimeZone,
            double weightKg,
            @NotBlank String carrierCode,
            @NotNull ServiceLevel serviceLevel) {
    }

    public record AddEventRequest(
            @NotNull ShipmentStatus status,
            String location,
            String note,
            Instant occurredAt) {
    }

    public record ShipmentResponse(
            Long id,
            String trackingNumber,
            String origin,
            String destination,
            double weightKg,
            String carrierCode,
            ServiceLevel serviceLevel,
            ShipmentStatus status,
            Instant createdAt,
            LocalDate estimatedDelivery) {

        static ShipmentResponse from(Shipment s) {
            return new ShipmentResponse(
                    s.getId(),
                    s.getTrackingNumber(),
                    s.getOrigin(),
                    s.getDestination(),
                    s.getWeightKg(),
                    s.getCarrier().getCode(),
                    s.getServiceLevel(),
                    s.getStatus(),
                    s.getCreatedAt(),
                    s.getEstimatedDelivery());
        }
    }

    public record ShipmentPage(
            List<ShipmentResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    public record ShipmentEventResponse(
            Long id,
            ShipmentStatus status,
            String location,
            String note,
            Instant occurredAt) {

        static ShipmentEventResponse from(ShipmentEvent e) {
            return new ShipmentEventResponse(e.getId(), e.getStatus(), e.getLocation(), e.getNote(), e.getOccurredAt());
        }
    }

    public record ShipmentSummary(
            String trackingNumber,
            String carrierName,
            ShipmentStatus status,
            String lastLocation,
            Instant lastEventAt,
            int eventCount) {
    }
}

package com.freightline.shipment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;

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

    public record ShipmentPageResponse(
            List<ShipmentResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        public List<ShipmentResponse> getShipments() {
            return content;
        }

        public int getNumber() {
            return page;
        }

        public int getPageNumber() {
            return page;
        }

        public int getPageSize() {
            return size;
        }

        public boolean isFirst() {
            return page == 0;
        }

        public boolean isLast() {
            return totalPages == 0 || page >= totalPages - 1;
        }

        public int getNumberOfElements() {
            return content.size();
        }

        public boolean isEmpty() {
            return content.isEmpty();
        }

        public static ShipmentPageResponse from(Page<ShipmentResponse> p) {
            return new ShipmentPageResponse(
                    p.getContent(),
                    p.getNumber(),
                    p.getSize(),
                    p.getTotalElements(),
                    p.getTotalPages());
        }
    }
}

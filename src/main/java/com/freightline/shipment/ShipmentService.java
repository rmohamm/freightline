package com.freightline.shipment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.freightline.carrier.Carrier;
import com.freightline.carrier.CarrierRepository;
import com.freightline.shipment.ShipmentDtos.AddEventRequest;
import com.freightline.shipment.ShipmentDtos.CreateShipmentRequest;
import com.freightline.shipment.ShipmentDtos.ShipmentEventResponse;
import com.freightline.shipment.ShipmentDtos.ShipmentPageResponse;
import com.freightline.shipment.ShipmentDtos.ShipmentResponse;
import com.freightline.shipment.ShipmentDtos.ShipmentSummary;

@Service
public class ShipmentService {

    private final ShipmentRepository shipments;
    private final CarrierRepository carriers;
    private final DeliveryEstimator deliveryEstimator;
    private final Clock clock;

    public ShipmentService(ShipmentRepository shipments, CarrierRepository carriers,
                           DeliveryEstimator deliveryEstimator, Clock clock) {
        this.shipments = shipments;
        this.carriers = carriers;
        this.deliveryEstimator = deliveryEstimator;
        this.clock = clock;
    }

    @Transactional
    public ShipmentResponse create(CreateShipmentRequest request) {
        Carrier carrier = carriers.findById(request.carrierCode())
                .orElseThrow(() -> new IllegalArgumentException("Unknown carrier: " + request.carrierCode()));
        ZoneId originZone = ZoneId.of(request.originTimeZone());

        Instant now = clock.instant();
        Shipment shipment = new Shipment(
                newTrackingNumber(),
                request.origin(),
                request.destination(),
                originZone.getId(),
                request.weightKg(),
                carrier,
                request.serviceLevel(),
                now);
        shipment.setEstimatedDelivery(deliveryEstimator.estimate(now, request.serviceLevel()));
        shipment.addEvent(new ShipmentEvent(ShipmentStatus.CREATED, request.origin(), "Shipment created", now));

        return ShipmentResponse.from(shipments.save(shipment));
    }

    @Transactional(readOnly = true)
    public ShipmentResponse get(Long id) {
        return ShipmentResponse.from(shipments.findById(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<ShipmentResponse> list() {
        return shipments.findAll().stream()
                .map(ShipmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ShipmentPageResponse list(int page, int size) {
        int effectiveSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, effectiveSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ShipmentResponse> shipmentPage = shipments.findAll(pageable)
                .map(ShipmentResponse::from);
        return ShipmentPageResponse.from(shipmentPage);
    }

    @Transactional
    public ShipmentEventResponse addEvent(Long shipmentId, AddEventRequest request) {
        Shipment shipment = shipments.findById(shipmentId).orElseThrow();
        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : clock.instant();

        ShipmentEvent event = new ShipmentEvent(request.status(), request.location(), request.note(), occurredAt);
        shipment.addEvent(event);
        shipments.saveAndFlush(shipment);

        return ShipmentEventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public List<ShipmentEventResponse> history(Long shipmentId) {
        Shipment shipment = shipments.findById(shipmentId).orElseThrow();
        return shipment.getEvents().stream()
                .map(ShipmentEventResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ShipmentSummary> summary() {
        return shipments.findAll().stream()
                .map(s -> {
                    List<ShipmentEvent> events = s.getEvents();
                    ShipmentEvent last = events.isEmpty() ? null : events.get(events.size() - 1);
                    return new ShipmentSummary(
                            s.getTrackingNumber(),
                            s.getCarrier().getName(),
                            s.getStatus(),
                            last != null ? last.getLocation() : null,
                            last != null ? last.getOccurredAt() : null,
                            events.size());
                })
                .toList();
    }

    private static String newTrackingNumber() {
        return "FL" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}

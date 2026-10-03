package com.freightline.shipment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.freightline.carrier.Carrier;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;

@Entity
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String trackingNumber;

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    private String destination;

    /** IANA time zone of the origin facility, e.g. "America/Chicago". */
    @Column(nullable = false)
    private String originTimeZone;

    @Column(nullable = false)
    private double weightKg;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "carrier_code")
    private Carrier carrier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ServiceLevel serviceLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private LocalDate estimatedDelivery;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("occurredAt ASC")
    private List<ShipmentEvent> events = new ArrayList<>();

    protected Shipment() {
    }

    public Shipment(String trackingNumber, String origin, String destination, String originTimeZone,
                    double weightKg, Carrier carrier, ServiceLevel serviceLevel, Instant createdAt) {
        this.trackingNumber = trackingNumber;
        this.origin = origin;
        this.destination = destination;
        this.originTimeZone = originTimeZone;
        this.weightKg = weightKg;
        this.carrier = carrier;
        this.serviceLevel = serviceLevel;
        this.createdAt = createdAt;
        this.status = ShipmentStatus.CREATED;
    }

    public void addEvent(ShipmentEvent event) {
        event.setShipment(this);
        events.add(event);
        this.status = event.getStatus();
    }

    public Long getId() {
        return id;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public String getOriginTimeZone() {
        return originTimeZone;
    }

    public double getWeightKg() {
        return weightKg;
    }

    public Carrier getCarrier() {
        return carrier;
    }

    public ServiceLevel getServiceLevel() {
        return serviceLevel;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public LocalDate getEstimatedDelivery() {
        return estimatedDelivery;
    }

    public void setEstimatedDelivery(LocalDate estimatedDelivery) {
        this.estimatedDelivery = estimatedDelivery;
    }

    public List<ShipmentEvent> getEvents() {
        return events;
    }
}

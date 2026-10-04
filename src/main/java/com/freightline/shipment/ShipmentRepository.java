package com.freightline.shipment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    @EntityGraph(attributePaths = {"events", "carrier"})
    List<Shipment> findAllWithEventsAndCarrierBy();

    Optional<Shipment> findByTrackingNumber(String trackingNumber);
}

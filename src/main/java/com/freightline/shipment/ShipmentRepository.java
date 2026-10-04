package com.freightline.shipment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    /** Loads all shipments with their carrier and events in a single query. */
    @Query("""
            select distinct s from Shipment s
            join fetch s.carrier
            left join fetch s.events
            order by s.id""")
    List<Shipment> findAllWithCarrierAndEvents();
}

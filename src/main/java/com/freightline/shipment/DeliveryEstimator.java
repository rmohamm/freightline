package com.freightline.shipment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * Estimates the delivery date for a shipment from the time it was created
 * and its service level (calendar days in transit).
 */
@Component
public class DeliveryEstimator {

    public LocalDate estimate(Instant createdAt, ZoneId originZone, ServiceLevel serviceLevel) {
        // Timestamps are stored in UTC, but the ship date is the local date at the origin facility.
        LocalDate shipDate = LocalDate.ofInstant(createdAt, originZone);
        return shipDate.plusDays(serviceLevel.transitDays());
    }
}

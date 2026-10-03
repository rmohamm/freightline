package com.freightline.shipment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

/**
 * Estimates the delivery date for a shipment from the time it was created
 * and its service level (calendar days in transit).
 */
@Component
public class DeliveryEstimator {

    public LocalDate estimate(Instant createdAt, ServiceLevel serviceLevel) {
        // All timestamps are stored in UTC.
        LocalDate shipDate = LocalDate.ofInstant(createdAt, ZoneOffset.UTC);
        return shipDate.plusDays(serviceLevel.transitDays());
    }
}

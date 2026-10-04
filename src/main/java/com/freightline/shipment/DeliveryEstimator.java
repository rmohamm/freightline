package com.freightline.shipment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

/**
 * Estimates the delivery date for a shipment from the time it was created,
 * its service level (calendar days in transit), and the origin facility's time zone.
 */
@Component
public class DeliveryEstimator {

    public LocalDate estimate(Instant createdAt, ServiceLevel serviceLevel, ZoneId originZone) {
        ZoneId zone = originZone != null ? originZone : ZoneOffset.UTC;
        LocalDate shipDate = LocalDate.ofInstant(createdAt, zone);
        return shipDate.plusDays(serviceLevel.transitDays());
    }

    public LocalDate estimate(Instant createdAt, ServiceLevel serviceLevel, String originTimeZone) {
        return estimate(createdAt, serviceLevel, originTimeZone != null ? ZoneId.of(originTimeZone) : ZoneOffset.UTC);
    }

    public LocalDate estimate(Instant createdAt, ServiceLevel serviceLevel) {
        return estimate(createdAt, serviceLevel, ZoneOffset.UTC);
    }
}

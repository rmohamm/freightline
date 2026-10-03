package com.freightline.shipment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DeliveryEstimatorTest {

    private final DeliveryEstimator estimator = new DeliveryEstimator();

    // Midday UTC, so the ship date is the same in every US time zone.
    private static final Instant MIDDAY = Instant.parse("2026-11-12T18:00:00Z");

    @Test
    void standardTakesFiveDays() {
        assertEquals(LocalDate.of(2026, 11, 17), estimator.estimate(MIDDAY, ServiceLevel.STANDARD));
    }

    @Test
    void expressTakesTwoDays() {
        assertEquals(LocalDate.of(2026, 11, 14), estimator.estimate(MIDDAY, ServiceLevel.EXPRESS));
    }

    @Test
    void overnightTakesOneDay() {
        assertEquals(LocalDate.of(2026, 11, 13), estimator.estimate(MIDDAY, ServiceLevel.OVERNIGHT));
    }
}

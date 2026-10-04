package com.freightline.shipment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

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

    @Test
    void countsFromLocalOriginDateForEveningShipments() {
        // 9:30 PM Central on Thursday, Nov 12, 2026 is 03:30 AM UTC on Friday, Nov 13, 2026.
        Instant evening = Instant.parse("2026-11-13T03:30:00Z");
        ZoneId chicago = ZoneId.of("America/Chicago");

        assertEquals(LocalDate.of(2026, 11, 17),
                estimator.estimate(evening, ServiceLevel.STANDARD, chicago));
    }

    @Test
    void afternoonAndEveningShipmentsOnSameDateHaveSameEstimate() {
        // 2:00 PM Central on Thursday, Nov 12, 2026 (20:00 UTC)
        Instant afternoon = Instant.parse("2026-11-12T20:00:00Z");
        // 9:30 PM Central on Thursday, Nov 12, 2026 (03:30 UTC next day)
        Instant evening = Instant.parse("2026-11-13T03:30:00Z");
        ZoneId chicago = ZoneId.of("America/Chicago");

        LocalDate afternoonEstimate = estimator.estimate(afternoon, ServiceLevel.STANDARD, chicago);
        LocalDate eveningEstimate = estimator.estimate(evening, ServiceLevel.STANDARD, chicago);

        assertEquals(LocalDate.of(2026, 11, 17), afternoonEstimate);
        assertEquals(afternoonEstimate, eveningEstimate);
    }

    @Test
    void supportsStringOriginTimeZone() {
        Instant evening = Instant.parse("2026-11-13T03:30:00Z");
        assertEquals(LocalDate.of(2026, 11, 17),
                estimator.estimate(evening, ServiceLevel.STANDARD, "America/Chicago"));
    }
}

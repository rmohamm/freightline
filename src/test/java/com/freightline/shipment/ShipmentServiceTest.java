package com.freightline.shipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.freightline.carrier.Carrier;
import com.freightline.carrier.CarrierRepository;
import com.freightline.shipment.ShipmentDtos.CreateShipmentRequest;
import com.freightline.shipment.ShipmentDtos.ShipmentResponse;

class ShipmentServiceTest {

    private ShipmentRepository shipments;
    private CarrierRepository carriers;
    private DeliveryEstimator deliveryEstimator;

    @BeforeEach
    void setUp() {
        shipments = mock(ShipmentRepository.class);
        carriers = mock(CarrierRepository.class);
        deliveryEstimator = new DeliveryEstimator();

        when(carriers.findById("RDLN"))
                .thenReturn(Optional.of(new Carrier("RDLN", "Redline Logistics", new BigDecimal("1.75"))));
        when(shipments.save(any(Shipment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsShipmentWithEstimateBasedOnOriginTimeZoneForEveningShipment() {
        // 9:30 PM Central on Thursday, Nov 12, 2026 is 03:30 AM UTC on Friday, Nov 13, 2026.
        Instant evening = Instant.parse("2026-11-13T03:30:00Z");
        Clock fixedClock = Clock.fixed(evening, ZoneOffset.UTC);

        ShipmentService service = new ShipmentService(shipments, carriers, deliveryEstimator, fixedClock);

        CreateShipmentRequest request = new CreateShipmentRequest(
                "Dallas, TX",
                "Atlanta, GA",
                "America/Chicago",
                12.5,
                "RDLN",
                ServiceLevel.STANDARD);

        ShipmentResponse response = service.create(request);

        assertEquals(LocalDate.of(2026, 11, 17), response.estimatedDelivery());
    }
}

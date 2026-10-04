package com.freightline.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.freightline.carrier.Carrier;
import com.freightline.carrier.CarrierRepository;
import com.freightline.shipment.DeliveryEstimator;
import com.freightline.shipment.ServiceLevel;
import com.freightline.shipment.Shipment;
import com.freightline.shipment.ShipmentEvent;
import com.freightline.shipment.ShipmentRepository;
import com.freightline.shipment.ShipmentStatus;

/**
 * Loads a few hundred realistic shipments for demos.
 * Run with: mvn spring-boot:run -Dspring-boot.run.profiles=demo
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements CommandLineRunner {

    private static final int SHIPMENT_COUNT = 500;

    private static final List<String[]> LANES = List.of(
            new String[] {"Dallas, TX", "America/Chicago", "Atlanta, GA"},
            new String[] {"Chicago, IL", "America/Chicago", "Denver, CO"},
            new String[] {"Los Angeles, CA", "America/Los_Angeles", "Phoenix, AZ"},
            new String[] {"Newark, NJ", "America/New_York", "Charlotte, NC"},
            new String[] {"Seattle, WA", "America/Los_Angeles", "Salt Lake City, UT"},
            new String[] {"Memphis, TN", "America/Chicago", "Columbus, OH"});

    private static final List<ShipmentStatus> PROGRESSION = List.of(
            ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.OUT_FOR_DELIVERY,
            ShipmentStatus.DELIVERED);

    private final ShipmentRepository shipments;
    private final CarrierRepository carriers;
    private final DeliveryEstimator estimator;
    private final Clock clock;

    public DemoDataSeeder(ShipmentRepository shipments, CarrierRepository carriers,
                          DeliveryEstimator estimator, Clock clock) {
        this.shipments = shipments;
        this.carriers = carriers;
        this.estimator = estimator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Random random = new Random(42);
        List<Carrier> allCarriers = carriers.findAll();
        ServiceLevel[] levels = ServiceLevel.values();

        for (int i = 0; i < SHIPMENT_COUNT; i++) {
            String[] lane = LANES.get(random.nextInt(LANES.size()));
            Carrier carrier = allCarriers.get(random.nextInt(allCarriers.size()));
            ServiceLevel level = levels[random.nextInt(levels.length)];
            Instant createdAt = clock.instant().minus(Duration.ofHours(random.nextInt(24 * 14)));
            double weightKg = 1 + random.nextInt(400) / 4.0;

            Shipment shipment = new Shipment(
                    String.format("FLDEMO%06d", i), lane[0], lane[2], lane[1],
                    weightKg, carrier, level, createdAt);
            shipment.setEstimatedDelivery(estimator.estimate(createdAt, level, ZoneId.of(lane[1])));
            shipment.addEvent(new ShipmentEvent(ShipmentStatus.CREATED, lane[0], "Shipment created", createdAt));

            int steps = random.nextInt(PROGRESSION.size() + 1);
            Instant at = createdAt;
            for (int s = 0; s < steps; s++) {
                at = at.plus(Duration.ofHours(4 + random.nextInt(20)));
                String location = s < 2 ? lane[0] : lane[2];
                shipment.addEvent(new ShipmentEvent(PROGRESSION.get(s), location, null, at));
            }
            shipments.save(shipment);
        }
    }
}

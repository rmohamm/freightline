package com.freightline.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.freightline.shipment.ShipmentDtos.CreateShipmentRequest;
import com.freightline.shipment.ShipmentDtos.ShipmentSummary;

import jakarta.persistence.EntityManagerFactory;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ShipmentSummaryQueryCountTest {

    @Autowired
    private ShipmentService service;

    @Autowired
    private EntityManagerFactory emf;

    @Test
    void summaryQueryCountDoesNotGrowWithShipments() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();

        createShipments(3);
        stats.clear();
        List<ShipmentSummary> small = service.summary();
        long smallQueries = stats.getPrepareStatementCount();

        createShipments(20);
        stats.clear();
        List<ShipmentSummary> large = service.summary();
        long largeQueries = stats.getPrepareStatementCount();

        assertThat(large.size()).isGreaterThan(small.size());
        assertThat(largeQueries).isEqualTo(smallQueries);
        assertThat(largeQueries).isLessThanOrEqualTo(2);
    }

    private void createShipments(int count) {
        for (int i = 0; i < count; i++) {
            service.create(new CreateShipmentRequest("Dallas, TX", "Atlanta, GA", "America/Chicago",
                    12.5, "RDLN", ServiceLevel.STANDARD));
        }
    }
}

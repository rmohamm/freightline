package com.freightline.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManagerFactory;

@SpringBootTest
@AutoConfigureMockMvc
class ShipmentSummaryQueryCountTest {

    private static final String[] CARRIERS = {"FSHP", "RDLN", "PRCL"};

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @Test
    void summaryQueryCountDoesNotGrowWithShipments() throws Exception {
        createShipmentsWithEvents(3);
        long before = summaryQueryCount();

        createShipmentsWithEvents(10);
        long after = summaryQueryCount();

        assertThat(after)
                .as("queries for /api/shipments/summary after adding 10 shipments (was %d before)", before)
                .isEqualTo(before);
    }

    @Test
    void summaryReportsLatestEventAndEventCount() throws Exception {
        Created shipment = createShipment("PRCL");
        String trackingNumber = shipment.trackingNumber();
        long id = shipment.id();
        addEvent(id, "IN_TRANSIT", "Memphis, TN", "2030-01-02T10:00:00Z");
        // Recorded later but occurred earlier: must not become the "last" event.
        addEvent(id, "PICKED_UP", "Dallas, TX", "2030-01-01T10:00:00Z");

        String path = "$[?(@.trackingNumber == '" + trackingNumber + "')]";
        mvc.perform(get("/api/shipments/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(path + ".carrierName").value("Parcel Prime"))
                .andExpect(jsonPath(path + ".status").value("PICKED_UP"))
                .andExpect(jsonPath(path + ".lastLocation").value("Memphis, TN"))
                .andExpect(jsonPath(path + ".lastEventAt").value("2030-01-02T10:00:00Z"))
                .andExpect(jsonPath(path + ".eventCount").value(3));
    }

    private long summaryQueryCount() throws Exception {
        statistics.clear();
        mvc.perform(get("/api/shipments/summary")).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private void createShipmentsWithEvents(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            long id = createShipment(CARRIERS[i % CARRIERS.length]).id();
            addEvent(id, "PICKED_UP", "Dallas, TX", null);
            addEvent(id, "IN_TRANSIT", "Little Rock, AR", null);
        }
    }

    private Created createShipment(String carrierCode) throws Exception {
        MvcResult result = mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "Dallas, TX",
                                  "destination": "Atlanta, GA",
                                  "originTimeZone": "America/Chicago",
                                  "weightKg": 12.5,
                                  "carrierCode": "%s",
                                  "serviceLevel": "STANDARD"
                                }
                                """.formatted(carrierCode)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new Created(((Number) JsonPath.read(body, "$.id")).longValue(), JsonPath.read(body, "$.trackingNumber"));
    }

    private record Created(long id, String trackingNumber) {
    }

    private void addEvent(long shipmentId, String status, String location, String occurredAt) throws Exception {
        String occurred = occurredAt == null ? "" : ", \"occurredAt\": \"" + occurredAt + "\"";
        mvc.perform(post("/api/shipments/{id}/events", shipmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"%s\", \"location\": \"%s\"%s}".formatted(status, location, occurred)))
                .andExpect(status().isCreated());
    }
}

package com.freightline.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class ShipmentApiTest {

    private static final String VALID_SHIPMENT = """
            {
              "origin": "Dallas, TX",
              "destination": "Atlanta, GA",
              "originTimeZone": "America/Chicago",
              "weightKg": 12.5,
              "carrierCode": "RDLN",
              "serviceLevel": "STANDARD"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void createsShipment() throws Exception {
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPMENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trackingNumber").value(startsWith("FL")))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.carrierCode").value("RDLN"))
                .andExpect(jsonPath("$.estimatedDelivery").exists());
    }

    @Test
    void rejectsBlankOrigin() throws Exception {
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPMENT.replace("\"Dallas, TX\"", "\"\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownCarrier() throws Exception {
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPMENT.replace("RDLN", "NOPE")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidTimeZone() throws Exception {
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPMENT.replace("America/Chicago", "Mars/Olympus_Mons")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getsShipmentById() throws Exception {
        long id = createShipment();

        mvc.perform(get("/api/shipments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.origin").value("Dallas, TX"));
    }

    @Test
    void recordsStatusEventsInHistory() throws Exception {
        long id = createShipment();

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "PICKED_UP", "location": "Dallas, TX", "note": "Driver scan"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PICKED_UP"));

        mvc.perform(get("/api/shipments/{id}", id))
                .andExpect(jsonPath("$.status").value("PICKED_UP"));

        mvc.perform(get("/api/shipments/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("CREATED"))
                .andExpect(jsonPath("$[1].status").value("PICKED_UP"));
    }

    @Test
    void listsShipments() throws Exception {
        long id = createShipment();

        mvc.perform(get("/api/shipments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) id)));
    }

    @Test
    void summarizesShipments() throws Exception {
        MvcResult created = mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPMENT))
                .andReturn();
        String trackingNumber = JsonPath.read(created.getResponse().getContentAsString(), "$.trackingNumber");

        mvc.perform(get("/api/shipments/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].trackingNumber", hasItem(trackingNumber)))
                .andExpect(jsonPath("$[*].carrierName", hasItem("Redline Logistics")));
    }

    @Test
    void summaryQueryCountDoesNotGrowWithShipmentCount() throws Exception {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);

        // Ensure at least 2 shipments with different carriers exist
        createShipment();
        createShipmentWithCarrier("FSHP");

        // Warm up and verify query count with initial shipments
        stats.clear();
        mvc.perform(get("/api/shipments/summary")).andExpect(status().isOk());
        long queriesWithInitialCount = stats.getPrepareStatementCount();

        // Create 5 additional shipments with events across multiple carriers
        for (int i = 0; i < 5; i++) {
            long id = i % 2 == 0 ? createShipment() : createShipmentWithCarrier("PRCL");
            mvc.perform(post("/api/shipments/{id}/events", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "PICKED_UP", "location": "Dallas, TX", "note": "Driver scan"}
                                    """))
                    .andExpect(status().isCreated());
        }

        stats.clear();
        mvc.perform(get("/api/shipments/summary")).andExpect(status().isOk());
        long queriesWithAdditionalCount = stats.getPrepareStatementCount();

        // The query count must be fixed at 1 and not grow with the number of shipments
        assertThat(queriesWithInitialCount).isEqualTo(1);
        assertThat(queriesWithAdditionalCount)
                .withFailMessage("Expected query count not to grow, but went from %d to %d",
                        queriesWithInitialCount, queriesWithAdditionalCount)
                .isEqualTo(queriesWithInitialCount);
    }

    @Test
    void summaryReportsLastEventAccurately() throws Exception {
        long id = createShipment();
        MvcResult shipmentResult = mvc.perform(get("/api/shipments/{id}", id)).andReturn();
        String trackingNumber = JsonPath.read(shipmentResult.getResponse().getContentAsString(), "$.trackingNumber");

        java.time.Instant now = java.time.Instant.now();
        // Add event with future timestamp
        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"status": "DELIVERED", "location": "Final Destination", "note": "Delivered", "occurredAt": "%s"}
                                """, now.plus(java.time.Duration.ofHours(5)))))
                .andExpect(status().isCreated());

        // Add event with timestamp in between
        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"status": "IN_TRANSIT", "location": "Intermediate Hub", "note": "In transit", "occurredAt": "%s"}
                                """, now.plus(java.time.Duration.ofHours(2)))))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/shipments/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.trackingNumber == '" + trackingNumber + "')].eventCount").value(3))
                .andExpect(jsonPath("$[?(@.trackingNumber == '" + trackingNumber + "')].lastLocation").value("Final Destination"))
                .andExpect(jsonPath("$[?(@.trackingNumber == '" + trackingNumber + "')].status").value("IN_TRANSIT"));
    }

    private long createShipmentWithCarrier(String carrierCode) throws Exception {
        MvcResult result = mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPMENT.replace("RDLN", carrierCode)))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private long createShipment() throws Exception {
        MvcResult result = mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPMENT))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }
}

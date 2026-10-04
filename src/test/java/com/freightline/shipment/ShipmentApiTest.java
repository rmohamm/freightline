package com.freightline.shipment;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void rejectsEventAfterDelivery() throws Exception {
        long id = createShipment();

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "DELIVERED", "location": "Atlanta, GA", "note": "Delivered to door"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // Carrier replaying old in-transit event after delivery
        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "IN_TRANSIT", "location": "Memphis, TN"}
                                """))
                .andExpect(status().isConflict());

        // Status must remain DELIVERED
        mvc.perform(get("/api/shipments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // History must not have been changed
        mvc.perform(get("/api/shipments/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("CREATED"))
                .andExpect(jsonPath("$[1].status").value("DELIVERED"));
    }

    @Test
    void rejectsDuplicateScanAfterDelivery() throws Exception {
        long id = createShipment();

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "DELIVERED", "location": "Atlanta, GA"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "DELIVERED", "location": "Atlanta, GA"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void rejectsExceptionAfterDelivery() throws Exception {
        long id = createShipment();

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "DELIVERED", "location": "Atlanta, GA"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "EXCEPTION", "location": "Atlanta, GA", "note": "Late report"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void rejectsMovingBackwardsBeforeDelivery() throws Exception {
        long id = createShipment();

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "IN_TRANSIT", "location": "Nashville, TN"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "PICKED_UP", "location": "Dallas, TX"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").exists());

        // Status remains IN_TRANSIT
        mvc.perform(get("/api/shipments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // History unchanged
        mvc.perform(get("/api/shipments/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("CREATED"))
                .andExpect(jsonPath("$[1].status").value("IN_TRANSIT"));
    }

    @Test
    void allowsDuplicateScansBeforeDelivery() throws Exception {
        long id = createShipment();

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "PICKED_UP", "location": "Dallas, TX"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "PICKED_UP", "location": "Dallas, TX", "note": "Duplicate scan"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PICKED_UP"));

        mvc.perform(get("/api/shipments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED_UP"));

        mvc.perform(get("/api/shipments/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].status").value("CREATED"))
                .andExpect(jsonPath("$[1].status").value("PICKED_UP"))
                .andExpect(jsonPath("$[2].status").value("PICKED_UP"));
    }

    @Test
    void allowsSkippingAheadInLifecycle() throws Exception {
        long id = createShipment();

        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "OUT_FOR_DELIVERY", "location": "Atlanta, GA"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"));

        mvc.perform(get("/api/shipments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"));
    }

    @Test
    void handlesExceptionTransitions() throws Exception {
        long id = createShipment();

        // Exception before delivery
        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "EXCEPTION", "location": "Dallas, TX", "note": "Flat tire"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXCEPTION"));

        // From EXCEPTION cannot move to CREATED
        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "CREATED", "location": "Dallas, TX"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").exists());

        // From EXCEPTION can move forward (e.g. IN_TRANSIT)
        mvc.perform(post("/api/shipments/{id}/events", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "IN_TRANSIT", "location": "Dallas, TX", "note": "Tire fixed, resuming"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
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

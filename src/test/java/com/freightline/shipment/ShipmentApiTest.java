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
    void returnsNotFoundForUnknownShipment() throws Exception {
        mvc.perform(get("/api/shipments/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Shipment 999999 not found"));
    }

    @Test
    void returnsNotFoundForUnknownShipmentHistory() throws Exception {
        mvc.perform(get("/api/shipments/999999/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Shipment 999999 not found"));
    }

    @Test
    void returnsNotFoundWhenAddingEventToUnknownShipment() throws Exception {
        mvc.perform(post("/api/shipments/999999/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"IN_TRANSIT\", \"location\": \"Dallas, TX\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Shipment 999999 not found"));
    }

    @Test
    void rejectsBlankOrigin() throws Exception {
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPMENT.replace("\"Dallas, TX\"", "\"\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNegativeWeight() throws Exception {
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPMENT.replace("12.5", "-5")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsZeroWeight() throws Exception {
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHIPMENT.replace("12.5", "0")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownCarrier()throws Exception {
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
    void rejectsEventsAfterDeliveredWithoutChangingShipment() throws Exception {
        long id = createShipment();
        postEvent(id, "DELIVERED").andExpect(status().isCreated());

        postEvent(id, "IN_TRANSIT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cannot move shipment from DELIVERED to IN_TRANSIT"));
        postEvent(id, "DELIVERED").andExpect(status().isConflict());
        postEvent(id, "EXCEPTION").andExpect(status().isConflict());

        mvc.perform(get("/api/shipments/{id}", id))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        mvc.perform(get("/api/shipments/{id}/history", id))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void rejectsMovingBackwards() throws Exception {
        long id = createShipment();
        postEvent(id, "IN_TRANSIT").andExpect(status().isCreated());

        postEvent(id, "PICKED_UP").andExpect(status().isConflict());
        postEvent(id, "CREATED").andExpect(status().isConflict());

        mvc.perform(get("/api/shipments/{id}", id))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        mvc.perform(get("/api/shipments/{id}/history", id))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void allowsSkippingAheadAndDuplicateScans() throws Exception {
        long id = createShipment();
        postEvent(id, "PICKED_UP").andExpect(status().isCreated());
        postEvent(id, "PICKED_UP").andExpect(status().isCreated());
        postEvent(id, "OUT_FOR_DELIVERY").andExpect(status().isCreated());

        mvc.perform(get("/api/shipments/{id}/history", id))
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void exceptionCanResumeToAnyStatusExceptCreated() throws Exception {
        long id = createShipment();
        postEvent(id, "IN_TRANSIT").andExpect(status().isCreated());
        postEvent(id, "EXCEPTION").andExpect(status().isCreated());
        postEvent(id, "CREATED").andExpect(status().isConflict());
        postEvent(id, "PICKED_UP").andExpect(status().isCreated());
        postEvent(id, "EXCEPTION").andExpect(status().isCreated());
        postEvent(id, "DELIVERED").andExpect(status().isCreated());
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

    private org.springframework.test.web.servlet.ResultActions postEvent(long id, String status) throws Exception {
        return mvc.perform(post("/api/shipments/{id}/events", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"" + status + "\", \"location\": \"Memphis, TN\"}"));
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

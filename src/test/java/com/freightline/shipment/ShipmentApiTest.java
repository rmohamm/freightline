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
                .andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
    }

    @Test
    void listsShipmentsWithPaginationDefaults() throws Exception {
        createShipment();

        mvc.perform(get("/api/shipments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }

    @Test
    void listsShipmentsWithCustomPageAndSize() throws Exception {
        createShipment();
        createShipment();

        mvc.perform(get("/api/shipments").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void sortsShipmentsByCreatedAtNewestFirst() throws Exception {
        long id1 = createShipment();
        Thread.sleep(20);
        long id2 = createShipment();

        MvcResult result = mvc.perform(get("/api/shipments").param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();

        Number firstId = JsonPath.read(result.getResponse().getContentAsString(), "$.content[0].id");
        org.junit.jupiter.api.Assertions.assertEquals(id2, firstId.longValue());
    }

    @Test
    void capsPageSizeAt100() throws Exception {
        mvc.perform(get("/api/shipments").param("size", "150"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void rejectsNegativePage() throws Exception {
        mvc.perform(get("/api/shipments").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonPositiveSize() throws Exception {
        mvc.perform(get("/api/shipments").param("size", "0"))
                .andExpect(status().isBadRequest());
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

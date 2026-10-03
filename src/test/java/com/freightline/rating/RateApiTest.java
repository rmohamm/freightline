package com.freightline.rating;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RateApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void returnsQuoteForKnownCarrier() throws Exception {
        mvc.perform(post("/api/rates/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carrierCode": "PRCL", "serviceLevel": "STANDARD", "weightKg": 10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrierCode").value("PRCL"))
                .andExpect(jsonPath("$.total").value(27.0));
    }

    @Test
    void rejectsUnknownCarrier() throws Exception {
        mvc.perform(post("/api/rates/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carrierCode": "NOPE", "serviceLevel": "STANDARD", "weightKg": 10}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonPositiveWeight() throws Exception {
        mvc.perform(post("/api/rates/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carrierCode": "PRCL", "serviceLevel": "STANDARD", "weightKg": 0}
                                """))
                .andExpect(status().isBadRequest());
    }
}

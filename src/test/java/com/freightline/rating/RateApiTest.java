package com.freightline.rating;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    void quotesFastShipStandardThreeKgWithExactCentsRounding() throws Exception {
        mvc.perform(post("/api/rates/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carrierCode": "FSHP", "serviceLevel": "STANDARD", "weightKg": 3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrierCode").value("FSHP"))
                .andExpect(jsonPath("$.serviceLevel").value("STANDARD"))
                .andExpect(jsonPath("$.weightKg").value(3.0))
                .andExpect(jsonPath("$.subtotal").value(6.30))
                .andExpect(jsonPath("$.fuelSurcharge").value(0.50))
                .andExpect(jsonPath("$.total").value(6.80))
                .andExpect(content().string(containsString("\"subtotal\":6.30")))
                .andExpect(content().string(containsString("\"fuelSurcharge\":0.50")))
                .andExpect(content().string(containsString("\"total\":6.80")));
    }

    @Test
    void quotesFastShipExpressSevenKgWithExactCentsRounding() throws Exception {
        mvc.perform(post("/api/rates/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carrierCode": "FSHP", "serviceLevel": "EXPRESS", "weightKg": 7}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrierCode").value("FSHP"))
                .andExpect(jsonPath("$.serviceLevel").value("EXPRESS"))
                .andExpect(jsonPath("$.weightKg").value(7.0))
                .andExpect(jsonPath("$.subtotal").value(22.05))
                .andExpect(jsonPath("$.fuelSurcharge").value(1.76))
                .andExpect(jsonPath("$.total").value(23.81))
                .andExpect(content().string(containsString("\"subtotal\":22.05")))
                .andExpect(content().string(containsString("\"fuelSurcharge\":1.76")))
                .andExpect(content().string(containsString("\"total\":23.81")));
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

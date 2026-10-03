package com.freightline.rating;

import java.math.BigDecimal;

import com.freightline.shipment.ServiceLevel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RateQuote(
        String carrierCode,
        ServiceLevel serviceLevel,
        double weightKg,
        BigDecimal subtotal,
        BigDecimal fuelSurcharge,
        BigDecimal total) {

    public record Request(
            @NotBlank String carrierCode,
            @NotNull ServiceLevel serviceLevel,
            @Positive double weightKg) {
    }
}

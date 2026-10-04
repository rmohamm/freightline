package com.freightline.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.freightline.carrier.Carrier;
import com.freightline.carrier.CarrierRepository;
import com.freightline.shipment.ServiceLevel;

/**
 * Prices a shipment: weight x carrier rate per kg x service level multiplier,
 * plus a fuel surcharge on the subtotal.
 */
@Component
public class RateCalculator {

    static final BigDecimal FUEL_SURCHARGE_RATE = new BigDecimal("0.08");

    private final CarrierRepository carriers;

    public RateCalculator(CarrierRepository carriers) {
        this.carriers = carriers;
    }

    public RateQuote quote(String carrierCode, ServiceLevel serviceLevel, double weightKg) {
        Carrier carrier = carriers.findById(carrierCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown carrier: " + carrierCode));

        BigDecimal subtotal = BigDecimal.valueOf(weightKg)
                .multiply(carrier.getRatePerKg())
                .multiply(BigDecimal.valueOf(serviceLevel.rateMultiplier()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal fuelSurcharge = subtotal.multiply(FUEL_SURCHARGE_RATE)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(fuelSurcharge);

        return new RateQuote(carrierCode, serviceLevel, weightKg, subtotal, fuelSurcharge, total);
    }
}

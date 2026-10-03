package com.freightline.rating;

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

    static final double FUEL_SURCHARGE_RATE = 0.08;

    private final CarrierRepository carriers;

    public RateCalculator(CarrierRepository carriers) {
        this.carriers = carriers;
    }

    public RateQuote quote(String carrierCode, ServiceLevel serviceLevel, double weightKg) {
        Carrier carrier = carriers.findById(carrierCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown carrier: " + carrierCode));

        double subtotal = weightKg * carrier.getRatePerKg().doubleValue() * serviceLevel.rateMultiplier();
        double fuelSurcharge = subtotal * FUEL_SURCHARGE_RATE;
        double total = subtotal + fuelSurcharge;

        return new RateQuote(carrierCode, serviceLevel, weightKg, subtotal, fuelSurcharge, total);
    }
}

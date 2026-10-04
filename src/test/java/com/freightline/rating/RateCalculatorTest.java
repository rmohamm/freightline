package com.freightline.rating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.freightline.carrier.Carrier;
import com.freightline.carrier.CarrierRepository;
import com.freightline.shipment.ServiceLevel;

class RateCalculatorTest {

    private CarrierRepository carriers;
    private RateCalculator calculator;

    @BeforeEach
    void setUp() {
        carriers = mock(CarrierRepository.class);
        when(carriers.findById("PRCL"))
                .thenReturn(Optional.of(new Carrier("PRCL", "Parcel Prime", new BigDecimal("2.50"))));
        when(carriers.findById("RDLN"))
                .thenReturn(Optional.of(new Carrier("RDLN", "Redline Logistics", new BigDecimal("1.75"))));
        when(carriers.findById("NOPE")).thenReturn(Optional.empty());
        calculator = new RateCalculator(carriers);
    }

    @Test
    void standardQuoteAppliesRateAndFuelSurcharge() {
        RateQuote quote = calculator.quote("PRCL", ServiceLevel.STANDARD, 10);

        assertEquals(new BigDecimal("25.00"), quote.subtotal());
        assertEquals(new BigDecimal("2.00"), quote.fuelSurcharge());
        assertEquals(new BigDecimal("27.00"), quote.total());
    }

    @Test
    void expressQuoteAppliesServiceLevelMultiplier() {
        RateQuote quote = calculator.quote("RDLN", ServiceLevel.EXPRESS, 4);

        assertEquals(new BigDecimal("10.50"), quote.subtotal());
        assertEquals(new BigDecimal("0.84"), quote.fuelSurcharge());
        assertEquals(new BigDecimal("11.34"), quote.total());
    }

    @Test
    void amountsAreRoundedToCentsHalfUp() {
        when(carriers.findById("FSHP"))
                .thenReturn(Optional.of(new Carrier("FSHP", "FastShip", new BigDecimal("2.10"))));

        RateQuote quote = calculator.quote("FSHP", ServiceLevel.STANDARD, 3);

        assertEquals(new BigDecimal("6.30"), quote.subtotal());
        assertEquals(new BigDecimal("0.50"), quote.fuelSurcharge());
        assertEquals(new BigDecimal("6.80"), quote.total());
    }

    @Test
    void unknownCarrierIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.quote("NOPE", ServiceLevel.STANDARD, 10));
    }
}

package com.freightline.shipment;

public enum ServiceLevel {
    STANDARD(5, 1.0),
    EXPRESS(2, 1.5),
    OVERNIGHT(1, 2.25);

    private final int transitDays;
    private final double rateMultiplier;

    ServiceLevel(int transitDays, double rateMultiplier) {
        this.transitDays = transitDays;
        this.rateMultiplier = rateMultiplier;
    }

    public int transitDays() {
        return transitDays;
    }

    public double rateMultiplier() {
        return rateMultiplier;
    }
}

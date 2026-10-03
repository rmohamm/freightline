package com.freightline.carrier;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Carrier {

    @Id
    @Column(length = 8)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "rate_per_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal ratePerKg;

    protected Carrier() {
    }

    public Carrier(String code, String name, BigDecimal ratePerKg) {
        this.code = code;
        this.name = name;
        this.ratePerKg = ratePerKg;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getRatePerKg() {
        return ratePerKg;
    }
}

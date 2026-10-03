package com.freightline.rating;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/rates")
public class RateController {

    private final RateCalculator calculator;

    public RateController(RateCalculator calculator) {
        this.calculator = calculator;
    }

    @PostMapping("/quote")
    public RateQuote quote(@Valid @RequestBody RateQuote.Request request) {
        return calculator.quote(request.carrierCode(), request.serviceLevel(), request.weightKg());
    }
}

package com.freightline.shipment;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.freightline.shipment.ShipmentDtos.AddEventRequest;
import com.freightline.shipment.ShipmentDtos.CreateShipmentRequest;
import com.freightline.shipment.ShipmentDtos.ShipmentEventResponse;
import com.freightline.shipment.ShipmentDtos.ShipmentPageResponse;
import com.freightline.shipment.ShipmentDtos.ShipmentResponse;
import com.freightline.shipment.ShipmentDtos.ShipmentSummary;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentService service;

    public ShipmentController(ShipmentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShipmentResponse create(@Valid @RequestBody CreateShipmentRequest request) {
        return service.create(request);
    }

    @GetMapping
    public ShipmentPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/summary")
    public List<ShipmentSummary> summary() {
        return service.summary();
    }

    @GetMapping("/{id}")
    public ShipmentResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/events")
    @ResponseStatus(HttpStatus.CREATED)
    public ShipmentEventResponse addEvent(@PathVariable Long id, @Valid @RequestBody AddEventRequest request) {
        return service.addEvent(id, request);
    }

    @GetMapping("/{id}/history")
    public List<ShipmentEventResponse> history(@PathVariable Long id) {
        return service.history(id);
    }
}

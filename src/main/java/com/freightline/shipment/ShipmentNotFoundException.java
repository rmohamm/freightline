package com.freightline.shipment;

public class ShipmentNotFoundException extends RuntimeException {

    public ShipmentNotFoundException(Long id) {
        super("Shipment " + id + " not found");
    }
}

package com.freightline.shipment;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(ShipmentStatus from, ShipmentStatus to) {
        super("Cannot move shipment from " + from + " to " + to);
    }
}

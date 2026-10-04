package com.freightline.shipment;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(ShipmentStatus from, ShipmentStatus to) {
        super(from == ShipmentStatus.DELIVERED
                ? "Cannot record " + to + ": shipment is already DELIVERED"
                : "Cannot move shipment from " + from + " to " + to);
    }
}

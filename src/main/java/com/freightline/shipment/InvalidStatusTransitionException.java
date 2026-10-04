package com.freightline.shipment;

public class InvalidStatusTransitionException extends IllegalStateException {

    public InvalidStatusTransitionException(ShipmentStatus from, ShipmentStatus to) {
        super(formatMessage(from, to));
    }

    public InvalidStatusTransitionException(String message) {
        super(message);
    }

    private static String formatMessage(ShipmentStatus from, ShipmentStatus to) {
        if (from == ShipmentStatus.DELIVERED) {
            return "Cannot record " + to + " event: shipment is already DELIVERED";
        }
        if (to == ShipmentStatus.CREATED) {
            return "Cannot transition shipment from " + from + " to CREATED";
        }
        return "Cannot transition shipment backwards from " + from + " to " + to;
    }
}

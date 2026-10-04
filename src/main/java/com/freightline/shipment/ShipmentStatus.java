package com.freightline.shipment;

/**
 * Lifecycle of a shipment, in the order it normally moves through:
 * CREATED -> PICKED_UP -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED.
 * EXCEPTION marks a problem (damage, missed delivery, customs hold).
 */
public enum ShipmentStatus {
    CREATED,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    EXCEPTION;

    /**
     * Whether a shipment currently in this status may record an event with {@code next}.
     * Nothing can follow DELIVERED; EXCEPTION can resolve to anything but CREATED;
     * otherwise status may only stay the same or move forward (skipping is fine).
     */
    public boolean canTransitionTo(ShipmentStatus next) {
        if (this == DELIVERED) {
            return false;
        }
        if (this == next || next == EXCEPTION) {
            return true;
        }
        if (this == EXCEPTION) {
            return next != CREATED;
        }
        return next.ordinal() > ordinal();
    }
}

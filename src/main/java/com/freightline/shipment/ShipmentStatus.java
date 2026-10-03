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
     * Whether an event with status {@code next} may be recorded on a shipment currently in this status.
     * Status only moves forward (skipping ahead is fine); a repeat of the same status is a duplicate scan;
     * EXCEPTION is allowed any time before delivery and can resume to anything except CREATED;
     * nothing is allowed after DELIVERED.
     */
    public boolean canTransitionTo(ShipmentStatus next) {
        if (this == DELIVERED) {
            return false;
        }
        if (next == this || next == EXCEPTION) {
            return true;
        }
        if (this == EXCEPTION) {
            return next != CREATED;
        }
        return next.ordinal() > ordinal();
    }
}

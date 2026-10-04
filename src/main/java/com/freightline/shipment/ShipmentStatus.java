package com.freightline.shipment;

import java.util.List;

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

    private static final List<ShipmentStatus> FORWARD_LIFECYCLE = List.of(
            CREATED,
            PICKED_UP,
            IN_TRANSIT,
            OUT_FOR_DELIVERY,
            DELIVERED
    );

    /**
     * Determines whether transitioning from this status to {@code next} is allowed.
     *
     * Rules:
     * - Status moves forward through CREATED -> PICKED_UP -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED.
     * - Skipping ahead is fine (carriers miss scans).
     * - EXCEPTION can be recorded at any time before delivery.
     * - From EXCEPTION, shipment can move to any status except CREATED.
     * - Nothing can be recorded after DELIVERED.
     * - Duplicate scans (same status twice in a row) are allowed before delivery.
     */
    public boolean canTransitionTo(ShipmentStatus next) {
        if (next == null) {
            return false;
        }
        if (this == DELIVERED) {
            return false;
        }
        if (this == EXCEPTION) {
            return next != CREATED;
        }
        if (next == EXCEPTION) {
            return true;
        }
        int currentIndex = FORWARD_LIFECYCLE.indexOf(this);
        int nextIndex = FORWARD_LIFECYCLE.indexOf(next);
        return nextIndex >= currentIndex;
    }
}

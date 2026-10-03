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
    EXCEPTION
}

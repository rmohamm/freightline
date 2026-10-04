package com.freightline.shipment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShipmentStatusTest {

    @Test
    void allowsNormalForwardLifecycle() {
        assertTrue(ShipmentStatus.CREATED.canTransitionTo(ShipmentStatus.PICKED_UP));
        assertTrue(ShipmentStatus.PICKED_UP.canTransitionTo(ShipmentStatus.IN_TRANSIT));
        assertTrue(ShipmentStatus.IN_TRANSIT.canTransitionTo(ShipmentStatus.OUT_FOR_DELIVERY));
        assertTrue(ShipmentStatus.OUT_FOR_DELIVERY.canTransitionTo(ShipmentStatus.DELIVERED));
    }

    @Test
    void allowsSkippingAhead() {
        assertTrue(ShipmentStatus.CREATED.canTransitionTo(ShipmentStatus.IN_TRANSIT));
        assertTrue(ShipmentStatus.CREATED.canTransitionTo(ShipmentStatus.OUT_FOR_DELIVERY));
        assertTrue(ShipmentStatus.CREATED.canTransitionTo(ShipmentStatus.DELIVERED));

        assertTrue(ShipmentStatus.PICKED_UP.canTransitionTo(ShipmentStatus.OUT_FOR_DELIVERY));
        assertTrue(ShipmentStatus.PICKED_UP.canTransitionTo(ShipmentStatus.DELIVERED));

        assertTrue(ShipmentStatus.IN_TRANSIT.canTransitionTo(ShipmentStatus.DELIVERED));
    }

    @Test
    void allowsDuplicateScansBeforeDelivery() {
        assertTrue(ShipmentStatus.CREATED.canTransitionTo(ShipmentStatus.CREATED));
        assertTrue(ShipmentStatus.PICKED_UP.canTransitionTo(ShipmentStatus.PICKED_UP));
        assertTrue(ShipmentStatus.IN_TRANSIT.canTransitionTo(ShipmentStatus.IN_TRANSIT));
        assertTrue(ShipmentStatus.OUT_FOR_DELIVERY.canTransitionTo(ShipmentStatus.OUT_FOR_DELIVERY));
        assertTrue(ShipmentStatus.EXCEPTION.canTransitionTo(ShipmentStatus.EXCEPTION));
    }

    @Test
    void rejectsMovingBackwards() {
        assertFalse(ShipmentStatus.PICKED_UP.canTransitionTo(ShipmentStatus.CREATED));

        assertFalse(ShipmentStatus.IN_TRANSIT.canTransitionTo(ShipmentStatus.CREATED));
        assertFalse(ShipmentStatus.IN_TRANSIT.canTransitionTo(ShipmentStatus.PICKED_UP));

        assertFalse(ShipmentStatus.OUT_FOR_DELIVERY.canTransitionTo(ShipmentStatus.CREATED));
        assertFalse(ShipmentStatus.OUT_FOR_DELIVERY.canTransitionTo(ShipmentStatus.PICKED_UP));
        assertFalse(ShipmentStatus.OUT_FOR_DELIVERY.canTransitionTo(ShipmentStatus.IN_TRANSIT));
    }

    @Test
    void allowsExceptionBeforeDelivery() {
        assertTrue(ShipmentStatus.CREATED.canTransitionTo(ShipmentStatus.EXCEPTION));
        assertTrue(ShipmentStatus.PICKED_UP.canTransitionTo(ShipmentStatus.EXCEPTION));
        assertTrue(ShipmentStatus.IN_TRANSIT.canTransitionTo(ShipmentStatus.EXCEPTION));
        assertTrue(ShipmentStatus.OUT_FOR_DELIVERY.canTransitionTo(ShipmentStatus.EXCEPTION));
    }

    @Test
    void handlesTransitionsFromException() {
        // Can move to any status except CREATED
        assertFalse(ShipmentStatus.EXCEPTION.canTransitionTo(ShipmentStatus.CREATED));

        assertTrue(ShipmentStatus.EXCEPTION.canTransitionTo(ShipmentStatus.PICKED_UP));
        assertTrue(ShipmentStatus.EXCEPTION.canTransitionTo(ShipmentStatus.IN_TRANSIT));
        assertTrue(ShipmentStatus.EXCEPTION.canTransitionTo(ShipmentStatus.OUT_FOR_DELIVERY));
        assertTrue(ShipmentStatus.EXCEPTION.canTransitionTo(ShipmentStatus.DELIVERED));
    }

    @Test
    void rejectsAllTransitionsAfterDelivery() {
        assertFalse(ShipmentStatus.DELIVERED.canTransitionTo(ShipmentStatus.CREATED));
        assertFalse(ShipmentStatus.DELIVERED.canTransitionTo(ShipmentStatus.PICKED_UP));
        assertFalse(ShipmentStatus.DELIVERED.canTransitionTo(ShipmentStatus.IN_TRANSIT));
        assertFalse(ShipmentStatus.DELIVERED.canTransitionTo(ShipmentStatus.OUT_FOR_DELIVERY));
        assertFalse(ShipmentStatus.DELIVERED.canTransitionTo(ShipmentStatus.DELIVERED));
        assertFalse(ShipmentStatus.DELIVERED.canTransitionTo(ShipmentStatus.EXCEPTION));
    }

    @Test
    void rejectsNullTransition() {
        assertFalse(ShipmentStatus.CREATED.canTransitionTo(null));
        assertFalse(ShipmentStatus.IN_TRANSIT.canTransitionTo(null));
    }
}

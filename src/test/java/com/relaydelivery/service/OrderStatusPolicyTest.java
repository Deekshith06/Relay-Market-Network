package com.relaydelivery.service;

import com.relaydelivery.model.Models.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderStatusPolicyTest {
    @Test void allowsOnlyTheCentralLifecyclePolicy() {
        assertTrue(OrderService.canTransition(OrderStatus.SCHEDULED, OrderStatus.PLACED));
        assertTrue(OrderService.canTransition(OrderStatus.SCHEDULED, OrderStatus.CANCELLED));
        assertTrue(OrderService.canTransition(OrderStatus.PLACED, OrderStatus.CONFIRMED));
        assertTrue(OrderService.canTransition(OrderStatus.PLACED, OrderStatus.CANCELLED));
        assertTrue(OrderService.canTransition(OrderStatus.CONFIRMED, OrderStatus.PACKED));
        assertTrue(OrderService.canTransition(OrderStatus.PACKED, OrderStatus.ASSIGNED));
        assertTrue(OrderService.canTransition(OrderStatus.ASSIGNED, OrderStatus.PICKED_UP));
        assertTrue(OrderService.canTransition(OrderStatus.ASSIGNED, OrderStatus.CANCELLED));
        assertTrue(OrderService.canTransition(OrderStatus.PICKED_UP, OrderStatus.OUT_FOR_DELIVERY));
        assertTrue(OrderService.canTransition(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERY_VERIFICATION));
        assertTrue(OrderService.canTransition(OrderStatus.DELIVERY_VERIFICATION, OrderStatus.DELIVERED));

        assertFalse(OrderService.canTransition(OrderStatus.PICKED_UP, OrderStatus.CANCELLED));
        assertFalse(OrderService.canTransition(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CANCELLED));
        assertFalse(OrderService.canTransition(OrderStatus.DELIVERED, OrderStatus.DELIVERED));
        assertFalse(OrderService.canTransition(OrderStatus.ASSIGNED, OrderStatus.DELIVERED));
        assertFalse(OrderService.canTransition(OrderStatus.SCHEDULED, OrderStatus.ASSIGNED));
        assertFalse(OrderService.canTransition(OrderStatus.PLACED, OrderStatus.PACKED));
        assertFalse(OrderService.canTransition(OrderStatus.PACKED, OrderStatus.PICKED_UP));
    }
}

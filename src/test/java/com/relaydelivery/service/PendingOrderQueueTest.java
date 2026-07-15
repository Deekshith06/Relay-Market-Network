package com.relaydelivery.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PendingOrderQueueTest {
    @Test void preservesFifoAndRejectsDuplicates() {
        PendingOrderQueue queue = new PendingOrderQueue();
        queue.restore(List.of(7L, 8L, 7L));
        assertEquals(2, queue.size());
        assertEquals(7, queue.peek().orElseThrow());
        assertEquals(7, queue.poll().orElseThrow());
        assertEquals(8, queue.poll().orElseThrow());
        assertTrue(queue.poll().isEmpty());
    }
}

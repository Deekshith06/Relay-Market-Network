package com.relaydelivery.service;

import java.util.Collection;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class PendingOrderQueue {
    // Queue preserves FIFO fairness; the companion hash set prevents duplicate entries in O(1).
    private final Queue<Long> queue = new ConcurrentLinkedQueue<>();
    private final Set<Long> membership = ConcurrentHashMap.newKeySet();

    public void restore(Collection<Long> orderIds) { orderIds.forEach(this::offer); }
    public void reset(Collection<Long> orderIds) {
        queue.clear(); membership.clear(); restore(orderIds);
    }
    public void offer(long orderId) { if (membership.add(orderId)) queue.offer(orderId); }

    /** O(1) average time and O(n) total queue space. */
    public OptionalLong poll() {
        Long id = queue.poll();
        if (id == null) return OptionalLong.empty();
        membership.remove(id);
        return OptionalLong.of(id);
    }

    public OptionalLong peek() {
        Long id = queue.peek();
        return id == null ? OptionalLong.empty() : OptionalLong.of(id);
    }

    public void remove(long orderId) { if (membership.remove(orderId)) queue.remove(orderId); }
    public int size() { return queue.size(); }
}

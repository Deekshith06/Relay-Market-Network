package com.relaydelivery.service;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

public final class LoginRateLimiter {
    private record Bucket(int failures, long startedAt) {}
    private static final int MAX_KEYS = 10_000;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final long windowMillis;
    private final Clock clock;

    public LoginRateLimiter(Duration window) { this(window, Clock.systemUTC()); }
    LoginRateLimiter(Duration window, Clock clock) {
        this.windowMillis = window.toMillis(); this.clock = clock;
    }

    public synchronized OptionalLong retryAfter(String key, int limit) {
        long now = clock.millis();
        Bucket bucket = current(key, now);
        if (bucket == null || bucket.failures() < limit) return OptionalLong.empty();
        return OptionalLong.of(Math.max(1, (bucket.startedAt() + windowMillis - now + 999) / 1000));
    }

    public synchronized void failed(String key) {
        long now = clock.millis();
        Bucket bucket = current(key, now);
        buckets.put(key, bucket == null ? new Bucket(1, now) : new Bucket(bucket.failures() + 1, bucket.startedAt()));
        if (buckets.size() > MAX_KEYS) purge(now);
    }

    public synchronized void succeeded(String key) { buckets.remove(key); }

    private Bucket current(String key, long now) {
        Bucket bucket = buckets.get(key);
        if (bucket != null && now - bucket.startedAt() >= windowMillis) {
            buckets.remove(key); return null;
        }
        return bucket;
    }

    private void purge(long now) {
        buckets.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= windowMillis);
        while (buckets.size() > MAX_KEYS) buckets.remove(buckets.keySet().iterator().next());
    }
}

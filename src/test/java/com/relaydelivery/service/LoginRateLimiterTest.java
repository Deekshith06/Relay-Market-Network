package com.relaydelivery.service;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {
    @Test void blocksAtLimitAndResetsAfterWindowOrSuccess() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(Duration.ofMinutes(15), clock);
        for (int i = 0; i < 5; i++) limiter.failed("account:test");
        assertTrue(limiter.retryAfter("account:test", 5).isPresent());
        limiter.succeeded("account:test");
        assertTrue(limiter.retryAfter("account:test", 5).isEmpty());
        for (int i = 0; i < 5; i++) limiter.failed("account:test");
        clock.advance(Duration.ofMinutes(15));
        assertTrue(limiter.retryAfter("account:test", 5).isEmpty());
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }
}

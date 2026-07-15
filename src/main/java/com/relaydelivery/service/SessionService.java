package com.relaydelivery.service;

import com.relaydelivery.config.Database;
import com.relaydelivery.model.Models.User;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SessionService {
    private record Session(User user, Instant expiresAt) {}
    private static final SecureRandom RANDOM = new SecureRandom();
    // HashMap-style token lookup is O(1) average and avoids a database read per request.
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger created = new AtomicInteger();
    private final Duration lifetime = Duration.ofHours(Long.parseLong(Database.env("SESSION_HOURS", "24")));

    public String create(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(user, Instant.now().plus(lifetime)));
        if ((created.incrementAndGet() & 255) == 0)
            sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        return token;
    }

    public Optional<User> resolve(String token) {
        Session session = token == null ? null : sessions.get(token);
        if (session == null) return Optional.empty();
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.user());
    }

    public void revoke(String token) { if (token != null) sessions.remove(token); }
}

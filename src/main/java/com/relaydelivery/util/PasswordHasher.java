package com.relaydelivery.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class PasswordHasher {
    private static final int ROUNDS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(digest(salt, password));
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split(":", 2);
            byte[] salt = HexFormat.of().parseHex(parts[0]);
            byte[] expected = HexFormat.of().parseHex(parts[1]);
            return MessageDigest.isEqual(expected, digest(salt, password));
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    // Repeated salted SHA-256 deliberately raises the cost of an offline password guess.
    private static byte[] digest(byte[] salt, String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(salt);
            byte[] value = sha.digest(password.getBytes(StandardCharsets.UTF_8));
            for (int i = 1; i < ROUNDS; i++) {
                sha.reset();
                sha.update(salt);
                value = sha.digest(value);
            }
            return value;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

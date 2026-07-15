package com.relaydelivery.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {
    private final String url = env("DB_URL", "jdbc:postgresql://localhost:5432/relay_delivery");
    private final String user = env("DB_USER", "postgres");
    private final String password = env("DB_PASSWORD", "postgres");

    public Database() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("PostgreSQL JDBC driver is missing", e);
        }
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public void verify() throws SQLException {
        try (Connection ignored = connection()) {
            // Opening a connection fails fast before the HTTP server accepts traffic.
        }
    }

    public static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

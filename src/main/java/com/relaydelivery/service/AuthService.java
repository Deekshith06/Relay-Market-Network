package com.relaydelivery.service;

import com.relaydelivery.config.Database;
import com.relaydelivery.dao.AgentDao;
import com.relaydelivery.dao.UserDao;
import com.relaydelivery.model.Models.Role;
import com.relaydelivery.model.Models.User;
import com.relaydelivery.util.ApiException;
import com.relaydelivery.util.PasswordHasher;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class AuthService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final String DUMMY_HASH =
        "00112233445566778899aabbccddeeff:a1860f53ee39de241664b807307fb9c431e8e7a8d392e25c21434ad4b6a135c8";
    private final Database db;
    private final UserDao users;
    private final AgentDao agents;
    private final SessionService sessions;

    public AuthService(Database db, UserDao users, AgentDao agents, SessionService sessions) {
        this.db = db; this.users = users; this.agents = agents; this.sessions = sessions;
    }

    public Map<String, Object> login(String email, String password) throws SQLException {
        String normalized = normalizeEmail(email);
        User user = users.findByEmail(normalized).orElse(null);
        // The dummy digest keeps unknown-account and wrong-password work comparable.
        boolean valid = PasswordHasher.verify(password == null ? "" : password,
            user == null ? DUMMY_HASH : user.passwordHash());
        if (user == null || !valid)
            throw new ApiException(401, "Email or password is incorrect");
        return sessionPayload(user);
    }

    public Map<String, Object> register(String name, String email, String password,
                                        String roleValue, String vehicleType) throws SQLException {
        String normalizedName = clean(name, "Name", 2, 100);
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        Role role;
        try { role = Role.valueOf(roleValue == null ? "CUSTOMER" : roleValue.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw ApiException.badRequest("Role must be CUSTOMER or AGENT"); }
        if (role == Role.ADMIN) throw ApiException.forbidden();
        String vehicle = vehicleType == null ? "BICYCLE" : vehicleType.toUpperCase(Locale.ROOT);
        if (role == Role.AGENT && !java.util.Set.of("BICYCLE", "MOTORCYCLE", "SCOOTER", "CAR", "VAN", "MINI TRUCK").contains(vehicle))
            throw ApiException.badRequest("Choose a supported vehicle type");

        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                User user = users.create(c, normalizedName, normalizedEmail, PasswordHasher.hash(password), role);
                if (role == Role.AGENT) agents.create(c, user.id(), vehicle);
                c.commit();
                return sessionPayload(user);
            } catch (SQLException e) {
                c.rollback();
                if ("23505".equals(e.getSQLState())) throw ApiException.conflict("An account already uses this email");
                throw e;
            }
        }
    }

    private Map<String, Object> sessionPayload(User user) {
        return Map.of("token", sessions.create(user), "user",
            Map.of("id", user.id(), "name", user.name(), "email", user.email(), "role", user.role()));
    }

    private static String normalizeEmail(String email) {
        String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 180 || !EMAIL.matcher(value).matches())
            throw ApiException.badRequest("Enter a valid email address");
        return value;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128 ||
            !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*"))
            throw ApiException.badRequest("Password must be 8-128 characters with a letter and number");
    }

    private static String clean(String value, String label, int min, int max) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() < min || clean.length() > max)
            throw ApiException.badRequest(label + " must be " + min + "-" + max + " characters");
        return clean;
    }
}

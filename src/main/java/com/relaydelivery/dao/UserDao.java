package com.relaydelivery.dao;

import com.relaydelivery.config.Database;
import com.relaydelivery.model.Models.Role;
import com.relaydelivery.model.Models.User;

import java.sql.*;
import java.util.Optional;

public final class UserDao {
    private final Database db;

    public UserDao(Database db) { this.db = db; }

    public Optional<User> findByEmail(String email) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id,name,email,phone,password_hash,role FROM users WHERE email=?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public User create(Connection c, String name, String email, String passwordHash, Role role)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO users(name,email,password_hash,role) VALUES(?,?,?,?) " +
            "RETURNING id,name,email,phone,password_hash,role")) {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            ps.setString(4, role.name());
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return map(rs); }
        }
    }

    private static User map(ResultSet rs) throws SQLException {
        return new User(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
            rs.getString("phone"), rs.getString("password_hash"), Role.valueOf(rs.getString("role")));
    }
}

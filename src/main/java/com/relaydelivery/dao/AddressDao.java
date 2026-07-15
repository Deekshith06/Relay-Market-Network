package com.relaydelivery.dao;

import com.relaydelivery.config.Database;
import com.relaydelivery.model.Models.Address;
import com.relaydelivery.model.Models.AddressInput;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class AddressDao {
    private final Database db;
    public AddressDao(Database db) { this.db = db; }

    public List<Address> list(long customerId) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM addresses WHERE customer_id=? ORDER BY is_default DESC,created_at")) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Address> values = new ArrayList<>(); while (rs.next()) values.add(map(rs)); return values;
            }
        }
    }

    public Address save(long customerId, Long id, AddressInput value) throws SQLException {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                if (Boolean.TRUE.equals(value.isDefault())) clearDefault(c, customerId);
                String sql = id == null
                    ? "INSERT INTO addresses(customer_id,label,address_line,landmark,instructions,latitude,longitude,zone_id,is_default) " +
                      "VALUES(?,?,?,?,?,?,?,?,?) RETURNING *"
                    : "UPDATE addresses SET label=?,address_line=?,landmark=?,instructions=?,latitude=?,longitude=?,zone_id=?," +
                      "is_default=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND customer_id=? RETURNING *";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int i=1;
                    if (id == null) ps.setLong(i++, customerId);
                    ps.setString(i++,value.label()); ps.setString(i++,value.addressLine()); ps.setString(i++,value.landmark());
                    ps.setString(i++,value.instructions()); ps.setDouble(i++,value.latitude()); ps.setDouble(i++,value.longitude());
                    ps.setLong(i++,value.zoneId()); ps.setBoolean(i++,Boolean.TRUE.equals(value.isDefault()));
                    if (id != null) { ps.setLong(i++,id); ps.setLong(i,customerId); }
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return null;
                        Address address=map(rs); c.commit(); return address;
                    }
                }
            } catch (RuntimeException|SQLException e) { c.rollback(); throw e; }
        }
    }

    public boolean delete(long customerId, long id) throws SQLException {
        try (Connection c=db.connection(); PreparedStatement ps=c.prepareStatement(
            "DELETE FROM addresses WHERE id=? AND customer_id=?")) {
            ps.setLong(1,id); ps.setLong(2,customerId); return ps.executeUpdate()==1;
        }
    }

    private static void clearDefault(Connection c,long customerId) throws SQLException {
        try (PreparedStatement ps=c.prepareStatement("UPDATE addresses SET is_default=FALSE WHERE customer_id=?")) {
            ps.setLong(1,customerId); ps.executeUpdate();
        }
    }

    private static Address map(ResultSet rs) throws SQLException {
        return new Address(rs.getLong("id"),rs.getLong("customer_id"),rs.getString("label"),
            rs.getString("address_line"),rs.getString("landmark"),rs.getString("instructions"),
            rs.getDouble("latitude"),rs.getDouble("longitude"),rs.getLong("zone_id"),rs.getBoolean("is_default"),
            rs.getTimestamp("created_at").toInstant().toString());
    }
}

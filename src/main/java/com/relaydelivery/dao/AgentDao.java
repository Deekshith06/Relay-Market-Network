package com.relaydelivery.dao;

import com.relaydelivery.config.Database;
import com.relaydelivery.model.Models.*;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AgentDao {
    private static final String SELECT =
        "SELECT a.id,a.user_id,u.name,u.phone,a.current_lat,a.current_lng,a.status,a.vehicle_type_id,"+
        "v.name vehicle_type,v.maximum_capacity_kg,a.current_load_kg,v.maximum_active_orders,a.current_active_orders,"+
        "v.supports_fragile,v.supports_temperature_control,v.handling_equipment,a.rating "+
        "FROM agents a JOIN users u ON u.id=a.user_id JOIN vehicle_types v ON v.id=a.vehicle_type_id ";
    private final Database db;
    public AgentDao(Database db){this.db=db;}

    public Agent create(Connection c,long userId,String vehicleType)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "INSERT INTO agents(user_id,vehicle_type_id,current_lat,current_lng,status) "+
            "VALUES(?,(SELECT id FROM vehicle_types WHERE UPPER(name)=UPPER(?)),12.971600,77.594600,'OFFLINE') RETURNING id")){
            ps.setLong(1,userId);ps.setString(2,vehicleType);ps.executeQuery().close();
        }
        return findByUser(c,userId).orElseThrow();
    }

    public List<Agent> listAvailable()throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            SELECT+"WHERE a.status IN ('AVAILABLE','BUSY') AND a.current_active_orders<v.maximum_active_orders "+
            "AND a.current_load_kg<v.maximum_capacity_kg ORDER BY a.id");ResultSet rs=ps.executeQuery()){
            return list(rs);
        }
    }

    public List<Agent> capacityCandidates(Connection c,Order order)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            SELECT+"WHERE a.status IN ('AVAILABLE','BUSY') AND a.current_active_orders<v.maximum_active_orders "+
            "AND a.current_load_kg+?<=v.maximum_capacity_kg AND (?=FALSE OR v.supports_fragile=TRUE) "+
            "AND (?=FALSE OR v.supports_temperature_control=TRUE) "+
            "AND EXISTS(SELECT 1 FROM agent_zones az WHERE az.agent_id=a.id AND az.zone_id=?) ORDER BY a.id")){
            ps.setBigDecimal(1,order.totalWeightKg());ps.setBoolean(2,order.fragile());
            ps.setBoolean(3,order.temperatureControlled());ps.setLong(4,order.pickupZoneId());
            try(ResultSet rs=ps.executeQuery()){return list(rs);}
        }
    }

    public boolean claimCapacity(Connection c,long id,BigDecimal weight)throws SQLException{
        // Atomic counters serialize claims across JVMs without locking the complete candidate set.
        try(PreparedStatement ps=c.prepareStatement(
            "UPDATE agents a SET current_active_orders=current_active_orders+1,current_load_kg=current_load_kg+?,status='BUSY' "+
            "FROM vehicle_types v WHERE a.id=? AND v.id=a.vehicle_type_id AND a.status IN ('AVAILABLE','BUSY') "+
            "AND a.current_active_orders<v.maximum_active_orders AND a.current_load_kg+?<=v.maximum_capacity_kg")){
            ps.setBigDecimal(1,weight);ps.setLong(2,id);ps.setBigDecimal(3,weight);return ps.executeUpdate()==1;
        }
    }
    public boolean supportsZone(Connection c,long agentId,long zoneId)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM agent_zones WHERE agent_id=? AND zone_id=?")){
            ps.setLong(1,agentId);ps.setLong(2,zoneId);try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }
    }

    public void syncLoad(Connection c,long id)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "UPDATE agents a SET current_active_orders=s.active_count,current_load_kg=s.active_weight,"+
            "status=CASE WHEN s.active_count=0 THEN 'AVAILABLE' ELSE 'BUSY' END FROM ("+
            "SELECT COUNT(*)::integer active_count,COALESCE(SUM(total_weight_kg),0) active_weight FROM orders "+
            "WHERE agent_id=? AND status NOT IN ('DELIVERED','CANCELLED')) s WHERE a.id=?")){
            ps.setLong(1,id);ps.setLong(2,id);ps.executeUpdate();
        }
    }

    public Optional<Agent> findByUser(long userId)throws SQLException{
        try(Connection c=db.connection()){return findByUser(c,userId);}
    }
    public Optional<Agent> findByUser(Connection c,long userId)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(SELECT+"WHERE a.user_id=?")){
            ps.setLong(1,userId);try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(map(rs)):Optional.empty();}
        }
    }
    public Optional<Agent> lockById(Connection c,long id)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(SELECT+"WHERE a.id=? FOR UPDATE OF a")){
            ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(map(rs)):Optional.empty();}
        }
    }
    public Optional<Agent> findById(long id)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(SELECT+"WHERE a.id=?")){
            ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(map(rs)):Optional.empty();}
        }
    }

    public void setAvailability(long userId,AgentStatus status,Double lat,Double lng)throws SQLException{
        String sql="UPDATE agents SET status=?,current_lat=COALESCE(?,current_lat),current_lng=COALESCE(?,current_lng) "+
            "WHERE user_id=? AND (current_active_orders=0 OR ?<>'OFFLINE')";
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(sql)){
            ps.setString(1,status.name());if(lat==null)ps.setNull(2,Types.DOUBLE);else ps.setDouble(2,lat);
            if(lng==null)ps.setNull(3,Types.DOUBLE);else ps.setDouble(3,lng);ps.setLong(4,userId);ps.setString(5,status.name());
            if(ps.executeUpdate()==0)throw new SQLException("Agent is busy or missing");
        }
    }

    public void updateCoordinates(long agentId,double lat,double lng)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "UPDATE agents SET current_lat=?,current_lng=? WHERE id=?")){
            ps.setDouble(1,lat);ps.setDouble(2,lng);ps.setLong(3,agentId);ps.executeUpdate();
        }
    }

    private static List<Agent> list(ResultSet rs)throws SQLException{
        List<Agent> values=new ArrayList<>();while(rs.next())values.add(map(rs));return values;
    }
    private static Agent map(ResultSet rs)throws SQLException{
        return new Agent(rs.getLong("id"),rs.getLong("user_id"),rs.getString("name"),rs.getString("phone"),
            rs.getDouble("current_lat"),rs.getDouble("current_lng"),AgentStatus.valueOf(rs.getString("status")),
            rs.getLong("vehicle_type_id"),rs.getString("vehicle_type"),rs.getBigDecimal("maximum_capacity_kg"),
            rs.getBigDecimal("current_load_kg"),rs.getInt("maximum_active_orders"),rs.getInt("current_active_orders"),
            rs.getBoolean("supports_fragile"),rs.getBoolean("supports_temperature_control"),rs.getString("handling_equipment"),
            rs.getBigDecimal("rating"));
    }
}

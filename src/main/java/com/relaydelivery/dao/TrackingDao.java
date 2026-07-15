package com.relaydelivery.dao;

import com.relaydelivery.config.Database;
import com.relaydelivery.model.Models.AgentLocation;
import com.relaydelivery.model.Models.LocationUpdate;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class TrackingDao {
    private final Database db;
    public TrackingDao(Database db){this.db=db;}

    public Optional<AgentLocation> currentForAgent(long agentId)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement("SELECT * FROM agent_location WHERE agent_id=?")){
            ps.setLong(1,agentId);try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(map(rs)):Optional.empty();}
        }
    }

    public Optional<AgentLocation> currentForOrder(long orderId)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement("SELECT * FROM agent_location WHERE order_id=?")){
            ps.setLong(1,orderId);try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(map(rs)):Optional.empty();}
        }
    }

    public void save(long agentId,long orderId,LocationUpdate value,Instant recordedAt)throws SQLException{
        try(Connection c=db.connection()){
            c.setAutoCommit(false);
            try{
                try(PreparedStatement ps=c.prepareStatement(
                    "INSERT INTO agent_location(agent_id,order_id,latitude,longitude,accuracy_meters,heading,speed_mps,recorded_at) "+
                    "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(agent_id) DO UPDATE SET order_id=EXCLUDED.order_id,latitude=EXCLUDED.latitude,"+
                    "longitude=EXCLUDED.longitude,accuracy_meters=EXCLUDED.accuracy_meters,heading=EXCLUDED.heading,speed_mps=EXCLUDED.speed_mps,"+
                    "recorded_at=EXCLUDED.recorded_at,received_at=CURRENT_TIMESTAMP")){
                    bindLocation(ps,agentId,orderId,value,recordedAt);ps.executeUpdate();
                }
                try(PreparedStatement ps=c.prepareStatement(
                    "INSERT INTO agent_location_history(agent_id,order_id,latitude,longitude,accuracy_meters,heading,speed_mps,recorded_at) "+
                    "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(agent_id,recorded_at) DO NOTHING")){
                    bindLocation(ps,agentId,orderId,value,recordedAt);ps.executeUpdate();
                }
                c.commit();
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }
    }

    public void stop(long agentId)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement("DELETE FROM agent_location WHERE agent_id=?")){
            ps.setLong(1,agentId);ps.executeUpdate();
        }
    }

    public void stop(Connection c,long agentId)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("DELETE FROM agent_location WHERE agent_id=?")){
            ps.setLong(1,agentId);ps.executeUpdate();
        }
    }

    public int purgeHistory(int days)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "DELETE FROM agent_location_history WHERE received_at<CURRENT_TIMESTAMP-(?*INTERVAL '1 day')")){
            ps.setInt(1,days);return ps.executeUpdate();
        }
    }

    public List<Map<String,Object>> timeline(long orderId)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "SELECT status,timestamp FROM order_status_log WHERE order_id=? ORDER BY timestamp")){
            ps.setLong(1,orderId);try(ResultSet rs=ps.executeQuery()){
                List<Map<String,Object>> values=new ArrayList<>();while(rs.next())values.add(Map.of(
                    "status",rs.getString("status"),"timestamp",rs.getTimestamp("timestamp").toInstant().toString()));return values;
            }
        }
    }

    public List<Map<String,Object>> liveDeliveries()throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "SELECT l.order_id,l.latitude,l.longitude,l.accuracy_meters,l.recorded_at,u.name agent_name,o.status,"+
            "l.recorded_at<CURRENT_TIMESTAMP-INTERVAL '30 seconds' stale "+
            "FROM agent_location l JOIN agents a ON a.id=l.agent_id JOIN users u ON u.id=a.user_id "+
            "JOIN orders o ON o.id=l.order_id ORDER BY l.recorded_at DESC");ResultSet rs=ps.executeQuery()){
            List<Map<String,Object>> values=new ArrayList<>();while(rs.next()){
                Map<String,Object> row=new LinkedHashMap<>();row.put("orderId",rs.getLong("order_id"));row.put("agentName",rs.getString("agent_name"));
                row.put("latitude",rs.getDouble("latitude"));row.put("longitude",rs.getDouble("longitude"));
                row.put("accuracyMeters",rs.getBigDecimal("accuracy_meters"));row.put("status",rs.getString("status"));
                row.put("recordedAt",rs.getTimestamp("recorded_at").toInstant().toString());row.put("stale",rs.getBoolean("stale"));values.add(row);
            }return values;
        }
    }

    private static void bindLocation(PreparedStatement ps,long agentId,long orderId,LocationUpdate v,Instant recordedAt)throws SQLException{
        ps.setLong(1,agentId);ps.setLong(2,orderId);ps.setDouble(3,v.latitude());ps.setDouble(4,v.longitude());
        ps.setDouble(5,v.accuracyMeters());if(v.heading()==null)ps.setNull(6,Types.DECIMAL);else ps.setDouble(6,v.heading());
        if(v.speedMetersPerSecond()==null)ps.setNull(7,Types.DECIMAL);else ps.setDouble(7,v.speedMetersPerSecond());
        ps.setTimestamp(8,Timestamp.from(recordedAt));
    }

    private static AgentLocation map(ResultSet rs)throws SQLException{
        double heading=rs.getDouble("heading");Double h=rs.wasNull()?null:heading;
        double speed=rs.getDouble("speed_mps");Double s=rs.wasNull()?null:speed;
        return new AgentLocation(rs.getLong("agent_id"),rs.getLong("order_id"),rs.getDouble("latitude"),rs.getDouble("longitude"),
            rs.getDouble("accuracy_meters"),h,s,rs.getTimestamp("recorded_at").toInstant().toString(),rs.getTimestamp("received_at").toInstant().toString());
    }
}

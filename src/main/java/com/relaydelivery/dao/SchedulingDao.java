package com.relaydelivery.dao;

import com.relaydelivery.config.Database;

import java.sql.*;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public final class SchedulingDao {
    public record SlotDefinition(long id,String name,LocalTime start,LocalTime end,
                                 int capacity,int preparationMinutes){}
    private final Database db;
    public SchedulingDao(Database db){this.db=db;}

    public List<SlotDefinition> definitions(int postgresDayOfWeek)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "SELECT id,name,start_time,end_time,capacity,preparation_minutes FROM delivery_time_slots "+
            "WHERE day_of_week=? AND active=TRUE ORDER BY start_time")){
            ps.setInt(1,postgresDayOfWeek);try(ResultSet rs=ps.executeQuery()){
                List<SlotDefinition> values=new ArrayList<>();while(rs.next())values.add(new SlotDefinition(
                    rs.getLong("id"),rs.getString("name"),rs.getTime("start_time").toLocalTime(),rs.getTime("end_time").toLocalTime(),
                    rs.getInt("capacity"),rs.getInt("preparation_minutes")));return values;
            }
        }
    }

    public SlotDefinition lock(Connection c,long id)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "SELECT id,name,start_time,end_time,capacity,preparation_minutes FROM delivery_time_slots WHERE id=? AND active=TRUE FOR UPDATE")){
            ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){
                if(!rs.next())throw new SQLException("Delivery slot is no longer available","P0001");
                return new SlotDefinition(rs.getLong("id"),rs.getString("name"),rs.getTime("start_time").toLocalTime(),
                    rs.getTime("end_time").toLocalTime(),rs.getInt("capacity"),rs.getInt("preparation_minutes"));
            }
        }
    }
}

package com.relaydelivery.dao;

import com.relaydelivery.config.Database;
import com.relaydelivery.model.Models.ZoneEdge;

import java.sql.*;
import java.util.*;

public final class OperationsDao {
    private final Database db;
    public OperationsDao(Database db){this.db=db;}

    public List<ZoneEdge> routes()throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "SELECT from_zone_id,to_zone_id,distance FROM zone_routes");ResultSet rs=ps.executeQuery()){
            List<ZoneEdge> values=new ArrayList<>();while(rs.next())values.add(new ZoneEdge(rs.getLong(1),rs.getLong(2),rs.getDouble(3)));return values;
        }
    }

    public List<Map<String,Object>> zones()throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "SELECT id,name,center_lat,center_lng FROM zones ORDER BY name");ResultSet rs=ps.executeQuery()){
            List<Map<String,Object>> values=new ArrayList<>();while(rs.next())values.add(Map.of(
                "id",rs.getLong("id"),"name",rs.getString("name"),"latitude",rs.getBigDecimal("center_lat"),"longitude",rs.getBigDecimal("center_lng")));return values;
        }
    }

    public Map<String,Object> analytics()throws SQLException{
        Map<String,Object> data=new LinkedHashMap<>();
        try(Connection c=db.connection()){
            data.put("statusCounts",grouped(c,"SELECT status label,COUNT(*) value FROM orders GROUP BY status ORDER BY status"));
            data.put("busiestZones",grouped(c,"SELECT z.name label,COUNT(*) value FROM orders o JOIN zones z ON z.id=o.pickup_zone_id GROUP BY z.id,z.name ORDER BY value DESC LIMIT 5"));
            try(PreparedStatement ps=c.prepareStatement(
                "SELECT COUNT(*) FILTER(WHERE status='DELIVERED') delivered,COUNT(*) FILTER(WHERE status NOT IN ('DELIVERED','CANCELLED')) active,"+
                "COALESCE(ROUND(AVG(EXTRACT(EPOCH FROM(delivered_at-created_at))/60.0) FILTER(WHERE status='DELIVERED'),1),0) avg_minutes,"+
                "COALESCE(ROUND(AVG(total_weight_kg),2),0) avg_weight FROM orders");ResultSet rs=ps.executeQuery()){
                rs.next();data.put("delivered",rs.getLong("delivered"));data.put("active",rs.getLong("active"));
                data.put("avgMinutes",rs.getBigDecimal("avg_minutes"));data.put("avgWeightKg",rs.getBigDecimal("avg_weight"));
            }
            try(PreparedStatement ps=c.prepareStatement(
                "SELECT agent_id,agent_name,vehicle_type,status,rating,completed_deliveries,COALESCE(avg_delivery_minutes,0) avg_delivery_minutes "+
                "FROM agent_performance_view ORDER BY completed_deliveries DESC,agent_name");ResultSet rs=ps.executeQuery()){
                List<Map<String,Object>> agents=new ArrayList<>();while(rs.next()){
                    Map<String,Object> row=new LinkedHashMap<>();row.put("id",rs.getLong("agent_id"));row.put("name",rs.getString("agent_name"));
                    row.put("vehicleType",rs.getString("vehicle_type"));row.put("status",rs.getString("status"));row.put("rating",rs.getBigDecimal("rating"));
                    row.put("completed",rs.getLong("completed_deliveries"));row.put("avgMinutes",rs.getBigDecimal("avg_delivery_minutes"));agents.add(row);
                }data.put("agents",agents);
            }
        }return data;
    }

    public Map<String,Object> pricingAnalytics()throws SQLException{
        Map<String,Object> data=new LinkedHashMap<>();
        try(Connection c=db.connection()){
            data.put("revenueByCategory",moneyGrouped(c,
                "SELECT c.name label,COALESCE(SUM(oi.line_subtotal),0) value FROM categories c LEFT JOIN products p ON p.category_id=c.id "+
                "LEFT JOIN order_items oi ON oi.product_id=p.id GROUP BY c.id,c.name ORDER BY value DESC"));
            data.put("ordersByCategory",grouped(c,
                "SELECT c.name label,COUNT(DISTINCT oi.order_id) value FROM categories c LEFT JOIN products p ON p.category_id=c.id "+
                "LEFT JOIN order_items oi ON oi.product_id=p.id GROUP BY c.id,c.name ORDER BY value DESC"));
            data.put("giftByOccasion",grouped(c,
                "SELECT occasion label,COUNT(*) value FROM gift_delivery_details GROUP BY occasion ORDER BY value DESC"));
            data.put("popularProducts",grouped(c,
                "SELECT product_name_snapshot label,SUM(quantity)::bigint value FROM order_items GROUP BY product_name_snapshot ORDER BY value DESC LIMIT 10"));
            try(PreparedStatement ps=c.prepareStatement(
                "SELECT COALESCE(ROUND(AVG(q.distance_km),2),0) avg_distance,"+
                "COUNT(*) FILTER(WHERE o.order_type='GIFT') gift_orders,COUNT(*) FILTER(WHERE o.scheduled_delivery_at IS NOT NULL) scheduled_orders,"+
                "COUNT(*) FILTER(WHERE o.scheduled_delivery_at IS NULL) immediate_orders,"+
                "COALESCE(ROUND(100.0*COUNT(*) FILTER(WHERE o.status='DELIVERED' AND o.delivered_at<=o.delivery_window_end)/"+
                "NULLIF(COUNT(*) FILTER(WHERE o.status='DELIVERED' AND o.delivery_window_end IS NOT NULL),0),1),0) on_time_percent "+
                "FROM orders o LEFT JOIN delivery_quotes q ON q.id=o.quote_id");ResultSet rs=ps.executeQuery()){
                rs.next();data.put("averageDistanceKm",rs.getBigDecimal("avg_distance"));data.put("giftOrders",rs.getLong("gift_orders"));
                data.put("scheduledOrders",rs.getLong("scheduled_orders"));data.put("immediateOrders",rs.getLong("immediate_orders"));
                data.put("onTimeScheduledPercent",rs.getBigDecimal("on_time_percent"));
            }
        }return data;
    }

    private static List<Map<String,Object>> grouped(Connection c,String sql)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(sql);ResultSet rs=ps.executeQuery()){
            List<Map<String,Object>> values=new ArrayList<>();while(rs.next())values.add(Map.of("label",rs.getString(1),"value",rs.getLong(2)));return values;
        }
    }
    private static List<Map<String,Object>> moneyGrouped(Connection c,String sql)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(sql);ResultSet rs=ps.executeQuery()){
            List<Map<String,Object>> values=new ArrayList<>();while(rs.next())values.add(Map.of("label",rs.getString(1),"value",rs.getBigDecimal(2)));return values;
        }
    }
}

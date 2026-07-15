package com.relaydelivery.dao;

import com.relaydelivery.model.Models.PricedItem;
import com.relaydelivery.util.ApiException;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.List;

public final class InventoryDao {
    public void reserve(Connection c,long orderId,List<PricedItem> items,List<Long> orderItemIds,
                        boolean scheduled,Instant expiresAt)throws SQLException{
        for(int index=0;index<items.size();index++){
            PricedItem item=items.get(index);
            if(!decrement(c,item)) throw ApiException.conflict(item.productName()+" is no longer available in the requested quantity");
            try(PreparedStatement ps=c.prepareStatement(
                "INSERT INTO inventory_reservations(order_id,order_item_id,product_id,variant_id,reserved_quantity,status,expires_at) "+
                "VALUES(?,?,?,?,?,?,?)")){
                ps.setLong(1,orderId);ps.setLong(2,orderItemIds.get(index));ps.setLong(3,item.productId());
                if(item.variantId()==null)ps.setNull(4,Types.BIGINT);else ps.setLong(4,item.variantId());
                ps.setBigDecimal(5,item.stockDemand());ps.setString(6,scheduled?"RESERVED":"COMMITTED");
                if(expiresAt==null)ps.setNull(7,Types.TIMESTAMP_WITH_TIMEZONE);else ps.setTimestamp(7,Timestamp.from(expiresAt));
                ps.executeUpdate();
            }
        }
    }

    public void release(Connection c,long orderId)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "SELECT id,product_id,variant_id,reserved_quantity FROM inventory_reservations "+
            "WHERE order_id=? AND status IN ('RESERVED','COMMITTED') FOR UPDATE")){
            ps.setLong(1,orderId);
            try(ResultSet rs=ps.executeQuery()){
                while(rs.next()){
                    long variant=rs.getLong("variant_id");Long variantId=rs.wasNull()?null:variant;
                    increment(c,rs.getLong("product_id"),variantId,rs.getBigDecimal("reserved_quantity"));
                    try(PreparedStatement update=c.prepareStatement(
                        "UPDATE inventory_reservations SET status='RELEASED',released_at=CURRENT_TIMESTAMP WHERE id=?")){
                        update.setLong(1,rs.getLong("id"));update.executeUpdate();
                    }
                }
            }
        }
    }

    public void commitScheduled(Connection c,long orderId)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "UPDATE inventory_reservations SET status='COMMITTED',expires_at=NULL WHERE order_id=? AND status='RESERVED'")){
            ps.setLong(1,orderId);ps.executeUpdate();
        }
    }

    private static boolean decrement(Connection c,PricedItem item)throws SQLException{
        String sql=item.variantId()==null
            ? "UPDATE products SET stock_quantity=stock_quantity-?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND active=TRUE AND stock_quantity>=?"
            : "UPDATE product_variants SET stock_quantity=stock_quantity-? WHERE id=? AND product_id=? AND active=TRUE AND stock_quantity>=?";
        try(PreparedStatement ps=c.prepareStatement(sql)){
            ps.setBigDecimal(1,item.stockDemand());ps.setLong(2,item.variantId()==null?item.productId():item.variantId());
            if(item.variantId()==null)ps.setBigDecimal(3,item.stockDemand());
            else{ps.setLong(3,item.productId());ps.setBigDecimal(4,item.stockDemand());}
            return ps.executeUpdate()==1;
        }
    }

    private static void increment(Connection c,long productId,Long variantId,BigDecimal amount)throws SQLException{
        String sql=variantId==null?"UPDATE products SET stock_quantity=stock_quantity+?,updated_at=CURRENT_TIMESTAMP WHERE id=?"
            :"UPDATE product_variants SET stock_quantity=stock_quantity+? WHERE id=? AND product_id=?";
        try(PreparedStatement ps=c.prepareStatement(sql)){
            ps.setBigDecimal(1,amount);ps.setLong(2,variantId==null?productId:variantId);
            if(variantId!=null)ps.setLong(3,productId);ps.executeUpdate();
        }
    }
}

package com.relaydelivery.dao;

import com.relaydelivery.config.Database;
import com.relaydelivery.model.Models.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class OrderDao {
    public record CreatedOrder(long id,List<Long> itemIds){}
    public record DeliveryCodeState(String hash,String ciphertext,Instant expiresAt,Instant verifiedAt,
                                    int failedAttempts,Instant blockedUntil){}
    public record MissingDeliveryCode(long orderId,Instant scheduledAt,Instant createdAt){}
    private static final String SELECT=
        "SELECT o.*,c.name customer_name,au.name agent_name,pz.name pickup_zone,dz.name drop_zone,"+
        "COALESCE(g.hide_price,FALSE) hide_price,g.occasion FROM orders o JOIN users c ON c.id=o.customer_id "+
        "LEFT JOIN agents a ON a.id=o.agent_id LEFT JOIN users au ON au.id=a.user_id "+
        "JOIN zones pz ON pz.id=o.pickup_zone_id JOIN zones dz ON dz.id=o.drop_zone_id "+
        "LEFT JOIN gift_delivery_details g ON g.order_id=o.id ";
    private final Database db;
    public OrderDao(Database db){this.db=db;}

    public CreatedOrder create(Connection c,long customerId,String idempotencyKey,QuoteSnapshot snapshot,
                               OrderStatus status,Instant scheduledAt,Instant releaseAt,
                               Instant windowStart,Instant windowEnd)throws SQLException{
        QuoteRequest request=snapshot.request();Quote quote=snapshot.quote();
        String packageName=snapshot.items().stream().limit(3).map(PricedItem::productName).reduce((a,b)->a+", "+b).orElse("Marketplace order");
        int itemCount=snapshot.items().stream().mapToInt(PricedItem::quantity).sum();
        boolean fragile=snapshot.items().stream().anyMatch(PricedItem::fragile);
        boolean temperature=snapshot.items().stream().anyMatch(PricedItem::temperatureControlled);
        boolean gift=request.giftOptions()!=null&&Boolean.TRUE.equals(request.giftOptions().enabled());
        long id;
        try(PreparedStatement ps=c.prepareStatement(
            "INSERT INTO orders(customer_id,quote_id,idempotency_key,pickup_address,drop_address,pickup_lat,pickup_lng,drop_lat,drop_lng,"+
            "pickup_zone_id,drop_zone_id,package_name,item_count,total_weight_kg,notes,status,order_type,priority,fragile,"+
            "temperature_controlled,price,scheduled_delivery_at,assignment_release_at,delivery_window_start,delivery_window_end,timezone) "+
            "VALUES(?,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id")){
            int i=1;ps.setLong(i++,customerId);ps.setString(i++,quote.quoteId());ps.setString(i++,idempotencyKey);
            ps.setString(i++,request.pickupLocation().address());ps.setString(i++,request.deliveryLocation().address());
            ps.setDouble(i++,request.pickupLocation().latitude());ps.setDouble(i++,request.pickupLocation().longitude());
            ps.setDouble(i++,request.deliveryLocation().latitude());ps.setDouble(i++,request.deliveryLocation().longitude());
            ps.setLong(i++,request.pickupLocation().zoneId());ps.setLong(i++,request.deliveryLocation().zoneId());
            ps.setString(i++,packageName);ps.setInt(i++,itemCount);ps.setBigDecimal(i++,quote.totalWeightKg());
            ps.setString(i++,request.deliveryLocation().instructions());ps.setString(i++,status.name());ps.setString(i++,gift?"GIFT":"NORMAL");
            ps.setString(i++,request.priority().name());ps.setBoolean(i++,fragile);ps.setBoolean(i++,temperature);ps.setBigDecimal(i++,quote.finalPayableAmount());
            instant(ps,i++,scheduledAt);instant(ps,i++,releaseAt);instant(ps,i++,windowStart);instant(ps,i++,windowEnd);
            ps.setString(i,request.timezone()==null||request.timezone().isBlank()?"Asia/Kolkata":request.timezone());
            try(ResultSet rs=ps.executeQuery()){rs.next();id=rs.getLong(1);}
        }
        List<Long> itemIds=new ArrayList<>();for(PricedItem item:snapshot.items())itemIds.add(insertItem(c,id,item));
        for(ChargeLine line:snapshot.lines())insertCharge(c,id,line);
        if(gift)insertGift(c,id,request.giftOptions(),scheduledAt,windowStart,windowEnd,request.timezone());
        log(c,id,status);return new CreatedOrder(id,itemIds);
    }

    public Optional<Order> find(long id)throws SQLException{try(Connection c=db.connection()){return find(c,id);}}
    public Optional<Order> find(Connection c,long id)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(SELECT+"WHERE o.id=?")){
            ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(map(rs)):Optional.empty();}
        }
    }
    public Optional<Order> lock(Connection c,long id)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(SELECT+"WHERE o.id=? FOR UPDATE OF o")){
            ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(map(rs)):Optional.empty();}
        }
    }
    public Optional<Order> findByIdempotency(long customerId,String key)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(SELECT+"WHERE o.customer_id=? AND o.idempotency_key=?")){
            ps.setLong(1,customerId);ps.setString(2,key);try(ResultSet rs=ps.executeQuery()){return rs.next()?Optional.of(map(rs)):Optional.empty();}
        }
    }

    public List<Order> list(User user)throws SQLException{
        String filter=switch(user.role()){
            case CUSTOMER->"WHERE o.customer_id=? ";case AGENT->"WHERE a.user_id=? ";case ADMIN->"";};
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(SELECT+filter+"ORDER BY o.created_at DESC LIMIT 200")){
            if(user.role()!=Role.ADMIN)ps.setLong(1,user.id());try(ResultSet rs=ps.executeQuery()){return list(rs);}
        }
    }

    public List<OrderItem> items(long orderId)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "SELECT * FROM order_items WHERE order_id=? ORDER BY id")){
            ps.setLong(1,orderId);try(ResultSet rs=ps.executeQuery()){
                List<OrderItem> values=new ArrayList<>();while(rs.next()){
                    long variant=rs.getLong("variant_id");Long variantId=rs.wasNull()?null:variant;
                    values.add(new OrderItem(rs.getLong("id"),rs.getLong("product_id"),
                        variantId,rs.getString("product_name_snapshot"),rs.getString("variant_label_snapshot"),
                        rs.getInt("quantity"),rs.getBigDecimal("selected_weight_kg"),rs.getBigDecimal("unit_price_snapshot"),
                        rs.getBigDecimal("price_per_kg_snapshot"),rs.getBigDecimal("unit_weight_snapshot"),rs.getBigDecimal("line_subtotal")));
                }return values;
            }
        }
    }

    public List<ChargeLine> charges(long orderId)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "SELECT charge_type,label,amount,sort_order FROM order_charge_lines WHERE order_id=? ORDER BY sort_order,id")){
            ps.setLong(1,orderId);try(ResultSet rs=ps.executeQuery()){
                List<ChargeLine> values=new ArrayList<>();while(rs.next())values.add(new ChargeLine(rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getInt(4)));return values;
            }
        }
    }

    public Optional<GiftDetails> gift(long orderId)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement("SELECT * FROM gift_delivery_details WHERE order_id=?")){
            ps.setLong(1,orderId);try(ResultSet rs=ps.executeQuery()){
                if(!rs.next())return Optional.empty();return Optional.of(new GiftDetails(rs.getString("recipient_name"),mask(rs.getString("recipient_phone")),
                    rs.getString("occasion"),rs.getString("gift_message"),rs.getString("sender_display_name"),rs.getBoolean("hide_sender"),
                    rs.getBoolean("hide_price"),rs.getString("wrapping_style"),rs.getString("card_style"),rs.getBoolean("surprise_delivery"),
                    rs.getBoolean("recipient_otp_required"),iso(rs,"scheduled_at"),iso(rs,"delivery_window_start"),iso(rs,"delivery_window_end"),
                    rs.getString("timezone"),rs.getString("delivery_instructions")));
            }
        }
    }

    public List<Long> pendingIds()throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "SELECT id FROM orders WHERE status='PACKED' AND agent_id IS NULL ORDER BY created_at,id");ResultSet rs=ps.executeQuery()){
            List<Long> ids=new ArrayList<>();while(rs.next())ids.add(rs.getLong(1));return ids;
        }
    }

    public boolean assign(Connection c,long orderId,long agentId)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("UPDATE orders SET agent_id=?,status='ASSIGNED',version=version+1 WHERE id=? AND status='PACKED' AND agent_id IS NULL")){
            ps.setLong(1,agentId);ps.setLong(2,orderId);if(ps.executeUpdate()==0)return false;
        }log(c,orderId,OrderStatus.ASSIGNED);return true;
    }

    public void reassign(Connection c,long orderId,long agentId)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("UPDATE orders SET agent_id=?,version=version+1 WHERE id=? AND status IN ('ASSIGNED','PICKED_UP')")){
            ps.setLong(1,agentId);ps.setLong(2,orderId);if(ps.executeUpdate()==0)throw new SQLException("Order cannot be reassigned");
        }
    }

    public void logAssignment(Connection c,long orderId,Long previousAgentId,long newAgentId,Long adminId,String action)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO order_assignment_audit(order_id,previous_agent_id,new_agent_id,admin_id,action) VALUES(?,?,?,?,?)")){
            ps.setLong(1,orderId);if(previousAgentId==null)ps.setNull(2,Types.BIGINT);else ps.setLong(2,previousAgentId);ps.setLong(3,newAgentId);
            if(adminId==null)ps.setNull(4,Types.BIGINT);else ps.setLong(4,adminId);ps.setString(5,action);ps.executeUpdate();
        }
    }

    public void updateStatus(Connection c,long id,OrderStatus status)throws SQLException{
        String sql="UPDATE orders SET status=?,version=version+1,delivered_at="+(status==OrderStatus.DELIVERED?"CURRENT_TIMESTAMP":"delivered_at")+" WHERE id=?";
        try(PreparedStatement ps=c.prepareStatement(sql)){ps.setString(1,status.name());ps.setLong(2,id);if(ps.executeUpdate()!=1)throw new SQLException("Order missing");}
        log(c,id,status);
    }

    public List<Long> lockDueScheduled(Connection c,int limit)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "SELECT id FROM orders WHERE status='SCHEDULED' AND assignment_release_at<=CURRENT_TIMESTAMP "+
            "ORDER BY assignment_release_at,id FOR UPDATE SKIP LOCKED LIMIT ?")){
            ps.setInt(1,limit);try(ResultSet rs=ps.executeQuery()){List<Long> ids=new ArrayList<>();while(rs.next())ids.add(rs.getLong(1));return ids;}
        }
    }

    public boolean releaseScheduled(Connection c,long id)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "UPDATE orders SET status='PLACED',version=version+1 WHERE id=? AND status='SCHEDULED' AND assignment_release_at<=CURRENT_TIMESTAMP")){
            ps.setLong(1,id);if(ps.executeUpdate()!=1)return false;
        }log(c,id,OrderStatus.PLACED);return true;
    }

    public List<Order> activeForAgent(long agentId)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            SELECT+"WHERE o.agent_id=? AND o.status IN ('ASSIGNED','PICKED_UP','OUT_FOR_DELIVERY','DELIVERY_VERIFICATION') ORDER BY o.created_at")){
            ps.setLong(1,agentId);try(ResultSet rs=ps.executeQuery()){return list(rs);}
        }
    }

    public int scheduledUsage(Instant start,Instant end)throws SQLException{
        try(Connection c=db.connection()){return scheduledUsage(c,start,end);}
    }

    public void lockDeliveryCodeNamespace(Connection c)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('relay-delivery-code'))")){ps.executeQuery().close();}
    }

    public boolean deliveryCodeFingerprintExists(Connection c,String fingerprint)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM orders WHERE delivery_code_fingerprint=?")){
            ps.setString(1,fingerprint);try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }
    }

    public void storeDeliveryCode(Connection c,long orderId,String hash,String ciphertext,String fingerprint,Instant expiresAt)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "UPDATE orders SET delivery_code_hash=?,delivery_code_ciphertext=?,delivery_code_fingerprint=?,delivery_code_expires_at=?,"+
            "delivery_code_failed_attempts=0,delivery_code_blocked_until=NULL WHERE id=? AND delivery_code_hash IS NULL AND delivery_code_verified_at IS NULL")){
            ps.setString(1,hash);ps.setString(2,ciphertext);ps.setString(3,fingerprint);instant(ps,4,expiresAt);ps.setLong(5,orderId);
            if(ps.executeUpdate()!=1)throw new SQLException("Delivery code could not be stored");
        }
    }

    public Optional<DeliveryCodeState> deliveryCode(Connection c,long orderId,boolean lock)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "SELECT delivery_code_hash,delivery_code_ciphertext,delivery_code_expires_at,delivery_code_verified_at,"+
            "delivery_code_failed_attempts,delivery_code_blocked_until FROM orders WHERE id=?"+(lock?" FOR UPDATE":""))){
            ps.setLong(1,orderId);try(ResultSet rs=ps.executeQuery()){if(!rs.next())return Optional.empty();
                return Optional.of(new DeliveryCodeState(rs.getString(1),rs.getString(2),toInstant(rs,3),toInstant(rs,4),rs.getInt(5),toInstant(rs,6)));}
        }
    }

    public Optional<DeliveryCodeState> deliveryCode(long orderId)throws SQLException{
        try(Connection c=db.connection()){return deliveryCode(c,orderId,false);}
    }

    public List<MissingDeliveryCode> missingDeliveryCodes(Connection c)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "SELECT id,scheduled_delivery_at,created_at FROM orders WHERE status NOT IN ('DELIVERED','CANCELLED') "+
            "AND delivery_code_hash IS NULL AND delivery_code_verified_at IS NULL ORDER BY id FOR UPDATE");ResultSet rs=ps.executeQuery()){
            List<MissingDeliveryCode> values=new ArrayList<>();while(rs.next())values.add(new MissingDeliveryCode(rs.getLong(1),toInstant(rs,2),toInstant(rs,3)));return values;
        }
    }

    public void recordDeliveryFailure(Connection c,Order order,User actor,int attempts,Instant blockedUntil,String ip)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "UPDATE orders SET delivery_code_failed_attempts=?,delivery_code_blocked_until=? WHERE id=?")){
            ps.setInt(1,attempts);instant(ps,2,blockedUntil);ps.setLong(3,order.id());ps.executeUpdate();
        }
        auditSecurity(c,order,actor,blockedUntil==null?"DELIVERY_CODE_FAILED":"DELIVERY_CODE_BLOCKED",order.status(),ip,"attempt "+attempts);
    }

    public void completeVerifiedDelivery(Connection c,Order order,User actor,String reason,String ip)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "UPDATE orders SET status='DELIVERED',version=version+1,delivered_at=CURRENT_TIMESTAMP,"+
            "delivery_code_verified_at=CURRENT_TIMESTAMP,delivery_code_verified_by=?,delivery_code_verification_role=?,"+
            "delivery_partner_id=agent_id,manual_delivery_confirmation_reason=?,delivery_code_hash=NULL,delivery_code_ciphertext=NULL,"+
            "delivery_code_blocked_until=NULL WHERE id=? AND status='DELIVERY_VERIFICATION' AND delivery_code_verified_at IS NULL")){
            ps.setLong(1,actor.id());ps.setString(2,actor.role().name());ps.setString(3,reason);ps.setLong(4,order.id());
            if(ps.executeUpdate()!=1)throw new SQLException("Delivery verification state changed");
        }
        log(c,order.id(),OrderStatus.DELIVERED);
        auditSecurity(c,order,actor,actor.role()==Role.ADMIN?"ADMIN_DELIVERY_VERIFIED":"DELIVERY_VERIFIED",OrderStatus.DELIVERED,ip,reason);
    }

    public void auditSecurity(Connection c,Order order,User actor,String action,OrderStatus next,String ip,String detail)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "INSERT INTO order_security_audit(order_id,actor_id,actor_role,action,old_status,new_status,ip_address,metadata) "+
            "VALUES(?,?,?,?,?,?,?,jsonb_build_object('detail',?))")){
            ps.setLong(1,order.id());ps.setLong(2,actor.id());ps.setString(3,actor.role().name());ps.setString(4,action);
            ps.setString(5,order.status().name());ps.setString(6,next.name());ps.setString(7,ip==null?"unknown":ip);ps.setString(8,detail);ps.executeUpdate();
        }
    }

    public int scheduledUsage(Connection c,Instant start,Instant end)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "SELECT COUNT(*) FROM orders WHERE status<>'CANCELLED' AND delivery_window_start=? AND delivery_window_end=?")){
            ps.setTimestamp(1,Timestamp.from(start));ps.setTimestamp(2,Timestamp.from(end));try(ResultSet rs=ps.executeQuery()){rs.next();return rs.getInt(1);}
        }
    }

    private static long insertItem(Connection c,long orderId,PricedItem item)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "INSERT INTO order_items(order_id,product_id,variant_id,product_name_snapshot,variant_label_snapshot,quantity,selected_weight_kg,"+
            "stock_demand,unit_price_snapshot,price_per_kg_snapshot,unit_weight_snapshot,line_subtotal) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id")){
            int i=1;ps.setLong(i++,orderId);ps.setLong(i++,item.productId());nullableLong(ps,i++,item.variantId());ps.setString(i++,item.productName());
            ps.setString(i++,item.variantLabel());ps.setInt(i++,item.quantity());decimal(ps,i++,item.selectedWeightKg());ps.setBigDecimal(i++,item.stockDemand());
            decimal(ps,i++,item.unitPrice());decimal(ps,i++,item.pricePerKg());ps.setBigDecimal(i++,item.unitWeightKg());ps.setBigDecimal(i,item.lineSubtotal());
            try(ResultSet rs=ps.executeQuery()){rs.next();return rs.getLong(1);}
        }
    }

    private static void insertCharge(Connection c,long id,ChargeLine line)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO order_charge_lines(order_id,charge_type,label,amount,sort_order) VALUES(?,?,?,?,?)")){
            ps.setLong(1,id);ps.setString(2,line.type());ps.setString(3,line.label());ps.setBigDecimal(4,line.amount());ps.setInt(5,line.sortOrder());ps.executeUpdate();
        }
    }

    private static void insertGift(Connection c,long id,GiftOptions g,Instant scheduled,Instant start,Instant end,String timezone)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "INSERT INTO gift_delivery_details(order_id,recipient_name,recipient_phone,occasion,gift_message,sender_display_name,hide_sender,"+
            "hide_price,wrapping_style,card_style,surprise_delivery,recipient_otp_required,scheduled_at,delivery_window_start,"+
            "delivery_window_end,timezone,delivery_instructions) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
            int i=1;ps.setLong(i++,id);ps.setString(i++,g.recipientName());ps.setString(i++,g.recipientPhone());ps.setString(i++,g.occasion());
            ps.setString(i++,g.giftMessage());ps.setString(i++,g.senderName());ps.setBoolean(i++,Boolean.TRUE.equals(g.hideSender()));
            ps.setBoolean(i++,Boolean.TRUE.equals(g.hidePrice()));ps.setString(i++,g.wrappingStyle());ps.setString(i++,g.cardStyle());
            ps.setBoolean(i++,Boolean.TRUE.equals(g.surpriseDelivery()));ps.setBoolean(i++,Boolean.TRUE.equals(g.recipientOtpRequired()));
            instant(ps,i++,scheduled);instant(ps,i++,start);instant(ps,i++,end);ps.setString(i++,timezone==null?"Asia/Kolkata":timezone);ps.setString(i,g.deliveryInstructions());ps.executeUpdate();
        }
    }

    public static void log(Connection c,long id,OrderStatus status)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO order_status_log(order_id,status) VALUES(?,?)")){
            ps.setLong(1,id);ps.setString(2,status.name());ps.executeUpdate();
        }
    }

    private static List<Order> list(ResultSet rs)throws SQLException{List<Order> values=new ArrayList<>();while(rs.next())values.add(map(rs));return values;}
    private static Order map(ResultSet rs)throws SQLException{
        long agent=rs.getLong("agent_id");Long agentId=rs.wasNull()?null:agent;
        return new Order(rs.getLong("id"),rs.getLong("customer_id"),agentId,rs.getString("customer_name"),rs.getString("agent_name"),
            rs.getString("pickup_address"),rs.getString("drop_address"),rs.getDouble("pickup_lat"),rs.getDouble("pickup_lng"),
            rs.getDouble("drop_lat"),rs.getDouble("drop_lng"),rs.getLong("pickup_zone_id"),rs.getLong("drop_zone_id"),
            rs.getString("pickup_zone"),rs.getString("drop_zone"),rs.getString("package_name"),rs.getInt("item_count"),
            rs.getBigDecimal("total_weight_kg"),rs.getString("notes"),OrderStatus.valueOf(rs.getString("status")),
            OrderType.valueOf(rs.getString("order_type")),Priority.valueOf(rs.getString("priority")),rs.getBoolean("fragile"),
            rs.getBoolean("temperature_controlled"),rs.getBigDecimal("price"),iso(rs,"scheduled_delivery_at"),
            iso(rs,"delivery_window_start"),iso(rs,"delivery_window_end"),rs.getString("timezone"),iso(rs,"created_at"),
            iso(rs,"delivered_at"),rs.getBoolean("hide_price"),rs.getString("occasion"),rs.getInt("version"));
    }
    private static String iso(ResultSet rs,String column)throws SQLException{Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant().toString();}
    private static Instant toInstant(ResultSet rs,int column)throws SQLException{Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant();}
    private static String mask(String phone){if(phone==null||phone.length()<4)return "****";return "******"+phone.substring(phone.length()-4);}
    private static void instant(PreparedStatement ps,int i,Instant value)throws SQLException{if(value==null)ps.setNull(i,Types.TIMESTAMP_WITH_TIMEZONE);else ps.setTimestamp(i,Timestamp.from(value));}
    private static void decimal(PreparedStatement ps,int i,java.math.BigDecimal value)throws SQLException{if(value==null)ps.setNull(i,Types.DECIMAL);else ps.setBigDecimal(i,value);}
    private static void nullableLong(PreparedStatement ps,int i,Long value)throws SQLException{if(value==null)ps.setNull(i,Types.BIGINT);else ps.setLong(i,value);}
}

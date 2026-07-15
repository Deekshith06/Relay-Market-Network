package com.relaydelivery.dao;

import com.relaydelivery.config.Database;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.util.Json;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class PricingDao {
    public record Coupon(String code, BigDecimal percentage, BigDecimal maximumDiscount,
                         BigDecimal minimumOrderValue) {}
    private final Database db;
    public PricingDao(Database db) { this.db=db; }

    public List<PricingRule> activeRules() throws SQLException {
        return rules(false);
    }

    public List<PricingRule> rules(boolean includeInactive) throws SQLException {
        String sql="SELECT * FROM pricing_rules WHERE " + (includeInactive?"TRUE":"active=TRUE AND effective_from<=CURRENT_TIMESTAMP AND (effective_until IS NULL OR effective_until>CURRENT_TIMESTAMP)") +
            " ORDER BY rule_type,priority,id";
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(sql);ResultSet rs=ps.executeQuery()){
            List<PricingRule> values=new ArrayList<>(); while(rs.next()) values.add(rule(rs)); return values;
        }
    }

    public Optional<Coupon> coupon(String code) throws SQLException {
        if(code==null||code.isBlank()) return Optional.empty();
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement(
            "SELECT code,percentage_amount,maximum_discount,minimum_order_value FROM coupons " +
            "WHERE code=? AND active=TRUE AND effective_from<=CURRENT_TIMESTAMP AND effective_until>CURRENT_TIMESTAMP")){
            ps.setString(1,code.trim().toUpperCase(Locale.ROOT));
            try(ResultSet rs=ps.executeQuery()){
                return rs.next()?Optional.of(new Coupon(rs.getString(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4))):Optional.empty();
            }
        }
    }

    public void saveQuote(long customerId, Quote quote, QuoteRequest request, String requestHash,
                          List<PricedItem> items, List<ChargeLine> lines) throws SQLException {
        try(Connection c=db.connection()){
            c.setAutoCommit(false);
            try {
                BigDecimal category=sum(lines,"CATEGORY_");
                BigDecimal special=sumTypes(lines,Set.of("FRAGILE","TEMPERATURE_CONTROL","INSURANCE","PRIORITY"));
                try(PreparedStatement ps=c.prepareStatement(
                    "INSERT INTO delivery_quotes(id,customer_id,request_hash,request_payload,product_subtotal,base_delivery_fee,"+
                    "distance_km,distance_charge,total_weight_kg,weight_charge,category_charge,special_handling_charge,gift_charge,"+
                    "scheduling_charge,platform_fee,tax_total,discount_total,delivery_total,final_total,expires_at) "+
                    "VALUES(?::uuid,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
                    int i=1;ps.setString(i++,quote.quoteId());ps.setLong(i++,customerId);ps.setString(i++,requestHash);ps.setString(i++,Json.stringify(request));
                    ps.setBigDecimal(i++,quote.productSubtotal());ps.setBigDecimal(i++,quote.baseDeliveryFee());ps.setBigDecimal(i++,quote.distanceKm());
                    ps.setBigDecimal(i++,quote.distanceCharge());ps.setBigDecimal(i++,quote.totalWeightKg());ps.setBigDecimal(i++,quote.weightCharge());
                    ps.setBigDecimal(i++,category);ps.setBigDecimal(i++,special);ps.setBigDecimal(i++,quote.giftCharge());ps.setBigDecimal(i++,quote.schedulingCharge());
                    ps.setBigDecimal(i++,quote.platformFee());ps.setBigDecimal(i++,quote.tax());ps.setBigDecimal(i++,quote.discount());
                    ps.setBigDecimal(i++,quote.totalDeliveryCharge());ps.setBigDecimal(i++,quote.finalPayableAmount());
                    ps.setTimestamp(i,Timestamp.from(Instant.parse(quote.expiresAt())));ps.executeUpdate();
                }
                for(PricedItem item:items) insertItem(c,quote.quoteId(),item);
                for(ChargeLine line:lines) insertLine(c,quote.quoteId(),line);
                c.commit();
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }
    }

    public Optional<QuoteSnapshot> quote(long customerId,String quoteId,boolean lock,Connection supplied) throws SQLException {
        Connection c=supplied==null?db.connection():supplied;
        try {
            String sql="SELECT * FROM delivery_quotes WHERE id=?::uuid AND customer_id=?"+(lock?" FOR UPDATE":"");
            try(PreparedStatement ps=c.prepareStatement(sql)){
                ps.setString(1,quoteId);ps.setLong(2,customerId);
                try(ResultSet rs=ps.executeQuery()){
                    if(!rs.next()) return Optional.empty();
                    List<ChargeLine> lines=lines(c,quoteId); List<PricedItem> items=items(c,quoteId);
                    Quote quote=mapQuote(rs,lines);
                    QuoteRequest request=Json.parse(rs.getString("request_payload"),QuoteRequest.class);
                    Timestamp used=rs.getTimestamp("used_at");
                    return Optional.of(new QuoteSnapshot(quote,request,items,lines,customerId,rs.getString("request_hash"),used==null?null:used.toInstant().toString()));
                }
            }
        } finally { if(supplied==null)c.close(); }
    }

    public void use(Connection c,String quoteId) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement(
            "UPDATE delivery_quotes SET used_at=CURRENT_TIMESTAMP WHERE id=?::uuid AND used_at IS NULL AND expires_at>CURRENT_TIMESTAMP")){
            ps.setString(1,quoteId); if(ps.executeUpdate()!=1) throw new SQLException("Quote is expired or already used","P0001");
        }
    }

    public PricingRule updateRule(long adminId,long id,PricingRule value) throws SQLException {
        try(Connection c=db.connection()){
            c.setAutoCommit(false);
            try {
                String old;
                try(PreparedStatement read=c.prepareStatement("SELECT row_to_json(r)::text FROM pricing_rules r WHERE id=? FOR UPDATE")){
                    read.setLong(1,id);try(ResultSet rs=read.executeQuery()){if(!rs.next())return null;old=rs.getString(1);}
                }
                try(PreparedStatement ps=c.prepareStatement(
                    "UPDATE pricing_rules SET rule_type=?,category_id=?,applies_to=?,minimum_value=?,maximum_value=?,flat_amount=?,"+
                    "per_unit_amount=?,percentage_amount=?,priority=?,active=? WHERE id=? RETURNING *")){
                    bindRule(ps,value);ps.setLong(11,id);
                    try(ResultSet rs=ps.executeQuery()){
                        if(!rs.next())return null;PricingRule updated=rule(rs);
                        try(PreparedStatement audit=c.prepareStatement(
                            "INSERT INTO pricing_rule_audit(pricing_rule_id,admin_user_id,action,old_value,new_value) "+
                            "VALUES(?,?,\'UPDATE\',?::jsonb,row_to_json((SELECT r FROM pricing_rules r WHERE r.id=?))::jsonb)")){
                            audit.setLong(1,id);audit.setLong(2,adminId);audit.setString(3,old);audit.setLong(4,id);audit.executeUpdate();
                        }
                        c.commit();return updated;
                    }
                }
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }
    }

    private static void insertItem(Connection c,String id,PricedItem item)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "INSERT INTO delivery_quote_items(quote_id,product_id,variant_id,product_name,variant_label,quantity,selected_weight_kg,"+
            "stock_demand,unit_price_snapshot,price_per_kg_snapshot,unit_weight_snapshot,line_subtotal,fragile,temperature_controlled,requires_insurance) "+
            "VALUES(?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
            int i=1;ps.setString(i++,id);ps.setLong(i++,item.productId());nullableLong(ps,i++,item.variantId());ps.setString(i++,item.productName());
            ps.setString(i++,item.variantLabel());ps.setInt(i++,item.quantity());decimal(ps,i++,item.selectedWeightKg());ps.setBigDecimal(i++,item.stockDemand());
            decimal(ps,i++,item.unitPrice());decimal(ps,i++,item.pricePerKg());ps.setBigDecimal(i++,item.unitWeightKg());ps.setBigDecimal(i++,item.lineSubtotal());
            ps.setBoolean(i++,item.fragile());ps.setBoolean(i++,item.temperatureControlled());ps.setBoolean(i,item.requiresInsurance());ps.executeUpdate();
        }
    }

    private static void insertLine(Connection c,String id,ChargeLine line)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO delivery_quote_lines(quote_id,charge_type,label,amount,sort_order) VALUES(?::uuid,?,?,?,?)")){
            ps.setString(1,id);ps.setString(2,line.type());ps.setString(3,line.label());ps.setBigDecimal(4,line.amount());ps.setInt(5,line.sortOrder());ps.executeUpdate();
        }
    }

    private static List<PricedItem> items(Connection c,String id)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement(
            "SELECT qi.*,p.category_id,p.shelf_life_days,c.name category_name,c.max_delivery_distance_km FROM delivery_quote_items qi "+
            "JOIN products p ON p.id=qi.product_id JOIN categories c ON c.id=p.category_id WHERE quote_id=?::uuid ORDER BY qi.id")){
            ps.setString(1,id);try(ResultSet rs=ps.executeQuery()){
                List<PricedItem> values=new ArrayList<>();while(rs.next()){
                    long variant=rs.getLong("variant_id");Long variantId=rs.wasNull()?null:variant;
                    int shelf=rs.getInt("shelf_life_days");Integer shelfLife=rs.wasNull()?null:shelf;
                    values.add(new PricedItem(rs.getLong("product_id"),variantId,rs.getLong("category_id"),rs.getString("category_name"),rs.getString("product_name"),
                        rs.getString("variant_label"),rs.getInt("quantity"),rs.getBigDecimal("selected_weight_kg"),rs.getBigDecimal("stock_demand"),
                        rs.getBigDecimal("unit_price_snapshot"),rs.getBigDecimal("price_per_kg_snapshot"),rs.getBigDecimal("unit_weight_snapshot"),
                        rs.getBigDecimal("line_subtotal"),rs.getBoolean("fragile"),rs.getBoolean("temperature_controlled"),
                        rs.getBoolean("requires_insurance"),shelfLife,rs.getBigDecimal("max_delivery_distance_km")));
                }return values;
            }
        }
    }

    private static List<ChargeLine> lines(Connection c,String id)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("SELECT charge_type,label,amount,sort_order FROM delivery_quote_lines WHERE quote_id=?::uuid ORDER BY sort_order,id")){
            ps.setString(1,id);try(ResultSet rs=ps.executeQuery()){
                List<ChargeLine> values=new ArrayList<>();while(rs.next())values.add(new ChargeLine(rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getInt(4)));return values;
            }
        }
    }

    private static Quote mapQuote(ResultSet rs,List<ChargeLine> lines)throws SQLException{
        List<ChargeLine> category=lines.stream().filter(x->x.type().startsWith("CATEGORY_")).toList();
        List<ChargeLine> special=lines.stream().filter(x->Set.of("FRAGILE","TEMPERATURE_CONTROL","INSURANCE","PRIORITY").contains(x.type())).toList();
        return new Quote(rs.getString("id"),rs.getString("currency"),rs.getBigDecimal("product_subtotal"),
            rs.getBigDecimal("base_delivery_fee"),rs.getBigDecimal("distance_km"),rs.getBigDecimal("distance_charge"),
            rs.getBigDecimal("total_weight_kg"),rs.getBigDecimal("weight_charge"),category,special,rs.getBigDecimal("gift_charge"),
            rs.getBigDecimal("scheduling_charge"),rs.getBigDecimal("platform_fee"),rs.getBigDecimal("tax_total"),
            rs.getBigDecimal("discount_total"),rs.getBigDecimal("delivery_total"),rs.getBigDecimal("final_total"),lines,
            rs.getTimestamp("expires_at").toInstant().toString());
    }

    private static PricingRule rule(ResultSet rs)throws SQLException{
        long category=rs.getLong("category_id");Long categoryId=rs.wasNull()?null:category;
        return new PricingRule(rs.getLong("id"),RuleType.valueOf(rs.getString("rule_type")),
            categoryId,rs.getString("applies_to"),rs.getBigDecimal("minimum_value"),rs.getBigDecimal("maximum_value"),
            rs.getBigDecimal("flat_amount"),rs.getBigDecimal("per_unit_amount"),rs.getBigDecimal("percentage_amount"),rs.getInt("priority"),rs.getBoolean("active"));
    }

    private static void bindRule(PreparedStatement ps,PricingRule v)throws SQLException{
        ps.setString(1,v.type().name());nullableLong(ps,2,v.categoryId());ps.setString(3,v.appliesTo());decimal(ps,4,v.minimumValue());
        decimal(ps,5,v.maximumValue());ps.setBigDecimal(6,v.flatAmount());ps.setBigDecimal(7,v.perUnitAmount());
        ps.setBigDecimal(8,v.percentageAmount());ps.setInt(9,v.priority());ps.setBoolean(10,v.active());
    }
    private static BigDecimal sum(List<ChargeLine> lines,String prefix){return lines.stream().filter(x->x.type().startsWith(prefix)).map(ChargeLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static BigDecimal sumTypes(List<ChargeLine> lines,Set<String> types){return lines.stream().filter(x->types.contains(x.type())).map(ChargeLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static void decimal(PreparedStatement ps,int i,BigDecimal v)throws SQLException{if(v==null)ps.setNull(i,Types.DECIMAL);else ps.setBigDecimal(i,v);}
    private static void nullableLong(PreparedStatement ps,int i,Long v)throws SQLException{if(v==null)ps.setNull(i,Types.BIGINT);else ps.setLong(i,v);}
}

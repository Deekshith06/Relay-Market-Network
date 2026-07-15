package com.relaydelivery.dao;

import com.relaydelivery.config.Database;
import com.relaydelivery.model.Models.*;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

public final class CatalogDao {
    private static final String PRODUCT_SELECT =
        "SELECT p.*,c.name category_name,c.max_delivery_distance_km " +
        "FROM products p JOIN categories c ON c.id=p.category_id ";
    private final Database db;

    public CatalogDao(Database db) { this.db = db; }

    public List<Category> categories(boolean includeInactive) throws SQLException {
        String sql = "SELECT * FROM categories " + (includeInactive ? "" : "WHERE active=TRUE ") + "ORDER BY name";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Category> values = new ArrayList<>();
            while (rs.next()) values.add(category(rs));
            return values;
        }
    }

    public List<Product> products(Long categoryId, String search, boolean includeInactive) throws SQLException {
        StringBuilder sql = new StringBuilder(PRODUCT_SELECT + "WHERE 1=1 ");
        if (!includeInactive) sql.append("AND p.active=TRUE AND c.active=TRUE ");
        if (categoryId != null) sql.append("AND p.category_id=? ");
        if (search != null && !search.isBlank()) sql.append("AND (LOWER(p.name) LIKE ? OR LOWER(p.description) LIKE ?) ");
        sql.append("ORDER BY c.name,p.name");
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            if (categoryId != null) ps.setLong(i++, categoryId);
            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                ps.setString(i++, term); ps.setString(i, term);
            }
            try (ResultSet rs = ps.executeQuery()) { return mapProducts(c, rs, includeInactive); }
        }
    }

    public Optional<Product> product(long id, boolean includeInactive) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(
            PRODUCT_SELECT + "WHERE p.id=? " + (includeInactive ? "" : "AND p.active=TRUE AND c.active=TRUE"))) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                List<Product> values = mapProducts(c, rs, includeInactive);
                return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
            }
        }
    }

    public Optional<Product> lockProduct(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(PRODUCT_SELECT + "WHERE p.id=? FOR UPDATE OF p")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                List<Product> values = mapProducts(c, rs, true);
                return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
            }
        }
    }

    public Cart cart(long customerId) throws SQLException {
        Map<Long,Product> productById=new HashMap<>();
        for(Product product:products(null,null,false))productById.put(product.id(),product);
        try (Connection c = db.connection()) {
            long cartId = cartId(c, customerId);
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,product_id,variant_id,quantity,selected_weight_kg FROM cart_items WHERE cart_id=? ORDER BY id")) {
                ps.setLong(1, cartId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<CartItem> items = new ArrayList<>();
                    while (rs.next()) {
                        long productId = rs.getLong("product_id");
                        Product product = productById.get(productId);
                        if (product == null) continue;
                        long variant = rs.getLong("variant_id"); Long variantId=rs.wasNull()?null:variant;
                        items.add(new CartItem(rs.getLong("id"), productId, variantId,
                            rs.getInt("quantity"), rs.getBigDecimal("selected_weight_kg"), product));
                    }
                    return new Cart(cartId, items);
                }
            }
        }
    }

    public boolean addCartItem(long customerId, CartItemRequest item) throws SQLException {
        try (Connection c = db.connection()) {
            long cartId = cartId(c, customerId);
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cart_items(cart_id,product_id,variant_id,quantity,selected_weight_kg) VALUES(?,?,?,?,?) " +
                "ON CONFLICT (cart_id,product_id,variant_id) DO UPDATE SET " +
                "quantity=CASE WHEN EXCLUDED.selected_weight_kg IS NULL THEN cart_items.quantity+EXCLUDED.quantity ELSE 1 END,"+
                "selected_weight_kg=CASE WHEN EXCLUDED.selected_weight_kg IS NULL THEN cart_items.selected_weight_kg "+
                "ELSE COALESCE(cart_items.selected_weight_kg,0)+EXCLUDED.selected_weight_kg END,updated_at=CURRENT_TIMESTAMP "+
                "WHERE (EXCLUDED.selected_weight_kg IS NOT NULL AND COALESCE(cart_items.selected_weight_kg,0)+EXCLUDED.selected_weight_kg"+
                "<=(SELECT maximum_order_weight FROM products WHERE id=EXCLUDED.product_id)) OR "+
                "(EXCLUDED.selected_weight_kg IS NULL AND cart_items.quantity+EXCLUDED.quantity<=(SELECT c.max_quantity FROM products p JOIN categories c ON c.id=p.category_id WHERE p.id=EXCLUDED.product_id))")) {
                ps.setLong(1, cartId); ps.setLong(2, item.productId());
                if (item.variantId() == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, item.variantId());
                ps.setInt(4, item.quantity() == null ? 1 : item.quantity());
                if (item.selectedWeightKg() == null) ps.setNull(5, Types.DECIMAL); else ps.setBigDecimal(5, item.selectedWeightKg());
                return ps.executeUpdate()==1;
            }
        }
    }

    public boolean updateCartItem(long customerId, long itemId, CartItemRequest item) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(
            "UPDATE cart_items ci SET quantity=?,selected_weight_kg=?,updated_at=CURRENT_TIMESTAMP " +
            "FROM carts c WHERE ci.id=? AND ci.cart_id=c.id AND c.customer_id=?")) {
            ps.setInt(1, item.quantity() == null ? 1 : item.quantity());
            if (item.selectedWeightKg() == null) ps.setNull(2, Types.DECIMAL); else ps.setBigDecimal(2, item.selectedWeightKg());
            ps.setLong(3, itemId); ps.setLong(4, customerId);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean deleteCartItem(long customerId, long itemId) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(
            "DELETE FROM cart_items ci USING carts c WHERE ci.id=? AND ci.cart_id=c.id AND c.customer_id=?")) {
            ps.setLong(1, itemId); ps.setLong(2, customerId); return ps.executeUpdate() == 1;
        }
    }

    public void clearCart(Connection c, long customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "DELETE FROM cart_items WHERE cart_id=(SELECT id FROM carts WHERE customer_id=?)")) {
            ps.setLong(1, customerId); ps.executeUpdate();
        }
    }

    public Category createCategory(Category value) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO categories(name,description,handling_type,fragile_default,temperature_controlled_default," +
            "requires_insurance_default,max_quantity,max_delivery_distance_km,delivery_restrictions,return_eligible,default_priority,active) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *")) {
            bindCategory(ps, value);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return category(rs); }
        }
    }

    public Product saveProduct(Product value, Long id, long adminId) throws SQLException {
        String sql = id == null
            ? "INSERT INTO products(category_id,name,description,sku,pricing_type,unit_price,price_per_kg,unit_weight_kg," +
              "minimum_order_weight,maximum_order_weight,packaging_weight_kg,stock_quantity,fragile,temperature_controlled," +
              "requires_insurance,shelf_life_days,image_url,active) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id"
            : "UPDATE products SET category_id=?,name=?,description=?,sku=?,pricing_type=?,unit_price=?,price_per_kg=?,unit_weight_kg=?," +
              "minimum_order_weight=?,maximum_order_weight=?,packaging_weight_kg=?,stock_quantity=?,fragile=?,temperature_controlled=?," +
              "requires_insurance=?,shelf_life_days=?,image_url=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE id=? RETURNING id";
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                String old=null;BigDecimal oldStock=BigDecimal.ZERO;
                if(id!=null)try(PreparedStatement read=c.prepareStatement("SELECT to_jsonb(p)::text,stock_quantity FROM products p WHERE id=? FOR UPDATE")){
                    read.setLong(1,id);try(ResultSet rs=read.executeQuery()){if(!rs.next())return null;old=rs.getString(1);oldStock=rs.getBigDecimal(2);}
                }
                long savedId;
                try(PreparedStatement ps = c.prepareStatement(sql)){
                    bindProduct(ps, value);if (id != null) ps.setLong(19, id);
                    try(ResultSet rs=ps.executeQuery()){if(!rs.next())return null;savedId=rs.getLong(1);}
                }
                try(PreparedStatement audit=c.prepareStatement(
                    "INSERT INTO pricing_rule_audit(admin_user_id,action,old_value,new_value) "+
                    "SELECT ?,?,?,to_jsonb(p) FROM products p WHERE p.id=?")){
                    audit.setLong(1,adminId);audit.setString(2,id==null?"PRODUCT_CREATE":"PRODUCT_UPDATE");
                    if(old==null)audit.setNull(3,Types.OTHER);else audit.setObject(3,old,Types.OTHER);audit.setLong(4,savedId);audit.executeUpdate();
                }
                if(id==null||oldStock.compareTo(value.stockQuantity())!=0)
                    auditInventory(c,savedId,null,adminId,id==null?"CREATE":"SET",oldStock,value.stockQuantity(),id==null?"Initial product stock":"Product form stock update");
                c.commit();return product(savedId,true).orElseThrow();
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }
    }

    public ProductVariant addVariant(long productId, ProductVariant value) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO product_variants(product_id,label,sku,unit_price,price_per_kg,unit_weight_kg," +
            "minimum_order_weight,maximum_order_weight,stock_quantity,delivery_restriction,active) VALUES(?,?,?,?,?,?,?,?,?,?,?) RETURNING *")) {
            ps.setLong(1, productId); ps.setString(2, value.label()); ps.setString(3, value.sku());
            decimal(ps,4,value.unitPrice()); decimal(ps,5,value.pricePerKg()); ps.setBigDecimal(6,value.unitWeightKg());
            decimal(ps,7,value.minimumOrderWeight()); decimal(ps,8,value.maximumOrderWeight());
            ps.setBigDecimal(9,value.stockQuantity()); ps.setString(10,value.deliveryRestriction()); ps.setBoolean(11,value.active());
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return variant(rs); }
        }
    }

    public boolean setStock(long productId, Long variantId, BigDecimal stock,long adminId) throws SQLException {
        try(Connection c=db.connection()){
            c.setAutoCommit(false);try{
                BigDecimal before;
                String read=variantId==null?"SELECT stock_quantity FROM products WHERE id=? FOR UPDATE"
                    :"SELECT stock_quantity FROM product_variants WHERE id=? AND product_id=? FOR UPDATE";
                try(PreparedStatement ps=c.prepareStatement(read)){ps.setLong(1,variantId==null?productId:variantId);if(variantId!=null)ps.setLong(2,productId);
                    try(ResultSet rs=ps.executeQuery()){if(!rs.next()){c.rollback();return false;}before=rs.getBigDecimal(1);}}
                String update=variantId==null?"UPDATE products SET stock_quantity=?,updated_at=CURRENT_TIMESTAMP WHERE id=?"
                    :"UPDATE product_variants SET stock_quantity=? WHERE id=? AND product_id=?";
                try(PreparedStatement ps=c.prepareStatement(update)){ps.setBigDecimal(1,stock);ps.setLong(2,variantId==null?productId:variantId);if(variantId!=null)ps.setLong(3,productId);ps.executeUpdate();}
                int compared=stock.compareTo(before);auditInventory(c,productId,variantId,adminId,compared>0?"INCREASE":compared<0?"DECREASE":"SET",before,stock,"Admin stock adjustment");
                c.commit();return true;
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }
    }

    public boolean categoryActive(long id)throws SQLException{
        try(Connection c=db.connection();PreparedStatement ps=c.prepareStatement("SELECT 1 FROM categories WHERE id=? AND active=TRUE")){
            ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }
    }

    private long cartId(Connection c, long customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO carts(customer_id) VALUES(?) ON CONFLICT(customer_id) DO UPDATE SET updated_at=CURRENT_TIMESTAMP RETURNING id")) {
            ps.setLong(1, customerId); try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private static List<Product> mapProducts(Connection c, ResultSet rs, boolean includeInactive) throws SQLException {
        List<Product> base = new ArrayList<>(); Set<Long> ids = new LinkedHashSet<>();
        while (rs.next()) { Product product = product(rs, List.of()); base.add(product); ids.add(product.id()); }
        if (ids.isEmpty()) return base;
        Map<Long,List<ProductVariant>> variants = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM product_variants WHERE product_id=ANY(?) " + (includeInactive ? "" : "AND active=TRUE ") + "ORDER BY product_id,id")) {
            ps.setArray(1, c.createArrayOf("BIGINT", ids.toArray()));
            try (ResultSet values = ps.executeQuery()) {
                while (values.next()) variants.computeIfAbsent(values.getLong("product_id"), ignored -> new ArrayList<>()).add(variant(values));
            }
        }
        return base.stream().map(p -> productWithVariants(p, variants.getOrDefault(p.id(), List.of()))).toList();
    }

    private static Product product(ResultSet rs, List<ProductVariant> variants) throws SQLException {
        int shelf = rs.getInt("shelf_life_days"); boolean shelfMissing = rs.wasNull();
        return new Product(rs.getLong("id"),rs.getLong("category_id"),rs.getString("category_name"),
            rs.getString("name"),rs.getString("description"),rs.getString("sku"),PricingType.valueOf(rs.getString("pricing_type")),
            rs.getBigDecimal("unit_price"),rs.getBigDecimal("price_per_kg"),rs.getBigDecimal("unit_weight_kg"),
            rs.getBigDecimal("minimum_order_weight"),rs.getBigDecimal("maximum_order_weight"),rs.getBigDecimal("packaging_weight_kg"),
            rs.getBigDecimal("stock_quantity"),rs.getBoolean("fragile"),rs.getBoolean("temperature_controlled"),
            rs.getBoolean("requires_insurance"),shelfMissing?null:shelf,rs.getString("image_url"),rs.getBoolean("active"),variants);
    }

    private static Product productWithVariants(Product p, List<ProductVariant> variants) {
        return new Product(p.id(),p.categoryId(),p.categoryName(),p.name(),p.description(),p.sku(),p.pricingType(),
            p.unitPrice(),p.pricePerKg(),p.unitWeightKg(),p.minimumOrderWeight(),p.maximumOrderWeight(),p.packagingWeightKg(),
            p.stockQuantity(),p.fragile(),p.temperatureControlled(),p.requiresInsurance(),p.shelfLifeDays(),p.imageUrl(),p.active(),variants);
    }

    private static ProductVariant variant(ResultSet rs) throws SQLException {
        return new ProductVariant(rs.getLong("id"),rs.getLong("product_id"),rs.getString("label"),rs.getString("sku"),
            rs.getBigDecimal("unit_price"),rs.getBigDecimal("price_per_kg"),rs.getBigDecimal("unit_weight_kg"),
            rs.getBigDecimal("minimum_order_weight"),rs.getBigDecimal("maximum_order_weight"),rs.getBigDecimal("stock_quantity"),
            rs.getString("delivery_restriction"),rs.getBoolean("active"));
    }

    private static Category category(ResultSet rs) throws SQLException {
        return new Category(rs.getLong("id"),rs.getString("name"),rs.getString("description"),rs.getString("handling_type"),
            rs.getBoolean("fragile_default"),rs.getBoolean("temperature_controlled_default"),rs.getBoolean("requires_insurance_default"),
            rs.getInt("max_quantity"),rs.getBigDecimal("max_delivery_distance_km"),rs.getString("delivery_restrictions"),
            rs.getBoolean("return_eligible"),Priority.valueOf(rs.getString("default_priority")),rs.getBoolean("active"));
    }

    private static void bindCategory(PreparedStatement ps, Category v) throws SQLException {
        ps.setString(1,v.name()); ps.setString(2,v.description()); ps.setString(3,v.handlingType());
        ps.setBoolean(4,v.fragileDefault()); ps.setBoolean(5,v.temperatureControlledDefault());
        ps.setBoolean(6,v.requiresInsuranceDefault()); ps.setInt(7,v.maxQuantity()); ps.setBigDecimal(8,v.maxDeliveryDistanceKm());
        ps.setString(9,v.deliveryRestrictions()); ps.setBoolean(10,v.returnEligible()); ps.setString(11,v.defaultPriority().name()); ps.setBoolean(12,v.active());
    }

    private static void bindProduct(PreparedStatement ps, Product v) throws SQLException {
        ps.setLong(1,v.categoryId()); ps.setString(2,v.name()); ps.setString(3,v.description()); ps.setString(4,v.sku());
        ps.setString(5,v.pricingType().name()); decimal(ps,6,v.unitPrice()); decimal(ps,7,v.pricePerKg());
        decimal(ps,8,v.unitWeightKg()); decimal(ps,9,v.minimumOrderWeight()); decimal(ps,10,v.maximumOrderWeight());
        ps.setBigDecimal(11,v.packagingWeightKg()); ps.setBigDecimal(12,v.stockQuantity()); ps.setBoolean(13,v.fragile());
        ps.setBoolean(14,v.temperatureControlled()); ps.setBoolean(15,v.requiresInsurance());
        if(v.shelfLifeDays()==null) ps.setNull(16,Types.INTEGER); else ps.setInt(16,v.shelfLifeDays());
        ps.setString(17,v.imageUrl()); ps.setBoolean(18,v.active());
    }

    private static void decimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
        if (value == null) ps.setNull(index, Types.DECIMAL); else ps.setBigDecimal(index, value);
    }

    private static void auditInventory(Connection c,long productId,Long variantId,long adminId,String type,
                                       BigDecimal before,BigDecimal after,String reason)throws SQLException{
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO inventory_audit(product_id,variant_id,admin_id,change_type,quantity_before,quantity_change,quantity_after,reason) VALUES(?,?,?,?,?,?,?,?)")){
            ps.setLong(1,productId);if(variantId==null)ps.setNull(2,Types.BIGINT);else ps.setLong(2,variantId);ps.setLong(3,adminId);ps.setString(4,type);
            ps.setBigDecimal(5,before);ps.setBigDecimal(6,after.subtract(before));ps.setBigDecimal(7,after);ps.setString(8,reason);ps.executeUpdate();
        }
    }
}

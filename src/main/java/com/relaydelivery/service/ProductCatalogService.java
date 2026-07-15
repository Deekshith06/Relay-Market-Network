package com.relaydelivery.service;

import com.relaydelivery.config.Database;
import com.relaydelivery.dao.CatalogDao;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.util.ApiException;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.SQLException;
import java.util.*;

public final class ProductCatalogService {
    private final CatalogDao catalog;
    private final Set<String> imageHosts;
    public ProductCatalogService(CatalogDao catalog){
        this.catalog=catalog;
        this.imageHosts=Arrays.stream(Database.env("IMAGE_HOSTS","images.unsplash.com").split(","))
            .map(String::trim).map(x->x.toLowerCase(Locale.ROOT)).filter(x->x.matches("[a-z0-9.-]+"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(imageHosts.isEmpty())throw new IllegalArgumentException("IMAGE_HOSTS must contain a valid hostname");
    }
    public List<Category> categories(User actor)throws SQLException{return catalog.categories(actor.role()==Role.ADMIN);}
    public List<Product> products(User actor,Long category,String search)throws SQLException{
        if(search!=null&&search.length()>80)throw ApiException.badRequest("Search is too long");
        return catalog.products(category,search,actor.role()==Role.ADMIN);
    }
    public Product product(User actor,long id)throws SQLException{return catalog.product(id,actor.role()==Role.ADMIN).orElseThrow(()->ApiException.notFound("Product"));}
    public Cart cart(User actor)throws SQLException{requireCustomer(actor);return catalog.cart(actor.id());}
    public Cart add(User actor,CartItemRequest value)throws SQLException{
        requireCustomer(actor);validateCartItem(value);if(!catalog.addCartItem(actor.id(),value))
            throw ApiException.conflict("The cart quantity or selected weight exceeds the product limit");return catalog.cart(actor.id());
    }
    public Cart update(User actor,long id,CartItemRequest value)throws SQLException{
        requireCustomer(actor);validateCartItem(value);if(!catalog.updateCartItem(actor.id(),id,value))throw ApiException.notFound("Cart item");return catalog.cart(actor.id());
    }
    public Cart delete(User actor,long id)throws SQLException{
        requireCustomer(actor);if(!catalog.deleteCartItem(actor.id(),id))throw ApiException.notFound("Cart item");return catalog.cart(actor.id());
    }
    public Category createCategory(User actor,Category value)throws SQLException{
        requireAdmin(actor);validateCategory(value);try{return catalog.createCategory(value);}catch(SQLException e){if("23505".equals(e.getSQLState()))throw ApiException.conflict("A category already uses this name");throw e;}
    }
    public Product saveProduct(User actor,Long id,Product value)throws SQLException{
        requireAdmin(actor);validateProduct(value);try{Product saved=catalog.saveProduct(value,id,actor.id());if(saved==null)throw ApiException.notFound("Product");return saved;}
        catch(SQLException e){if("23505".equals(e.getSQLState()))throw ApiException.conflict("A product or variant already uses this SKU");throw e;}
    }
    public ProductVariant addVariant(User actor,long productId,ProductVariant value)throws SQLException{
        requireAdmin(actor);if(catalog.product(productId,true).isEmpty())throw ApiException.notFound("Product");
        clean(value.label(),"Variant label",1,80);clean(value.sku(),"Variant SKU",2,60);
        positive(value.unitWeightKg(),"Variant weight");
        if(value.stockQuantity()==null||value.stockQuantity().signum()<0)throw ApiException.badRequest("Variant stock is invalid");
        if(value.unitPrice()==null&&value.pricePerKg()==null)throw ApiException.badRequest("Variant price is required");
        try{return catalog.addVariant(productId,value);}catch(SQLException e){if("23505".equals(e.getSQLState()))throw ApiException.conflict("A product or variant already uses this SKU");throw e;}
    }
    public Product stock(User actor,long productId,Long variantId,BigDecimal quantity)throws SQLException{
        requireAdmin(actor);if(quantity==null||quantity.signum()<0||quantity.compareTo(new BigDecimal("100000"))>0)
            throw ApiException.badRequest("Stock quantity is invalid");
        if(!catalog.setStock(productId,variantId,quantity,actor.id()))throw ApiException.notFound("Product or variant");
        return catalog.product(productId,true).orElseThrow();
    }
    public Product status(User actor,long productId,boolean active)throws SQLException{
        requireAdmin(actor);Product p=catalog.product(productId,true).orElseThrow(()->ApiException.notFound("Product"));
        Product changed=new Product(p.id(),p.categoryId(),p.categoryName(),p.name(),p.description(),p.sku(),p.pricingType(),p.unitPrice(),p.pricePerKg(),
            p.unitWeightKg(),p.minimumOrderWeight(),p.maximumOrderWeight(),p.packagingWeightKg(),p.stockQuantity(),p.fragile(),p.temperatureControlled(),
            p.requiresInsurance(),p.shelfLifeDays(),p.imageUrl(),active,p.variants());return saveProduct(actor,productId,changed);
    }

    private void validateCartItem(CartItemRequest value)throws SQLException{
        if(value==null||value.productId()<=0)throw ApiException.badRequest("Select a product");
        Product product=catalog.product(value.productId(),false).orElseThrow(()->ApiException.notFound("Product"));
        int quantity=value.quantity()==null?1:value.quantity();
        Category category=catalog.categories(false).stream().filter(c->c.id()==product.categoryId()).findFirst().orElseThrow();
        if(quantity<=0||quantity>category.maxQuantity())throw ApiException.badRequest("Quantity must be between 1 and "+category.maxQuantity());
        if(product.pricingType()==PricingType.VARIANT){
            if(value.variantId()==null||product.variants().stream().noneMatch(v->v.id()==value.variantId()&&v.active()))
                throw ApiException.badRequest("Select an available variant");
        }else if(value.variantId()!=null)throw ApiException.badRequest("This product does not use variants");
        if(product.pricingType()==PricingType.WEIGHT){
            BigDecimal weight=value.selectedWeightKg();
            if(weight==null||weight.compareTo(product.minimumOrderWeight())<0||weight.compareTo(product.maximumOrderWeight())>0)
                throw ApiException.badRequest("Selected weight is outside the product range");
        }
    }
    private void validateCategory(Category v){
        if(v==null)throw ApiException.badRequest("Category details are required");clean(v.name(),"Category name",2,80);
        clean(v.description(),"Description",5,300);clean(v.handlingType(),"Handling type",2,30);
        if(v.maxQuantity()<=0||v.maxQuantity()>100)throw ApiException.badRequest("Maximum quantity is invalid");positive(v.maxDeliveryDistanceKm(),"Maximum distance");
    }
    private void validateProduct(Product v)throws SQLException{
        if(v==null||v.categoryId()<=0)throw ApiException.badRequest("Product category is required");
        if(!catalog.categoryActive(v.categoryId()))throw ApiException.badRequest("Select an active product category");
        clean(v.name(),"Product name",2,120);clean(v.description(),"Description",5,700);clean(v.sku(),"SKU",2,60);
        if(v.pricingType()==null)throw ApiException.badRequest("Pricing type is required");
        if(v.packagingWeightKg()==null||v.packagingWeightKg().signum()<0)throw ApiException.badRequest("Packaging weight is invalid");
        if(v.stockQuantity()==null||v.stockQuantity().signum()<0)throw ApiException.badRequest("Stock quantity is invalid");
        switch(v.pricingType()){
            case FIXED->{positive(v.unitPrice(),"Unit price");positive(v.unitWeightKg(),"Unit weight");}
            case WEIGHT->{positive(v.pricePerKg(),"Price per kilogram");positive(v.minimumOrderWeight(),"Minimum weight");positive(v.maximumOrderWeight(),"Maximum weight");}
            case VARIANT->{}
        }
        validateImage(v.imageUrl());
    }
    private void validateImage(String value){
        if(value==null||value.isBlank())return;
        URI uri;try{uri=URI.create(value);}catch(IllegalArgumentException e){throw ApiException.badRequest("Product image URL is invalid");}
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||!imageHosts.contains(uri.getHost().toLowerCase(Locale.ROOT)))
            throw ApiException.badRequest("Product image host is not allowed");
    }
    private static void positive(BigDecimal value,String label){if(value==null||value.signum()<=0)throw ApiException.badRequest(label+" must be positive");}
    private static String clean(String value,String label,int min,int max){String v=value==null?"":value.trim();if(v.length()<min||v.length()>max)throw ApiException.badRequest(label+" must be "+min+"-"+max+" characters");return v;}
    private static void requireCustomer(User u){if(u.role()!=Role.CUSTOMER)throw ApiException.forbidden();}
    private static void requireAdmin(User u){if(u.role()!=Role.ADMIN)throw ApiException.forbidden();}
}

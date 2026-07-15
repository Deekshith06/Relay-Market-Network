package com.relaydelivery.service;

import com.relaydelivery.config.Database;
import com.relaydelivery.dao.PricingDao;
import com.relaydelivery.dao.PricingDao.Coupon;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.service.DeliveryPricingEngine.Calculation;
import com.relaydelivery.service.GiftDeliveryService.SchedulePlan;
import com.relaydelivery.util.ApiException;
import com.relaydelivery.util.Json;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

public final class QuoteService {
    public record VerifiedQuote(QuoteSnapshot snapshot,SchedulePlan schedule){}
    private static final SecureRandom RANDOM=new SecureRandom();
    private final ProductPricingService products;private final DeliveryPricingEngine engine;
    private final PricingDao pricing;private final MapDistanceService distance;private final GiftDeliveryService gifts;
    private final Duration lifetime=Duration.ofMinutes(Long.parseLong(Database.env("QUOTE_MINUTES","10")));
    public QuoteService(ProductPricingService products,DeliveryPricingEngine engine,PricingDao pricing,
                        MapDistanceService distance,GiftDeliveryService gifts){
        this.products=products;this.engine=engine;this.pricing=pricing;this.distance=distance;this.gifts=gifts;
    }

    public Quote create(User actor,QuoteRequest request)throws SQLException{
        requireCustomer(actor);validateRequest(request);
        List<PricedItem> items=products.price(request.cartItems());
        MapDistanceService.Distance route=distance.distance(request.pickupLocation(),request.deliveryLocation());
        for(PricedItem item:items)if(route.kilometers().compareTo(item.maxDeliveryDistanceKm())>0)
            throw new ApiException(422,item.productName()+" cannot be delivered across this distance");
        SchedulePlan schedule=gifts.validate(request,items);
        Coupon coupon=resolveCoupon(request.couponCode());
        Calculation value=engine.calculate(items,route.kilometers(),request.priority(),request.giftOptions(),schedule!=null,pricing.activeRules(),coupon);
        String id=secureUuid().toString();Instant expires=Instant.now().plus(lifetime);
        Quote quote=quote(id,value,expires);String hash=hash(request);
        pricing.saveQuote(actor.id(),quote,request,hash,items,value.lines());return quote;
    }

    public VerifiedQuote lockAndVerify(Connection c,User actor,String quoteId)throws SQLException{
        requireCustomer(actor);if(quoteId==null||quoteId.isBlank())throw ApiException.badRequest("Quote is required");
        QuoteSnapshot stored;
        try{stored=pricing.quote(actor.id(),quoteId,true,c).orElseThrow(()->ApiException.notFound("Quote"));}
        catch(IllegalArgumentException e){throw ApiException.badRequest("Quote identifier is invalid");}
        if(stored.usedAt()!=null)throw ApiException.conflict("Quote has already been used");
        if(!Instant.parse(stored.quote().expiresAt()).isAfter(Instant.now()))throw ApiException.conflict("Quote has expired; refresh the price");
        if(!MessageDigest.isEqual(stored.requestHash().getBytes(StandardCharsets.US_ASCII),hash(stored.request()).getBytes(StandardCharsets.US_ASCII)))
            throw ApiException.conflict("Stored quote integrity check failed");
        List<PricedItem> current=products.price(stored.request().cartItems());
        MapDistanceService.Distance route=distance.distance(stored.request().pickupLocation(),stored.request().deliveryLocation());
        SchedulePlan schedule=gifts.validate(stored.request(),current);
        Coupon coupon=resolveCoupon(stored.request().couponCode());
        Calculation recalculated=engine.calculate(current,route.kilometers(),stored.request().priority(),stored.request().giftOptions(),schedule!=null,pricing.activeRules(),coupon);
        if(!same(stored.quote(),recalculated))throw ApiException.conflict("Price or product availability changed; request a new quote");
        return new VerifiedQuote(new QuoteSnapshot(stored.quote(),stored.request(),current,stored.lines(),stored.customerId(),stored.requestHash(),null),schedule);
    }

    public void markUsed(Connection c,String id)throws SQLException{
        try{pricing.use(c,id);}catch(SQLException e){if("P0001".equals(e.getSQLState()))throw ApiException.conflict("Quote is expired or already used");throw e;}
    }

    private Coupon resolveCoupon(String code)throws SQLException{
        if(code==null||code.isBlank())return null;
        return pricing.coupon(code).orElseThrow(()->ApiException.badRequest("Coupon is invalid or expired"));
    }
    private static Quote quote(String id,Calculation c,Instant expires){return new Quote(id,"INR",c.productSubtotal(),c.baseDeliveryFee(),
        c.distanceKm(),c.distanceCharge(),c.totalWeightKg(),c.weightCharge(),c.categoryCharges(),c.specialCharges(),c.giftCharge(),
        c.schedulingCharge(),c.platformFee(),c.tax(),c.discount(),c.totalDeliveryCharge(),c.finalTotal(),c.lines(),expires.toString());}
    private static boolean same(Quote quote,Calculation c){return eq(quote.productSubtotal(),c.productSubtotal())
        &&eq(quote.baseDeliveryFee(),c.baseDeliveryFee())&&eq(quote.distanceKm(),c.distanceKm())
        &&eq(quote.distanceCharge(),c.distanceCharge())&&eq(quote.totalWeightKg(),c.totalWeightKg())
        &&eq(quote.weightCharge(),c.weightCharge())&&eq(quote.giftCharge(),c.giftCharge())
        &&eq(quote.schedulingCharge(),c.schedulingCharge())&&eq(quote.platformFee(),c.platformFee())
        &&eq(quote.tax(),c.tax())&&eq(quote.discount(),c.discount())
        &&eq(quote.totalDeliveryCharge(),c.totalDeliveryCharge())&&eq(quote.finalPayableAmount(),c.finalTotal());}
    private static boolean eq(BigDecimal a,BigDecimal b){return a.compareTo(b)==0;}
    private static void validateRequest(QuoteRequest r){
        if(r==null||r.priority()==null)throw ApiException.badRequest("Quote details and priority are required");point(r.pickupLocation(),"Pickup");point(r.deliveryLocation(),"Delivery");
    }
    private static void point(GeoPoint p,String label){
        if(p==null||p.address()==null||p.address().trim().length()<5||p.address().length()>240)throw ApiException.badRequest(label+" address is invalid");
        if(!Double.isFinite(p.latitude())||!Double.isFinite(p.longitude())||p.latitude()<-90||p.latitude()>90||p.longitude()<-180||p.longitude()>180)
            throw ApiException.badRequest(label+" coordinates are invalid");if(p.zoneId()<=0)throw ApiException.badRequest(label+" zone is invalid");
    }
    private static String hash(QuoteRequest request){
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");return HexFormat.of().formatHex(digest.digest(Json.stringify(request).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static UUID secureUuid(){return new UUID(RANDOM.nextLong(),RANDOM.nextLong());}
    private static void requireCustomer(User actor){if(actor.role()!=Role.CUSTOMER)throw ApiException.forbidden();}
}

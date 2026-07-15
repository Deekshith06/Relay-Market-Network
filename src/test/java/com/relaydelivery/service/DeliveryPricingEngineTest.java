package com.relaydelivery.service;

import com.relaydelivery.dao.PricingDao.Coupon;
import com.relaydelivery.model.Models.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryPricingEngineTest {
    private final DeliveryPricingEngine engine=new DeliveryPricingEngine();
    private final List<PricingRule> rules=List.of(
        rule(RuleType.BASE_DELIVERY,null,null,null,null,"39","0","0",1),
        rule(RuleType.DISTANCE,null,null,"0","3","0","0","0",1),
        rule(RuleType.DISTANCE,null,null,"3","7","0","8","0",2),
        rule(RuleType.DISTANCE,null,null,"7","15","0","11","0",3),
        rule(RuleType.WEIGHT,null,null,"0","1","0","0","0",1),
        rule(RuleType.WEIGHT,null,null,"1","3","0","12","0",2),
        rule(RuleType.WEIGHT,null,null,"3","5","0","18","0",3),
        rule(RuleType.CATEGORY_HANDLING,1L,null,null,null,"35","0","0",1),
        rule(RuleType.CATEGORY_HANDLING,8L,null,null,null,"12","0","0",1),
        rule(RuleType.CATEGORY_PACKAGING,1L,null,null,null,"20","0","0",1),
        rule(RuleType.FRAGILE,null,null,null,null,"45","0","0",1),
        rule(RuleType.INSURANCE,null,null,null,null,"0","0","1.5",1),
        rule(RuleType.PRIORITY,null,"EXPRESS",null,null,"0","0","20",1),
        rule(RuleType.GIFT_WRAP,null,"STANDARD",null,null,"49","0","0",1),
        rule(RuleType.SCHEDULED_DELIVERY,null,null,null,null,"39","0","0",1),
        rule(RuleType.PLATFORM_FEE,null,null,null,null,"12","0","0",1),
        rule(RuleType.TAX,null,null,null,null,"0","0","5",1));

    @Test void mixedCartComposesProgressiveAndSpecialFees(){
        var calculation=engine.calculate(List.of(
            item(1,1,"Electronics","Phone",2,"0.20","200",true,true),
            item(8,8,"Groceries","Apples",1,"1.03","180",false,false)),
            new BigDecimal("8"),Priority.EXPRESS,new GiftOptions(true,"Recipient","+919999999999","BIRTHDAY","Hi","Sender",false,true,"STANDARD","MINIMAL",true,false,"Door"),
            true,rules,new Coupon("WELCOME10",new BigDecimal("10"),new BigDecimal("150"),new BigDecimal("100")));
        assertEquals(new BigDecimal("43.00"),calculation.distanceCharge());
        assertEquals(new BigDecimal("5.16"),calculation.weightCharge());
        assertEquals(new BigDecimal("380.00"),calculation.productSubtotal());
        assertEquals(new BigDecimal("49.00"),calculation.giftCharge());
        assertEquals(new BigDecimal("39.00"),calculation.schedulingCharge());
        assertTrue(calculation.categoryCharges().stream().anyMatch(x->x.type().equals("CATEGORY_PACKAGING")));
        assertTrue(calculation.specialCharges().stream().anyMatch(x->x.type().equals("INSURANCE")));
        assertTrue(calculation.tax().signum()>0);assertEquals(new BigDecimal("38.00"),calculation.discount());
        assertEquals(2,calculation.finalTotal().scale());
    }

    @Test void longerDistanceAndHeavierWeightCostMoreAtTierBoundaries(){
        var item=List.of(item(8,8,"Groceries","Rice",1,"1.00","155",false,false));
        var shortLight=engine.calculate(item,new BigDecimal("3"),Priority.STANDARD,null,false,rules,null);
        var longLight=engine.calculate(item,new BigDecimal("7.01"),Priority.STANDARD,null,false,rules,null);
        var heavy=engine.calculate(List.of(item(8,8,"Groceries","Rice",1,"4.00","620",false,false)),new BigDecimal("3"),Priority.STANDARD,null,false,rules,null);
        assertEquals(new BigDecimal("0.00"),shortLight.distanceCharge());
        assertTrue(longLight.distanceCharge().compareTo(shortLight.distanceCharge())>0);
        assertTrue(heavy.weightCharge().compareTo(shortLight.weightCharge())>0);
    }

    private static PricedItem item(long product,long category,String categoryName,String name,int quantity,String weight,String subtotal,boolean fragile,boolean insurance){
        return new PricedItem(product,null,category,categoryName,name,null,quantity,null,BigDecimal.valueOf(quantity),new BigDecimal(subtotal).divide(BigDecimal.valueOf(quantity)),null,
            new BigDecimal(weight),new BigDecimal(subtotal),fragile,false,insurance,null,new BigDecimal("25"));
    }
    private static PricingRule rule(RuleType type,Long category,String applies,String min,String max,String flat,String per,String percent,int priority){
        return new PricingRule(priority,type,category,applies,decimal(min),decimal(max),new BigDecimal(flat),new BigDecimal(per),new BigDecimal(percent),priority,true);
    }
    private static BigDecimal decimal(String value){return value==null?null:new BigDecimal(value);}
}

package com.relaydelivery.service;

import com.relaydelivery.dao.PricingDao.Coupon;
import com.relaydelivery.model.Models.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public final class DeliveryPricingEngine {
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    public record Calculation(BigDecimal productSubtotal,BigDecimal baseDeliveryFee,
        BigDecimal distanceKm,BigDecimal distanceCharge,BigDecimal totalWeightKg,
        BigDecimal weightCharge,List<ChargeLine> categoryCharges,List<ChargeLine> specialCharges,
        BigDecimal giftCharge,BigDecimal schedulingCharge,BigDecimal platformFee,BigDecimal tax,
        BigDecimal discount,BigDecimal totalDeliveryCharge,BigDecimal finalTotal,List<ChargeLine> lines){}

    /** Rules are data; this service only composes applicable values with decimal-safe rounding. */
    public Calculation calculate(List<PricedItem> items,BigDecimal distanceKm,Priority priority,
                                 GiftOptions gift,boolean scheduled,List<PricingRule> rules,Coupon coupon){
        BigDecimal productSubtotal=money(items.stream().map(PricedItem::lineSubtotal).reduce(BigDecimal.ZERO,BigDecimal::add));
        BigDecimal totalWeight=items.stream().map(i->i.unitWeightKg().multiply(BigDecimal.valueOf(i.quantity())))
            .reduce(BigDecimal.ZERO,BigDecimal::add).setScale(3,RoundingMode.HALF_UP);
        List<ChargeLine> lines=new ArrayList<>();int order=1;
        lines.add(new ChargeLine("PRODUCT_SUBTOTAL","Products",productSubtotal,order++));

        BigDecimal base=flat(rules,RuleType.BASE_DELIVERY,null,null);lines.add(new ChargeLine("BASE_DELIVERY","Base delivery",base,10));
        BigDecimal distance=tiered(distanceKm,rules,RuleType.DISTANCE);lines.add(new ChargeLine("DISTANCE","Distance charge",distance,20));
        BigDecimal weight=tiered(totalWeight,rules,RuleType.WEIGHT);lines.add(new ChargeLine("WEIGHT","Weight charge",weight,30));

        List<ChargeLine> categoryLines=new ArrayList<>();Set<Long> categories=new LinkedHashSet<>();
        Map<Long,String> names=new HashMap<>();for(PricedItem item:items){categories.add(item.categoryId());names.put(item.categoryId(),item.categoryName());}
        int categoryOrder=40;
        for(long categoryId:categories){
            BigDecimal handling=flat(rules,RuleType.CATEGORY_HANDLING,categoryId,null);
            if(handling.signum()>0)categoryLines.add(new ChargeLine("CATEGORY_HANDLING",names.get(categoryId)+" handling",handling,categoryOrder++));
            BigDecimal packaging=flat(rules,RuleType.CATEGORY_PACKAGING,categoryId,null);
            if(packaging.signum()>0)categoryLines.add(new ChargeLine("CATEGORY_PACKAGING",names.get(categoryId)+" packaging",packaging,categoryOrder++));
        }
        lines.addAll(categoryLines);

        List<ChargeLine> special=new ArrayList<>();int specialOrder=60;
        if(items.stream().anyMatch(PricedItem::fragile))special.add(new ChargeLine("FRAGILE","Fragile handling",flat(rules,RuleType.FRAGILE,null,null),specialOrder++));
        if(items.stream().anyMatch(PricedItem::temperatureControlled))special.add(new ChargeLine("TEMPERATURE_CONTROL","Temperature control",flat(rules,RuleType.TEMPERATURE_CONTROL,null,null),specialOrder++));
        if(items.stream().anyMatch(PricedItem::requiresInsurance)){
            BigDecimal insurance=percent(productSubtotal,percentage(rules,RuleType.INSURANCE,null));
            special.add(new ChargeLine("INSURANCE","Transit insurance",insurance,specialOrder++));
        }
        lines.addAll(special);
        BigDecimal categoryTotal=sum(categoryLines),specialBeforePriority=sum(special);
        BigDecimal deliveryBeforePriority=base.add(distance).add(weight).add(categoryTotal).add(specialBeforePriority);
        BigDecimal priorityFee=priority==Priority.STANDARD?BigDecimal.ZERO:percent(deliveryBeforePriority,percentage(rules,RuleType.PRIORITY,priority.name()));
        if(priorityFee.signum()>0){ChargeLine line=new ChargeLine("PRIORITY",title(priority.name())+" delivery",priorityFee,80);special.add(line);lines.add(line);}

        boolean giftEnabled=gift!=null&&Boolean.TRUE.equals(gift.enabled());
        BigDecimal giftCharge=giftEnabled?flat(rules,RuleType.GIFT_WRAP,null,blank(gift.wrappingStyle(),"STANDARD")):BigDecimal.ZERO;
        if(giftCharge.signum()>0)lines.add(new ChargeLine("GIFT_WRAP",title(blank(gift.wrappingStyle(),"STANDARD"))+" gift wrap",giftCharge,90));
        BigDecimal schedule=scheduled?flat(rules,RuleType.SCHEDULED_DELIVERY,null,null):BigDecimal.ZERO;
        if(schedule.signum()>0)lines.add(new ChargeLine("SCHEDULED_DELIVERY","Scheduled delivery",schedule,91));
        BigDecimal platform=flat(rules,RuleType.PLATFORM_FEE,null,null);if(platform.signum()>0)lines.add(new ChargeLine("PLATFORM_FEE","Service fee",platform,92));

        BigDecimal deliveryTotal=money(deliveryBeforePriority.add(priorityFee).add(giftCharge).add(schedule).add(platform));
        BigDecimal beforeDiscount=productSubtotal.add(deliveryTotal);
        BigDecimal discount=BigDecimal.ZERO;
        if(coupon!=null&&productSubtotal.compareTo(coupon.minimumOrderValue())>=0)
            discount=money(percent(productSubtotal,coupon.percentage()).min(coupon.maximumDiscount()));
        if(discount.signum()>0)lines.add(new ChargeLine("DISCOUNT","Coupon "+coupon.code(),discount.negate(),110));
        BigDecimal taxable=beforeDiscount.subtract(discount).max(BigDecimal.ZERO);
        BigDecimal tax=percent(taxable,percentage(rules,RuleType.TAX,null));
        if(tax.signum()>0)lines.add(new ChargeLine("TAX","Tax",tax,100));
        BigDecimal finalTotal=money(taxable.add(tax));
        return new Calculation(productSubtotal,base,money(distanceKm),distance,totalWeight,weight,List.copyOf(categoryLines),
            List.copyOf(special),giftCharge,schedule,platform,tax,discount,deliveryTotal,finalTotal,List.copyOf(lines));
    }

    /** Progressive tiers charge only the portion inside each configured interval. */
    static BigDecimal tiered(BigDecimal value,List<PricingRule> rules,RuleType type){
        BigDecimal result=BigDecimal.ZERO;
        for(PricingRule rule:rules.stream().filter(r->r.type()==type&&r.active()).sorted(Comparator.comparingInt(PricingRule::priority)).toList()){
            BigDecimal min=rule.minimumValue()==null?BigDecimal.ZERO:rule.minimumValue();
            BigDecimal max=rule.maximumValue()==null?value:rule.maximumValue().min(value);
            BigDecimal portion=max.subtract(min).max(BigDecimal.ZERO);
            if(portion.signum()>0)result=result.add(rule.flatAmount()).add(portion.multiply(rule.perUnitAmount()));
        }return money(result);
    }
    private static BigDecimal flat(List<PricingRule> rules,RuleType type,Long category,String applies){
        return money(rules.stream().filter(r->r.active()&&r.type()==type&&Objects.equals(r.categoryId(),category)
            &&(applies==null?r.appliesTo()==null:applies.equalsIgnoreCase(r.appliesTo()))).map(PricingRule::flatAmount).reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    private static BigDecimal percentage(List<PricingRule> rules,RuleType type,String applies){
        return rules.stream().filter(r->r.active()&&r.type()==type&&(applies==null?r.appliesTo()==null:applies.equalsIgnoreCase(r.appliesTo())))
            .map(PricingRule::percentageAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private static BigDecimal percent(BigDecimal value,BigDecimal percentage){return money(value.multiply(percentage).divide(HUNDRED,8,RoundingMode.HALF_UP));}
    private static BigDecimal sum(List<ChargeLine> values){return values.stream().map(ChargeLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static BigDecimal money(BigDecimal value){return value.setScale(2,RoundingMode.HALF_UP);}
    private static String blank(String value,String fallback){return value==null||value.isBlank()?fallback:value.toUpperCase(Locale.ROOT);}
    private static String title(String value){String s=value.toLowerCase(Locale.ROOT).replace('_',' ');return Character.toUpperCase(s.charAt(0))+s.substring(1);}
}

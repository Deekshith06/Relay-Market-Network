package com.relaydelivery.service;

import com.relaydelivery.dao.CatalogDao;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.util.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.*;

public final class ProductPricingService {
    private final CatalogDao catalog;
    public ProductPricingService(CatalogDao catalog){this.catalog=catalog;}

    public List<PricedItem> price(List<CartItemRequest> requests)throws SQLException{
        if(requests==null||requests.isEmpty())throw ApiException.badRequest("Cart is empty");
        if(requests.size()>40)throw ApiException.badRequest("Cart contains too many lines");
        Map<Long,Category> categories=new HashMap<>();for(Category c:catalog.categories(false))categories.put(c.id(),c);
        List<PricedItem> items=new ArrayList<>();Map<String,BigDecimal> demand=new HashMap<>();Map<String,BigDecimal> stock=new HashMap<>();
        for(CartItemRequest request:requests){
            if(request==null||request.productId()<=0)throw ApiException.badRequest("Cart contains an invalid product");
            Product p=catalog.product(request.productId(),false).orElseThrow(()->ApiException.conflict("A cart product is no longer available"));
            Category category=categories.get(p.categoryId());if(category==null)throw ApiException.conflict("Product category is unavailable");
            int quantity=request.quantity()==null?1:request.quantity();
            if(quantity<=0||quantity>category.maxQuantity())throw ApiException.badRequest(p.name()+" quantity is outside its allowed range");
            ProductVariant variant=null;
            if(p.pricingType()==PricingType.VARIANT){
                Long variantId=request.variantId();
                variant=p.variants().stream().filter(v->variantId!=null&&v.id()==variantId&&v.active()).findFirst()
                    .orElseThrow(()->ApiException.conflict("Select an available "+p.name()+" variant"));
            }else if(request.variantId()!=null)throw ApiException.badRequest(p.name()+" does not accept a variant");

            BigDecimal unitPrice=null,pricePerKg=null,selectedWeight=null,stockDemand,lineWeight,lineSubtotal;
            String variantLabel=null;BigDecimal available;
            if(p.pricingType()==PricingType.WEIGHT){
                if(quantity!=1)throw ApiException.badRequest("Weight-based products use one selected weight per cart line");
                selectedWeight=request.selectedWeightKg();validateWeight(p.name(),selectedWeight,p.minimumOrderWeight(),p.maximumOrderWeight());
                pricePerKg=p.pricePerKg();stockDemand=selectedWeight;lineWeight=selectedWeight.add(p.packagingWeightKg());
                lineSubtotal=pricePerKg.multiply(selectedWeight);available=p.stockQuantity();
            }else if(p.pricingType()==PricingType.VARIANT){
                variantLabel=variant.label();available=variant.stockQuantity();
                if(variant.unitPrice()!=null){
                    unitPrice=variant.unitPrice();stockDemand=BigDecimal.valueOf(quantity);
                    lineWeight=variant.unitWeightKg().add(p.packagingWeightKg());lineSubtotal=unitPrice.multiply(BigDecimal.valueOf(quantity));
                }else{
                    if(quantity!=1)throw ApiException.badRequest("Weight-based variants use one selected weight per cart line");
                    selectedWeight=request.selectedWeightKg();validateWeight(p.name(),selectedWeight,variant.minimumOrderWeight(),variant.maximumOrderWeight());
                    pricePerKg=variant.pricePerKg();stockDemand=selectedWeight;lineWeight=selectedWeight.add(p.packagingWeightKg());
                    lineSubtotal=pricePerKg.multiply(selectedWeight);
                }
            }else{
                unitPrice=p.unitPrice();stockDemand=BigDecimal.valueOf(quantity);available=p.stockQuantity();
                lineWeight=p.unitWeightKg().add(p.packagingWeightKg());lineSubtotal=unitPrice.multiply(BigDecimal.valueOf(quantity));
            }
            String key=p.id()+":"+(variant==null?"-":variant.id());
            demand.merge(key,stockDemand,BigDecimal::add);stock.put(key,available);
            items.add(new PricedItem(p.id(),variant==null?null:variant.id(),p.categoryId(),category.name(),p.name(),variantLabel,quantity,
                selectedWeight,stockDemand,unitPrice,pricePerKg,lineWeight,lineSubtotal.setScale(2,RoundingMode.HALF_UP),
                p.fragile()||category.fragileDefault(),p.temperatureControlled()||category.temperatureControlledDefault(),
                p.requiresInsurance()||category.requiresInsuranceDefault(),p.shelfLifeDays(),category.maxDeliveryDistanceKm()));
        }
        demand.forEach((key,value)->{if(value.compareTo(stock.get(key))>0)throw ApiException.conflict("Requested quantity exceeds current stock");});
        return List.copyOf(items);
    }

    private static void validateWeight(String product,BigDecimal value,BigDecimal minimum,BigDecimal maximum){
        if(value==null||value.signum()<=0||minimum==null||maximum==null||value.compareTo(minimum)<0||value.compareTo(maximum)>0)
            throw ApiException.badRequest(product+" weight must be between "+minimum+" and "+maximum+" kg");
        if(value.scale()>3)throw ApiException.badRequest("Weight supports at most three decimal places");
    }
}

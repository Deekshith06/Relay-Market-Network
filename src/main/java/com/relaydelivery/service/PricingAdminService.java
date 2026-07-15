package com.relaydelivery.service;

import com.relaydelivery.dao.PricingDao;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.util.ApiException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

public final class PricingAdminService {
    private final PricingDao pricing;public PricingAdminService(PricingDao pricing){this.pricing=pricing;}
    public List<PricingRule> list(User actor)throws SQLException{admin(actor);return pricing.rules(true);}
    public PricingRule update(User actor,long id,PricingRule value)throws SQLException{
        admin(actor);validate(value);PricingRule updated=pricing.updateRule(actor.id(),id,value);if(updated==null)throw ApiException.notFound("Pricing rule");return updated;
    }
    private static void validate(PricingRule v){
        if(v==null||v.type()==null)throw ApiException.badRequest("Pricing rule type is required");
        nonnegative(v.flatAmount(),"Flat amount");nonnegative(v.perUnitAmount(),"Per-unit amount");nonnegative(v.percentageAmount(),"Percentage");
        if(v.minimumValue()!=null&&v.maximumValue()!=null&&v.minimumValue().compareTo(v.maximumValue())>=0)throw ApiException.badRequest("Pricing range is invalid");
    }
    private static void nonnegative(BigDecimal v,String label){if(v==null||v.signum()<0)throw ApiException.badRequest(label+" is invalid");}
    private static void admin(User u){if(u.role()!=Role.ADMIN)throw ApiException.forbidden();}
}

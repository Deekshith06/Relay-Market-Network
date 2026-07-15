package com.relaydelivery.service;

import com.relaydelivery.dao.AddressDao;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.util.ApiException;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

public final class AddressService {
    private final AddressDao addresses;public AddressService(AddressDao addresses){this.addresses=addresses;}
    public List<Address> list(User actor)throws SQLException{customer(actor);return addresses.list(actor.id());}
    public Address save(User actor,Long id,AddressInput value)throws SQLException{
        customer(actor);validate(value);Address result=addresses.save(actor.id(),id,value);if(result==null)throw ApiException.notFound("Address");return result;
    }
    public void delete(User actor,long id)throws SQLException{customer(actor);if(!addresses.delete(actor.id(),id))throw ApiException.notFound("Address");}
    private static void validate(AddressInput v){
        if(v==null||!Set.of("HOME","WORK","OTHER").contains(v.label()==null?"":v.label().toUpperCase()))throw ApiException.badRequest("Address label is invalid");
        int length=v.addressLine()==null?0:v.addressLine().trim().length();if(length<5||length>240)throw ApiException.badRequest("Address must be 5-240 characters");
        if(v.landmark()!=null&&v.landmark().length()>120||v.instructions()!=null&&v.instructions().length()>300)throw ApiException.badRequest("Address notes are too long");
        if(!Double.isFinite(v.latitude())||!Double.isFinite(v.longitude())||v.latitude()<-90||v.latitude()>90||v.longitude()<-180||v.longitude()>180)
            throw ApiException.badRequest("Address coordinates are invalid");if(v.zoneId()<=0)throw ApiException.badRequest("Select a service zone");
    }
    private static void customer(User actor){if(actor.role()!=Role.CUSTOMER)throw ApiException.forbidden();}
}

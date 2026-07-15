package com.relaydelivery.service;

import com.relaydelivery.dao.AgentDao;
import com.relaydelivery.dao.OrderDao;
import com.relaydelivery.dao.TrackingDao;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.util.ApiException;

import java.sql.SQLException;
import java.time.*;
import java.util.*;

public final class LocationTrackingService {
    private final TrackingDao tracking;private final AgentDao agents;private final OrderDao orders;private final EtaService eta;
    private final DeliveryVerificationService verification;
    public LocationTrackingService(TrackingDao tracking,AgentDao agents,OrderDao orders,EtaService eta,
                                   DeliveryVerificationService verification){
        this.tracking=tracking;this.agents=agents;this.orders=orders;this.eta=eta;this.verification=verification;
    }

    public Map<String,Object> update(User actor,LocationUpdate value)throws SQLException{
        if(actor.role()!=Role.AGENT)throw ApiException.forbidden();
        Agent agent=agents.findByUser(actor.id()).orElseThrow(ApiException::forbidden);
        List<Order> active=orders.activeForAgent(agent.id());
        Order order;
        if(value!=null&&value.orderId()!=null)order=active.stream().filter(o->o.id()==value.orderId()).findFirst().orElseThrow(()->ApiException.forbidden());
        else if(active.size()==1)order=active.get(0);else throw ApiException.badRequest("Select the active order receiving this location");
        AgentLocation previous=tracking.currentForAgent(agent.id()).orElse(null);
        Instant recorded=validateUpdate(value,previous,Instant.now());
        tracking.save(agent.id(),order.id(),value,recorded);agents.updateCoordinates(agent.id(),value.latitude(),value.longitude());
        return Map.of("ok",true,"orderId",order.id(),"recordedAt",recorded.toString());
    }

    public Map<String,Object> stop(User actor)throws SQLException{
        if(actor.role()!=Role.AGENT)throw ApiException.forbidden();Agent agent=agents.findByUser(actor.id()).orElseThrow(ApiException::forbidden);
        tracking.stop(agent.id());return Map.of("ok",true);
    }

    public List<Map<String,Object>> liveDeliveries(User actor)throws SQLException{
        if(actor.role()!=Role.ADMIN)throw ApiException.forbidden();return tracking.liveDeliveries();
    }

    public Map<String,Object> tracking(User actor,long orderId)throws SQLException{
        Order order=orders.find(orderId).orElseThrow(()->ApiException.notFound("Order"));authorize(actor,order);
        Agent agent=order.agentId()==null?null:agents.findById(order.agentId()).orElse(null);
        AgentLocation location=tracking.currentForOrder(orderId).orElse(null);
        boolean active=Set.of(OrderStatus.ASSIGNED,OrderStatus.PICKED_UP,OrderStatus.OUT_FOR_DELIVERY,
            OrderStatus.DELIVERY_VERIFICATION).contains(order.status());
        boolean stale=location==null||Instant.parse(location.recordedAt()).isBefore(Instant.now().minus(Duration.ofSeconds(30)));
        Map<String,Object> data=new LinkedHashMap<>();data.put("orderId",order.id());data.put("status",order.status());
        data.put("pickup",Map.of("latitude",order.pickupLat(),"longitude",order.pickupLng(),"address",order.pickupAddress()));
        data.put("drop",Map.of("latitude",order.dropLat(),"longitude",order.dropLng(),"address",order.dropAddress()));
        data.put("agent",agent==null?null:Map.of("name",agent.name(),"vehicleType",agent.vehicleType(),"maskedContact",mask(agent.phone())));
        data.put("location",active&&location!=null?location:null);data.put("stale",stale);data.put("sharing",active&&location!=null);
        data.put("lastUpdatedAt",location==null?null:location.recordedAt());data.put("timeline",tracking.timeline(orderId));
        String deliveryCode=verification.customerCode(actor,order);
        if(deliveryCode!=null){data.put("deliveryCode",deliveryCode);data.put("deliveryCodeMessage","Share this code only when you receive your order.");}
        if(agent!=null){EtaService.Estimate estimate=eta.estimate(order,agent,location);data.put("remainingDistanceKm",estimate.remainingDistanceKm());
            data.put("eta",Map.of("minimumMinutes",estimate.minimumMinutes(),"maximumMinutes",estimate.maximumMinutes(),
                "label",estimate.minimumMinutes()+"-"+estimate.maximumMinutes()+" minutes"));}
        data.put("externalMapUrl","https://www.openstreetmap.org/?mlat="+order.dropLat()+"&mlon="+order.dropLng()+"#map=15/"+order.dropLat()+"/"+order.dropLng());
        return data;
    }

    public static Instant validateUpdate(LocationUpdate v,AgentLocation previous,Instant now){
        if(v==null)throw ApiException.badRequest("Location details are required");
        if(!Double.isFinite(v.latitude())||!Double.isFinite(v.longitude())||v.latitude()<-90||v.latitude()>90||v.longitude()<-180||v.longitude()>180)
            throw ApiException.badRequest("Coordinates are invalid");
        if(!Double.isFinite(v.accuracyMeters())||v.accuracyMeters()<0||v.accuracyMeters()>5000)throw ApiException.badRequest("Location accuracy is invalid");
        if(v.heading()!=null&&(!Double.isFinite(v.heading())||v.heading()<0||v.heading()>360))throw ApiException.badRequest("Heading is invalid");
        if(v.speedMetersPerSecond()!=null&&(!Double.isFinite(v.speedMetersPerSecond())||v.speedMetersPerSecond()<0||v.speedMetersPerSecond()>60))
            throw ApiException.badRequest("Reported speed is unrealistic");
        Instant recorded=parse(v.recordedAt());if(recorded.isBefore(now.minus(Duration.ofMinutes(2)))||recorded.isAfter(now.plus(Duration.ofSeconds(30))))
            throw ApiException.badRequest("Location timestamp is stale or in the future");
        if(previous!=null){
            Instant prior=Instant.parse(previous.recordedAt());if(!recorded.isAfter(prior))throw ApiException.conflict("Duplicate or stale location update");
            double seconds=Math.max(0.001,Duration.between(prior,recorded).toMillis()/1000d);
            double meters=AgentAssignmentService.haversine(previous.latitude(),previous.longitude(),v.latitude(),v.longitude())*1000;
            if(meters/seconds>60)throw ApiException.badRequest("Location jump implies an unrealistic speed");
        }return recorded;
    }

    private void authorize(User actor,Order order)throws SQLException{
        if(actor.role()==Role.ADMIN||actor.role()==Role.CUSTOMER&&order.customerId()==actor.id())return;
        if(actor.role()==Role.AGENT&&order.agentId()!=null){Agent agent=agents.findByUser(actor.id()).orElseThrow(ApiException::forbidden);if(agent.id()==order.agentId())return;}
        throw ApiException.forbidden();
    }
    private static Instant parse(String value){try{return Instant.parse(value);}catch(Exception e){throw ApiException.badRequest("Location timestamp is invalid");}}
    private static String mask(String phone){if(phone==null||phone.length()<4)return "Unavailable";return "******"+phone.substring(phone.length()-4);}
}

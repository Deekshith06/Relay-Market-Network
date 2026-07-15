package com.relaydelivery.service;

import com.relaydelivery.model.Models.*;

public final class EtaService {
    public record Estimate(double remainingDistanceKm,int minimumMinutes,int maximumMinutes){}
    public Estimate estimate(Order order,Agent agent,AgentLocation location){
        double lat=location==null?agent.latitude():location.latitude(),lng=location==null?agent.longitude():location.longitude();
        double remaining=AgentAssignmentService.haversine(lat,lng,order.dropLat(),order.dropLng())*1.18;
        double speed=location!=null&&location.speedMetersPerSecond()!=null&&location.speedMetersPerSecond()>1
            ?location.speedMetersPerSecond():fallbackSpeed(agent.vehicleType());
        double trafficMultiplier=1.25;double handling=order.status()==OrderStatus.ASSIGNED?10:order.status()==OrderStatus.PICKED_UP?4:0;
        double priorityFactor=order.priority()==Priority.CRITICAL?.85:order.priority()==Priority.EXPRESS?.92:1;
        double minutes=(remaining*1000/speed/60*trafficMultiplier+handling)*priorityFactor;
        int min=Math.max(2,(int)Math.floor(minutes*.9)),max=Math.max(min+2,(int)Math.ceil(minutes*1.18));
        return new Estimate(Math.round(remaining*10d)/10d,min,max);
    }
    private static double fallbackSpeed(String vehicle){return switch(vehicle.toUpperCase()){
        case "BICYCLE"->4.2;case "MOTORCYCLE","SCOOTER"->7.5;case "CAR"->6.5;case "VAN"->6;default->5.5;};}
}

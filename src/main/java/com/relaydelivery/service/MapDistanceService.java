package com.relaydelivery.service;

import com.relaydelivery.model.Models.GeoPoint;
import com.relaydelivery.model.Models.Route;
import com.relaydelivery.util.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class MapDistanceService {
    public record Distance(BigDecimal kilometers,List<Long> zonePath,String source){}
    private final RouteOptimizer routes;
    public MapDistanceService(RouteOptimizer routes){this.routes=routes;}

    public Distance distance(GeoPoint pickup,GeoPoint drop){
        try{
            Route route=routes.shortest(pickup.zoneId(),drop.zoneId());
            return new Distance(BigDecimal.valueOf(route.distanceKm()).setScale(2,RoundingMode.HALF_UP),route.zoneIds(),"ZONE_ROUTE");
        }catch(ApiException unavailable){
            if(unavailable.status()!=422)throw unavailable;
            // A road graph is preferred; Haversine with a road factor keeps quoting available when it is disconnected.
            double direct=AgentAssignmentService.haversine(pickup.latitude(),pickup.longitude(),drop.latitude(),drop.longitude());
            return new Distance(BigDecimal.valueOf(direct*1.18).setScale(2,RoundingMode.HALF_UP),
                pickup.zoneId()==drop.zoneId()?List.of(pickup.zoneId()):List.of(pickup.zoneId(),drop.zoneId()),"HAVERSINE_FALLBACK");
        }
    }
}

package com.relaydelivery.service;

import com.relaydelivery.model.Models.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentAssignmentServiceTest {
    @Test void haversineValidatesAndMeasuresCoordinates(){
        assertEquals(4.46,AgentAssignmentService.haversine(12.9719,77.6412,12.9352,77.6245),.15);
        assertEquals(0,AgentAssignmentService.haversine(12,77,12,77),.0001);
        assertEquals(9_337,AgentAssignmentService.haversine(12,77,-33.87,151.21),50);
        assertThrows(IllegalArgumentException.class,()->AgentAssignmentService.haversine(91,77,12,77));
    }

    @Test void rejectsOverloadAndHandlingMismatchThenChoosesNearestSuitable(){
        Agent overloaded=agent(1,12.9719,77.6412,AgentStatus.AVAILABLE,"8","7.5",2,1,true,true);
        Agent noCold=agent(2,12.9719,77.6412,AgentStatus.AVAILABLE,"20","0",3,0,true,false);
        Agent suitableNear=agent(3,12.9720,77.6412,AgentStatus.AVAILABLE,"20","2",3,1,true,true);
        Agent suitableFar=agent(4,13.1,77.7,AgentStatus.AVAILABLE,"20","2",3,1,true,true);
        assertEquals(3,AgentAssignmentService.selectBest(List.of(overloaded,noCold,suitableFar,suitableNear),
            12.9719,77.6412,new BigDecimal("2"),true,true).orElseThrow().id());
    }

    @Test void availabilityAndStableIdBreakTiesDeterministically(){
        Agent busyNear=agent(2,12,77,AgentStatus.BUSY,"20","0",3,1,true,true);
        Agent available=agent(3,12.01,77,AgentStatus.AVAILABLE,"20","0",3,0,true,true);
        assertEquals(3,AgentAssignmentService.selectBest(List.of(busyNear,available),12,77,BigDecimal.ONE,false,false).orElseThrow().id());
        Agent high=agent(9,12,77,AgentStatus.AVAILABLE,"20","0",3,0,true,true);
        Agent low=agent(4,12,77,AgentStatus.AVAILABLE,"20","0",3,0,true,true);
        assertEquals(4,AgentAssignmentService.selectBest(List.of(high,low),12,77,BigDecimal.ONE,false,false).orElseThrow().id());
    }

    private static Agent agent(long id,double lat,double lng,AgentStatus status,String capacity,String load,
                               int maxOrders,int active,boolean fragile,boolean cold){
        return new Agent(id,id,"Agent "+id,"+910000000000",lat,lng,status,1,"Bicycle",new BigDecimal(capacity),
            new BigDecimal(load),maxOrders,active,fragile,cold,"carrier",new BigDecimal("4.8"));
    }
}

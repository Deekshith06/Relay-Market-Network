package com.relaydelivery.service;

import com.relaydelivery.dao.AgentDao;
import com.relaydelivery.dao.OrderDao;
import com.relaydelivery.model.Models.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public final class AgentAssignmentService {
    private record Candidate(Agent agent,int availabilityRank,BigDecimal capacityWaste,double distance){}
    private final AgentDao agents;private final OrderDao orders;
    public AgentAssignmentService(AgentDao agents,OrderDao orders){this.agents=agents;this.orders=orders;}

    /** O(a log a) time and O(a) space for a capacity-suitable agents in the Min-Heap. */
    public OptionalLong assign(Connection c,Order order)throws SQLException{
        PriorityQueue<Candidate> heap=candidates(agents.capacityCandidates(c,order),order.pickupLat(),order.pickupLng(),order.totalWeightKg(),order.fragile(),order.temperatureControlled());
        while(!heap.isEmpty()){
            Agent winner=heap.poll().agent();
            if(!agents.claimCapacity(c,winner.id(),order.totalWeightKg()))continue;
            if(orders.assign(c,order.id(),winner.id())){orders.logAssignment(c,order.id(),null,winner.id(),null,"AUTO_ASSIGN");return OptionalLong.of(winner.id());}
            agents.syncLoad(c,winner.id());return OptionalLong.empty();
        }return OptionalLong.empty();
    }

    public static Optional<Agent> selectBest(Collection<Agent> values,double pickupLat,double pickupLng,
                                             BigDecimal packageWeight,boolean fragile,boolean temperatureControlled){
        Candidate winner=candidates(values,pickupLat,pickupLng,packageWeight,fragile,temperatureControlled).poll();
        return winner==null?Optional.empty():Optional.of(winner.agent());
    }

    private static PriorityQueue<Candidate> candidates(Collection<Agent> values,double pickupLat,double pickupLng,
                                                        BigDecimal packageWeight,boolean fragile,boolean temperatureControlled){
        PriorityQueue<Candidate> heap=new PriorityQueue<>(Comparator
            .comparingInt(Candidate::availabilityRank).thenComparing(Candidate::capacityWaste)
            .thenComparingDouble(Candidate::distance).thenComparingInt(x->x.agent().currentActiveOrders())
            .thenComparing(x->x.agent().currentLoadKg()).thenComparing((Candidate x)->x.agent().rating(),Comparator.reverseOrder())
            .thenComparingLong(x->x.agent().id()));
        for(Agent agent:values){
            if(agent.status()==AgentStatus.OFFLINE||agent.currentActiveOrders()>=agent.maximumActiveOrders())continue;
            BigDecimal remaining=agent.maximumCapacityKg().subtract(agent.currentLoadKg()).subtract(packageWeight);
            if(remaining.signum()<0||(fragile&&!agent.supportsFragile())||(temperatureControlled&&!agent.supportsTemperatureControl()))continue;
            heap.add(new Candidate(agent,agent.status()==AgentStatus.AVAILABLE?0:1,remaining,
                haversine(agent.latitude(),agent.longitude(),pickupLat,pickupLng)));
        }return heap;
    }

    public static double haversine(double lat1,double lng1,double lat2,double lng2){
        validateCoordinates(lat1,lng1);validateCoordinates(lat2,lng2);
        double dLat=Math.toRadians(lat2-lat1),dLng=Math.toRadians(lng2-lng1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*
            Math.sin(dLng/2)*Math.sin(dLng/2);
        return 6371*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
    }
    private static void validateCoordinates(double lat,double lng){if(!Double.isFinite(lat)||!Double.isFinite(lng)||lat<-90||lat>90||lng<-180||lng>180)
        throw new IllegalArgumentException("Coordinates are outside valid latitude/longitude ranges");}
}

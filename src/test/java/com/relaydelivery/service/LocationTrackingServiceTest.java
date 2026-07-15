package com.relaydelivery.service;

import com.relaydelivery.model.Models.*;
import com.relaydelivery.util.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LocationTrackingServiceTest {
    @Test void acceptsFreshLocationAndRejectsInvalidOrStaleSignals(){
        Instant now=Instant.parse("2026-07-15T10:00:00Z");
        LocationUpdate valid=new LocationUpdate(4L,12.9719,77.6412,12,90d,6d,now.minusSeconds(5).toString());
        assertEquals(now.minusSeconds(5),LocationTrackingService.validateUpdate(valid,null,now));
        assertThrows(ApiException.class,()->LocationTrackingService.validateUpdate(
            new LocationUpdate(4L,91,77,10,null,null,now.toString()),null,now));
        assertThrows(ApiException.class,()->LocationTrackingService.validateUpdate(
            new LocationUpdate(4L,12,77,10,null,null,now.minusSeconds(121).toString()),null,now));
        assertThrows(ApiException.class,()->LocationTrackingService.validateUpdate(
            new LocationUpdate(4L,12,77,10,null,70d,now.toString()),null,now));
    }

    @Test void rejectsDuplicateAndImpossibleMovement(){
        Instant prior=Instant.parse("2026-07-15T09:59:50Z"),now=Instant.parse("2026-07-15T10:00:00Z");
        AgentLocation previous=new AgentLocation(1,4,12.9719,77.6412,10,null,2d,prior.toString(),prior.toString());
        assertThrows(ApiException.class,()->LocationTrackingService.validateUpdate(
            new LocationUpdate(4L,12.9719,77.6412,10,null,2d,prior.toString()),previous,now));
        assertThrows(ApiException.class,()->LocationTrackingService.validateUpdate(
            new LocationUpdate(4L,13.5,78.1,10,null,2d,now.toString()),previous,now));
    }
}

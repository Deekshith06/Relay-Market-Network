package com.relaydelivery.service;

import com.relaydelivery.model.Models.ZoneEdge;
import com.relaydelivery.util.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteOptimizerTest {
    private final RouteOptimizer routes = new RouteOptimizer(List.of(
        new ZoneEdge(1, 2, 5), new ZoneEdge(1, 3, 2),
        new ZoneEdge(3, 2, 1), new ZoneEdge(2, 4, 3), new ZoneEdge(5, 6, 1)
    ));

    @Test void findsShortestPathInsteadOfFewestEdges() {
        var route = routes.shortest(1, 4);
        assertEquals(6, route.distanceKm(), .001);
        assertEquals(List.of(1L, 3L, 2L, 4L), route.zoneIds());
    }

    @Test void handlesSameZoneAndDisconnectedZone() {
        assertEquals(0, routes.shortest(2, 2).distanceKm());
        assertThrows(ApiException.class, () -> routes.shortest(1, 5));
        assertThrows(ApiException.class, () -> routes.shortest(99, 99));
    }

    @Test void rejectsInvalidWeightsAndSelfEdges() {
        assertThrows(IllegalArgumentException.class,
            () -> new RouteOptimizer(List.of(new ZoneEdge(1, 2, -1))));
        assertThrows(IllegalArgumentException.class,
            () -> new RouteOptimizer(List.of(new ZoneEdge(1, 1, 2))));
    }
}

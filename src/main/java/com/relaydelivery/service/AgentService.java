package com.relaydelivery.service;

import com.relaydelivery.dao.AgentDao;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.util.ApiException;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

public final class AgentService {
    private final AgentDao agents;
    private final OrderService orders;

    public AgentService(AgentDao agents, OrderService orders) { this.agents = agents; this.orders = orders; }

    public List<Agent> available(User actor) throws SQLException {
        if (actor.role() != Role.ADMIN) throw ApiException.forbidden();
        return agents.listAvailable();
    }

    public Agent me(User actor) throws SQLException {
        if (actor.role() != Role.AGENT) throw ApiException.forbidden();
        return agents.findByUser(actor.id()).orElseThrow(() -> ApiException.notFound("Agent profile"));
    }

    public Agent setAvailability(User actor, String value, Double lat, Double lng) throws SQLException {
        if (actor.role() != Role.AGENT) throw ApiException.forbidden();
        AgentStatus status;
        try { status = AgentStatus.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw ApiException.badRequest("Status must be AVAILABLE or OFFLINE"); }
        if (status == AgentStatus.BUSY) throw ApiException.badRequest("Busy status is managed by assigned orders");
        if (lat != null && (!Double.isFinite(lat) || lat < -90 || lat > 90) ||
            lng != null && (!Double.isFinite(lng) || lng < -180 || lng > 180))
            throw ApiException.badRequest("Coordinates are invalid");
        try {
            agents.setAvailability(actor.id(), status, lat, lng);
        } catch (SQLException e) {
            if ("Agent is busy or missing".equals(e.getMessage()))
                throw ApiException.conflict("Finish active deliveries before going offline");
            throw e;
        }
        if (status == AgentStatus.AVAILABLE) orders.dispatchPending();
        return me(actor);
    }
}

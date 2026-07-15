package com.relaydelivery.service;

import com.relaydelivery.dao.OperationsDao;
import com.relaydelivery.model.Models.Role;
import com.relaydelivery.model.Models.User;
import com.relaydelivery.util.ApiException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public final class OperationsService {
    private final OperationsDao operations;
    public OperationsService(OperationsDao operations) { this.operations = operations; }
    public List<Map<String, Object>> zones() throws SQLException { return operations.zones(); }
    public Map<String, Object> analytics(User actor) throws SQLException {
        if (actor.role() != Role.ADMIN) throw ApiException.forbidden();
        return operations.analytics();
    }
    public Map<String, Object> pricingAnalytics(User actor) throws SQLException {
        if (actor.role() != Role.ADMIN) throw ApiException.forbidden();
        return operations.pricingAnalytics();
    }
}

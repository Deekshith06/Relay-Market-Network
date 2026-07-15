package com.relaydelivery;

import com.relaydelivery.config.Database;
import com.relaydelivery.controller.ApiController;
import com.relaydelivery.controller.StaticFileHandler;
import com.relaydelivery.dao.*;
import com.relaydelivery.service.*;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class App {
    public static void main(String[] args) throws Exception {
        Database db = new Database();
        db.verify();
        UserDao users = new UserDao(db);
        AgentDao agentDao = new AgentDao(db);
        OrderDao orderDao = new OrderDao(db);
        OperationsDao operationsDao = new OperationsDao(db);
        CatalogDao catalogDao = new CatalogDao(db);
        AddressDao addressDao = new AddressDao(db);
        PricingDao pricingDao = new PricingDao(db);
        InventoryDao inventoryDao = new InventoryDao();
        TrackingDao trackingDao = new TrackingDao(db);
        SchedulingDao schedulingDao = new SchedulingDao(db);
        SessionService sessions = new SessionService();
        RouteOptimizer routes = new RouteOptimizer(operationsDao.routes());
        MapDistanceService mapDistance = new MapDistanceService(routes);
        PendingOrderQueue pending = new PendingOrderQueue();
        AgentAssignmentService assignment = new AgentAssignmentService(agentDao, orderDao);
        ProductPricingService productPricing = new ProductPricingService(catalogDao);
        DeliveryPricingEngine pricingEngine = new DeliveryPricingEngine();
        GiftDeliveryService gifts = new GiftDeliveryService(schedulingDao, orderDao);
        QuoteService quotes = new QuoteService(productPricing, pricingEngine, pricingDao, mapDistance, gifts);
        InventoryService inventory = new InventoryService(inventoryDao);
        DeliveryVerificationService deliveryVerification = new DeliveryVerificationService(db, orderDao, agentDao,
            trackingDao, new DeliveryCodeProtector());
        OrderService orders = new OrderService(db, orderDao, agentDao, assignment, pending, mapDistance,
            quotes, inventory, catalogDao, trackingDao, gifts, deliveryVerification);
        deliveryVerification.ensureMissingCodes();
        orders.restorePending();
        orders.dispatchPending();
        AgentService agents = new AgentService(agentDao, orders);
        AuthService auth = new AuthService(db, users, agentDao, sessions);
        OperationsService operations = new OperationsService(operationsDao);
        ProductCatalogService catalog = new ProductCatalogService(catalogDao);
        AddressService addresses = new AddressService(addressDao);
        LocationTrackingService tracking = new LocationTrackingService(trackingDao, agentDao, orderDao,
            new EtaService(), deliveryVerification);
        PricingAdminService pricingAdmin = new PricingAdminService(pricingDao);
        LoginRateLimiter loginLimiter = new LoginRateLimiter(Duration.ofMinutes(15));
        ScheduledOrderProcessor scheduledOrders = new ScheduledOrderProcessor(db, orderDao, inventory, orders);
        scheduledOrders.runOnce();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        long pollSeconds = Long.parseLong(Database.env("SCHEDULE_POLL_SECONDS", "30"));
        scheduler.scheduleWithFixedDelay(scheduledOrders, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        int retentionDays = Integer.parseInt(Database.env("TRACKING_RETENTION_DAYS", "30"));
        scheduler.scheduleWithFixedDelay(() -> {
            try { trackingDao.purgeHistory(retentionDays); }
            catch (Exception e) { System.err.println("Tracking retention failed: " + e.getMessage()); }
        }, 1, 1, TimeUnit.HOURS);

        int port = Integer.parseInt(Database.env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", new ApiController(auth, sessions, orders, agents, operations,
            loginLimiter, catalog, quotes, addresses, gifts, tracking, pricingAdmin));
        server.createContext("/", new StaticFileHandler(Path.of(Database.env("FRONTEND_DIR", "frontend"))));
        ExecutorService httpExecutor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
        server.setExecutor(httpExecutor);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { scheduler.shutdownNow(); server.stop(1); httpExecutor.shutdownNow(); }));
        server.start();
        System.out.println("Relay Delivery running at http://localhost:" + port);
    }
}

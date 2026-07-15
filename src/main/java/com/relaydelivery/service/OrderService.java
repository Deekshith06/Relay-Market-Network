package com.relaydelivery.service;

import com.relaydelivery.config.Database;
import com.relaydelivery.dao.*;
import com.relaydelivery.dao.OrderDao.CreatedOrder;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.service.GiftDeliveryService.SchedulePlan;
import com.relaydelivery.service.QuoteService.VerifiedQuote;
import com.relaydelivery.util.ApiException;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class OrderService {
    private static final Map<OrderStatus,Set<OrderStatus>> TRANSITIONS=Map.of(
        OrderStatus.SCHEDULED,Set.of(OrderStatus.PLACED,OrderStatus.CANCELLED),
        OrderStatus.PLACED,Set.of(OrderStatus.CONFIRMED,OrderStatus.CANCELLED),
        OrderStatus.CONFIRMED,Set.of(OrderStatus.PACKED,OrderStatus.CANCELLED),
        OrderStatus.PACKED,Set.of(OrderStatus.ASSIGNED,OrderStatus.CANCELLED),
        OrderStatus.ASSIGNED,Set.of(OrderStatus.PICKED_UP,OrderStatus.CANCELLED),
        OrderStatus.PICKED_UP,Set.of(OrderStatus.OUT_FOR_DELIVERY),
        OrderStatus.OUT_FOR_DELIVERY,Set.of(OrderStatus.DELIVERY_VERIFICATION),
        OrderStatus.DELIVERY_VERIFICATION,Set.of(OrderStatus.DELIVERED),
        OrderStatus.DELIVERED,Set.of(),OrderStatus.CANCELLED,Set.of());
    private final Database db;private final OrderDao orders;private final AgentDao agents;
    private final AgentAssignmentService assignment;private final PendingOrderQueue pending;private final MapDistanceService distance;
    private final QuoteService quotes;private final InventoryService inventory;private final CatalogDao catalog;private final TrackingDao tracking;
    private final GiftDeliveryService gifts;private final DeliveryVerificationService verification;
    public OrderService(Database db,OrderDao orders,AgentDao agents,AgentAssignmentService assignment,
                        PendingOrderQueue pending,MapDistanceService distance,QuoteService quotes,
                        InventoryService inventory,CatalogDao catalog,TrackingDao tracking,GiftDeliveryService gifts,
                        DeliveryVerificationService verification){
        this.db=db;this.orders=orders;this.agents=agents;this.assignment=assignment;this.pending=pending;this.distance=distance;
        this.quotes=quotes;this.inventory=inventory;this.catalog=catalog;this.tracking=tracking;this.gifts=gifts;this.verification=verification;
    }

    public void restorePending()throws SQLException{pending.restore(orders.pendingIds());}

    public synchronized Order create(User actor,PlaceOrderRequest input,String idempotencyKey)throws SQLException{
        if(actor.role()!=Role.CUSTOMER)throw ApiException.forbidden();String key=validateIdempotencyKey(idempotencyKey);
        Optional<Order> existing=orders.findByIdempotency(actor.id(),key);if(existing.isPresent())return existing.get();
        if(input==null||input.quoteId()==null)throw ApiException.badRequest("A valid quote is required");
        long orderId;OrderStatus initial;
        try(Connection c=db.connection()){
            c.setAutoCommit(false);
            try{
                VerifiedQuote verified=quotes.lockAndVerify(c,actor,input.quoteId());SchedulePlan schedule=verified.schedule();
                initial=schedule==null?OrderStatus.PLACED:OrderStatus.SCHEDULED;
                if(schedule!=null)gifts.lockCapacity(c,schedule);
                CreatedOrder created=orders.create(c,actor.id(),key,verified.snapshot(),initial,
                    schedule==null?null:schedule.scheduledAt(),schedule==null?null:schedule.assignmentReleaseAt(),
                    schedule==null?null:schedule.windowStart(),schedule==null?null:schedule.windowEnd());
                Instant reservationExpiry=schedule==null?null:schedule.windowEnd().plusSeconds(7200);
                inventory.reserve(c,created.id(),verified.snapshot().items(),created.itemIds(),schedule!=null,reservationExpiry);
                verification.issue(c,created.id(),schedule==null?null:schedule.scheduledAt());
                quotes.markUsed(c,input.quoteId());catalog.clearCart(c,actor.id());c.commit();orderId=created.id();
            }catch(RuntimeException|SQLException e){
                c.rollback();
                if(e instanceof SQLException sql&&"23505".equals(sql.getSQLState())){
                    Optional<Order> duplicate=orders.findByIdempotency(actor.id(),key);if(duplicate.isPresent())return duplicate.get();
                }throw e;
            }
        }
        return orders.find(orderId).orElseThrow();
    }

    public Order get(User actor,long id)throws SQLException{Order order=orders.find(id).orElseThrow(()->ApiException.notFound("Order"));authorizeRead(actor,order);return order;}
    public List<Order> list(User actor)throws SQLException{return orders.list(actor);}
    public List<OrderItem> items(User actor,long id)throws SQLException{get(actor,id);return orders.items(id);}
    public List<ChargeLine> charges(User actor,long id)throws SQLException{get(actor,id);return orders.charges(id);}
    public Optional<GiftDetails> gift(User actor,long id)throws SQLException{get(actor,id);return orders.gift(id);}

    public synchronized Order autoAssign(User actor,long id)throws SQLException{
        requireAdmin(actor);
        try(Connection c=db.connection()){
            c.setAutoCommit(false);try{
                Order order=orders.lock(c,id).orElseThrow(()->ApiException.notFound("Order"));
                if(order.status()!=OrderStatus.PACKED)throw ApiException.conflict("Only packed orders can be assigned");
                if(assignment.assign(c,order).isEmpty()){pending.offer(id);throw ApiException.conflict("No capacity-suitable agent; order remains queued");}
                c.commit();pending.remove(id);
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }return orders.find(id).orElseThrow();
    }

    public synchronized Order updateStatus(User actor,long id,OrderStatus next)throws SQLException{
        if(next==OrderStatus.DELIVERED){if(actor.role()==Role.CUSTOMER)throw ApiException.forbidden();
            throw ApiException.conflict("Delivery code verification is required");}
        if(next==OrderStatus.ASSIGNED)throw ApiException.conflict("Courier assignment must use an assignment endpoint");
        Long releaseAgent=null;
        try(Connection c=db.connection()){
            c.setAutoCommit(false);try{
                Order order=orders.lock(c,id).orElseThrow(()->ApiException.notFound("Order"));authorizeStatus(actor,order,next);
                if(order.status()==OrderStatus.SCHEDULED&&next==OrderStatus.PLACED)
                    throw ApiException.conflict("Scheduled orders are released by the scheduler");
                if(!canTransition(order.status(),next))throw ApiException.conflict("Cannot move from "+order.status()+" to "+next);
                orders.updateStatus(c,id,next);
                if(next==OrderStatus.CANCELLED){pending.remove(id);inventory.release(c,id);}
                if(next==OrderStatus.CANCELLED){
                    if(order.agentId()!=null){releaseAgent=order.agentId();agents.syncLoad(c,releaseAgent);tracking.stop(c,releaseAgent);}
                }c.commit();
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }
        if(next==OrderStatus.PACKED){pending.offer(id);dispatchPending();}
        else if(releaseAgent!=null)dispatchPending();return orders.find(id).orElseThrow();
    }

    public synchronized Order reassign(User actor,long orderId,long agentId,Integer expectedVersion)throws SQLException{
        requireAdmin(actor);
        if(expectedVersion==null||expectedVersion<0)throw ApiException.badRequest("Expected order version is required");
        try(Connection c=db.connection()){
            c.setAutoCommit(false);try{
                Order order=orders.lock(c,orderId).orElseThrow(()->ApiException.notFound("Order"));
                if(order.version()!=expectedVersion)throw ApiException.conflict("Order changed; refresh it before assigning a courier");
                if(order.status()!=OrderStatus.PACKED&&order.status()!=OrderStatus.ASSIGNED&&order.status()!=OrderStatus.PICKED_UP)
                    throw ApiException.conflict("Only packed, assigned or picked-up orders can be assigned manually");
                if(Objects.equals(order.agentId(),agentId)){c.commit();return order;}
                Agent target=agents.lockById(c,agentId).orElseThrow(()->ApiException.notFound("Agent"));
                if(!suitable(target,order)||!agents.supportsZone(c,agentId,order.pickupZoneId())||!agents.claimCapacity(c,agentId,order.totalWeightKg()))
                    throw ApiException.conflict("Selected agent lacks capacity, zone coverage, or handling support");
                Long previous=order.agentId();
                if(order.status()==OrderStatus.PACKED){if(!orders.assign(c,orderId,agentId))throw ApiException.conflict("Order was assigned by another request");pending.remove(orderId);}
                else orders.reassign(c,orderId,agentId);
                orders.logAssignment(c,orderId,previous,agentId,actor.id(),previous==null?"MANUAL_ASSIGN":"REASSIGN");
                if(previous!=null)agents.syncLoad(c,previous);c.commit();
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }return orders.find(orderId).orElseThrow();
    }

    public List<Agent> availableAgents(User actor,long orderId)throws SQLException{
        requireAdmin(actor);Order order=orders.find(orderId).orElseThrow(()->ApiException.notFound("Order"));
        if(order.status()!=OrderStatus.PACKED&&order.status()!=OrderStatus.ASSIGNED&&order.status()!=OrderStatus.PICKED_UP)
            throw ApiException.conflict("Order is not assignable");
        try(Connection c=db.connection()){return agents.capacityCandidates(c,order);}
    }

    public synchronized void dispatchPending()throws SQLException{
        pending.reset(orders.pendingIds());
        int passSize=pending.size();
        for(int scanned=0;scanned<passSize&&pending.peek().isPresent();scanned++){
            long id=pending.poll().getAsLong();boolean assigned=false,discard=false;
            try(Connection c=db.connection()){
                c.setAutoCommit(false);try{
                    Optional<Order> value=orders.lock(c,id);discard=value.isEmpty()||value.get().status()!=OrderStatus.PACKED;
                    if(!discard)assigned=assignment.assign(c,value.get()).isPresent();c.commit();
                }catch(RuntimeException|SQLException e){c.rollback();throw e;}
            }
            // Capacity-infeasible work returns to the tail; later feasible orders still move.
            if(!assigned&&!discard)pending.offer(id);
        }
    }

    public Map<String,Object> routeSummary(Order order){
        MapDistanceService.Distance route=distance.distance(new GeoPoint(order.pickupAddress(),order.pickupLat(),order.pickupLng(),order.pickupZoneId(),null,null),
            new GeoPoint(order.dropAddress(),order.dropLat(),order.dropLng(),order.dropZoneId(),null,null));
        int eta=(int)Math.ceil(route.kilometers().doubleValue()/25d*60d)+10;
        return Map.of("distanceKm",route.kilometers(),"etaMinutes",eta,"zoneIds",route.zonePath(),"source",route.source());
    }

    public static boolean canTransition(OrderStatus from,OrderStatus to){return TRANSITIONS.getOrDefault(from,Set.of()).contains(to);}
    public Order verifyDelivery(User actor,long id,String code,String reason,String ip)throws SQLException{
        Order result=verification.verify(actor,id,code,reason,ip);dispatchPending();return result;
    }
    public String deliveryCode(User actor,Order order)throws SQLException{return verification.customerCode(actor,order);}
    private static boolean suitable(Agent a,Order o){return a.status()!=AgentStatus.OFFLINE&&a.currentActiveOrders()<a.maximumActiveOrders()
        &&a.currentLoadKg().add(o.totalWeightKg()).compareTo(a.maximumCapacityKg())<=0&&(!o.fragile()||a.supportsFragile())
        &&(!o.temperatureControlled()||a.supportsTemperatureControl());}
    private void authorizeRead(User actor,Order order)throws SQLException{
        boolean assigned=false;if(actor.role()==Role.AGENT&&order.agentId()!=null)assigned=agents.findByUser(actor.id()).map(a->a.id()==order.agentId()).orElse(false);
        if(actor.role()!=Role.ADMIN&&order.customerId()!=actor.id()&&!assigned)throw ApiException.forbidden();
    }
    private void authorizeStatus(User actor,Order order,OrderStatus next)throws SQLException{
        if(actor.role()==Role.ADMIN)return;
        if(actor.role()==Role.CUSTOMER&&order.customerId()==actor.id()&&next==OrderStatus.CANCELLED
            &&Set.of(OrderStatus.SCHEDULED,OrderStatus.PLACED,OrderStatus.CONFIRMED,OrderStatus.PACKED,OrderStatus.ASSIGNED).contains(order.status()))return;
        if(actor.role()==Role.AGENT){Agent agent=agents.findByUser(actor.id()).orElseThrow(ApiException::forbidden);
            if(Objects.equals(order.agentId(),agent.id())&&next!=OrderStatus.CANCELLED)return;}
        throw ApiException.forbidden();
    }
    private static String validateIdempotencyKey(String value){String key=value==null?"":value.trim();if(key.length()<8||key.length()>100||!key.matches("[A-Za-z0-9._:-]+"))
        throw ApiException.badRequest("Idempotency-Key must be 8-100 URL-safe characters");return key;}
    private static void requireAdmin(User actor){if(actor.role()!=Role.ADMIN)throw ApiException.forbidden();}
}

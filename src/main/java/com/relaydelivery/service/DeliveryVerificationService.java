package com.relaydelivery.service;

import com.relaydelivery.config.Database;
import com.relaydelivery.dao.AgentDao;
import com.relaydelivery.dao.OrderDao;
import com.relaydelivery.dao.OrderDao.DeliveryCodeState;
import com.relaydelivery.dao.OrderDao.MissingDeliveryCode;
import com.relaydelivery.dao.TrackingDao;
import com.relaydelivery.model.Models.*;
import com.relaydelivery.util.ApiException;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class DeliveryVerificationService {
    private static final String INCORRECT="Incorrect delivery code. Please ask the customer for the correct code.";
    private static final int MAX_ATTEMPTS=5;
    private final Database db;private final OrderDao orders;private final AgentDao agents;private final TrackingDao tracking;
    private final DeliveryCodeProtector protector;private final Duration blockDuration;private final Duration defaultLifetime;

    public DeliveryVerificationService(Database db,OrderDao orders,AgentDao agents,TrackingDao tracking,
                                       DeliveryCodeProtector protector){
        this.db=db;this.orders=orders;this.agents=agents;this.tracking=tracking;this.protector=protector;
        this.blockDuration=Duration.ofMinutes(Long.parseLong(Database.env("DELIVERY_CODE_BLOCK_MINUTES","15")));
        this.defaultLifetime=Duration.ofDays(Long.parseLong(Database.env("DELIVERY_CODE_VALID_DAYS","30")));
    }

    public void issue(Connection c,long orderId,Instant scheduledAt)throws SQLException{
        orders.lockDeliveryCodeNamespace(c);
        Instant expiresAt=scheduledAt==null?Instant.now().plus(defaultLifetime):scheduledAt.plus(Duration.ofDays(2));
        for(int attempt=0;attempt<1_000_000;attempt++){
            String code=protector.generate();DeliveryCodeProtector.ProtectedCode value=protector.protect(code);
            if(orders.deliveryCodeFingerprintExists(c,value.fingerprint()))continue;
            orders.storeDeliveryCode(c,orderId,value.hash(),value.ciphertext(),value.fingerprint(),expiresAt);return;
        }
        throw new SQLException("No unused delivery verification code is available");
    }

    public void ensureMissingCodes()throws SQLException{
        try(Connection c=db.connection()){
            c.setAutoCommit(false);
            try{
                for(MissingDeliveryCode missing:orders.missingDeliveryCodes(c))issue(c,missing.orderId(),missing.scheduledAt());
                c.commit();
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }
    }

    public String customerCode(User actor,Order order)throws SQLException{
        if(actor.role()!=Role.CUSTOMER||order.customerId()!=actor.id()||
            order.status()==OrderStatus.DELIVERED||order.status()==OrderStatus.CANCELLED)return null;
        DeliveryCodeState value=orders.deliveryCode(order.id()).orElse(null);
        if(value==null||value.hash()==null||value.ciphertext()==null||value.verifiedAt()!=null||
            value.expiresAt()==null||!value.expiresAt().isAfter(Instant.now()))return null;
        return protector.decrypt(value.ciphertext());
    }

    public Order verify(User actor,long orderId,String submittedCode,String reason,String ip)throws SQLException{
        if(actor.role()!=Role.AGENT&&actor.role()!=Role.ADMIN)throw ApiException.forbidden();
        String adminReason=actor.role()==Role.ADMIN?validateReason(reason):null;
        ApiException rejection=null;Long completedAgent=null;
        try(Connection c=db.connection()){
            c.setAutoCommit(false);
            try{
                Order order=orders.lock(c,orderId).orElseThrow(()->ApiException.notFound("Order"));
                authorize(actor,order,c);
                if(order.status()!=OrderStatus.DELIVERY_VERIFICATION)
                    throw ApiException.conflict("Order must be awaiting delivery verification");
                DeliveryCodeState value=orders.deliveryCode(c,orderId,true)
                    .orElseThrow(()->ApiException.conflict("Delivery verification code is unavailable"));
                Instant now=Instant.now();
                if(value.verifiedAt()!=null||value.hash()==null)throw ApiException.conflict("Delivery verification code has already been used");
                if(value.blockedUntil()!=null&&value.blockedUntil().isAfter(now)){
                    long seconds=Math.max(1,Duration.between(now,value.blockedUntil()).toSeconds());
                    throw new ApiException(429,"Delivery verification is temporarily blocked. Try again in "+seconds+" seconds.");
                }
                if(value.expiresAt()==null||!value.expiresAt().isAfter(now)){
                    orders.auditSecurity(c,order,actor,"DELIVERY_CODE_EXPIRED",order.status(),ip,"expired code rejected");
                    rejection=ApiException.conflict("Delivery verification code has expired");
                }else if(!validFormat(submittedCode)||!protector.verify(submittedCode,value.hash())){
                    int prior=value.blockedUntil()!=null&&!value.blockedUntil().isAfter(now)?0:value.failedAttempts();
                    int attempts=Math.min(MAX_ATTEMPTS,prior+1);Instant blocked=attempts==MAX_ATTEMPTS?now.plus(blockDuration):null;
                    orders.recordDeliveryFailure(c,order,actor,attempts,blocked,ip);
                    rejection=blocked==null?ApiException.badRequest(INCORRECT):new ApiException(429,
                        "Too many incorrect delivery codes. Verification is temporarily blocked.");
                }else{
                    orders.completeVerifiedDelivery(c,order,actor,adminReason,ip);completedAgent=order.agentId();
                    if(completedAgent!=null){agents.syncLoad(c,completedAgent);tracking.stop(c,completedAgent);}
                }
                c.commit();
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }
        if(rejection!=null)throw rejection;
        return orders.find(orderId).orElseThrow(()->ApiException.notFound("Order"));
    }

    private void authorize(User actor,Order order,Connection c)throws SQLException{
        if(actor.role()==Role.ADMIN)return;
        Agent agent=agents.findByUser(c,actor.id()).orElseThrow(ApiException::forbidden);
        if(order.agentId()==null||!Objects.equals(order.agentId(),agent.id()))throw ApiException.forbidden();
    }
    private static boolean validFormat(String code){return code!=null&&code.matches("[0-9]{6}");}
    private static String validateReason(String value){String reason=value==null?"":value.trim();
        if(reason.length()<5||reason.length()>500)throw ApiException.badRequest("Manual confirmation reason must be 5-500 characters");return reason;}
}

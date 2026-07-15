package com.relaydelivery.service;

import com.relaydelivery.config.Database;
import com.relaydelivery.dao.OrderDao;

import java.sql.Connection;

public final class ScheduledOrderProcessor implements Runnable {
    private final Database db;private final OrderDao orders;private final InventoryService inventory;
    private final OrderService orderService;
    public ScheduledOrderProcessor(Database db,OrderDao orders,InventoryService inventory,OrderService orderService){
        this.db=db;this.orders=orders;this.inventory=inventory;this.orderService=orderService;
    }
    public int runOnce()throws Exception{
        int released=0;
        try(Connection c=db.connection()){
            c.setAutoCommit(false);
            try{
                for(long id:orders.lockDueScheduled(c,50))if(orders.releaseScheduled(c,id)){
                    inventory.commitScheduled(c,id);released++;
                }
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
        return released;
    }
    @Override public void run(){try{runOnce();}catch(Exception e){System.err.println("Scheduled order check failed: "+e.getMessage());}}
}

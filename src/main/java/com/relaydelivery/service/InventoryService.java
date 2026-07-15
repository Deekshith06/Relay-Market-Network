package com.relaydelivery.service;

import com.relaydelivery.dao.InventoryDao;
import com.relaydelivery.model.Models.PricedItem;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public final class InventoryService {
    private final InventoryDao inventory;
    public InventoryService(InventoryDao inventory){this.inventory=inventory;}
    public void reserve(Connection c,long orderId,List<PricedItem> items,List<Long> itemIds,
                        boolean scheduled,Instant expiresAt)throws SQLException{
        inventory.reserve(c,orderId,items,itemIds,scheduled,expiresAt);
    }
    public void release(Connection c,long orderId)throws SQLException{inventory.release(c,orderId);}
    public void commitScheduled(Connection c,long orderId)throws SQLException{inventory.commitScheduled(c,orderId);}
}

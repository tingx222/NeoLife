package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.*;
import com.hmdp.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A separate Spring bean ensures the consumer really enters a database transaction. */
@Service
public class OrderWriter {
    private final VoucherOrderMapper orders;private final SeckillVoucherMapper stocks;
    public OrderWriter(VoucherOrderMapper orders,SeckillVoucherMapper stocks){this.orders=orders;this.stocks=stocks;}
    @Transactional
    public void persist(VoucherOrder order){
        if(orders.selectById(order.getId())!=null)return; // ACK retry after commit is idempotent.
        if(orders.selectCount(new QueryWrapper<VoucherOrder>().eq("user_id",order.getUserId()).eq("voucher_id",order.getVoucherId()))>0)
            throw new IllegalStateException("Duplicate reservation requires reconciliation");
        if(stocks.update(null,new UpdateWrapper<SeckillVoucher>().eq("voucher_id",order.getVoucherId()).gt("stock",0).setSql("stock=stock-1"))!=1)
            throw new IllegalStateException("Stock mismatch requires reconciliation");
        order.setStatus(1);
        if(orders.insert(order)!=1)throw new IllegalStateException("Order insert failed");
    }
}

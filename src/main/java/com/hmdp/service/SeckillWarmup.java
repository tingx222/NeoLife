package com.hmdp.service;
import com.hmdp.utils.*;
import com.hmdp.entity.SeckillVoucher;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
@Component
public class SeckillWarmup {
 private final ISeckillVoucherService vouchers;private final AsyncSeckillService seckill;
 public SeckillWarmup(ISeckillVoucherService vouchers,AsyncSeckillService seckill){this.vouchers=vouchers;this.seckill=seckill;}
 @EventListener(ApplicationReadyEvent.class) public void warm(){
  for(SeckillVoucher v:vouchers.list())seckill.prepare(v.getVoucherId());
 }
}

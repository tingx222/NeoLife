package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.*;
import com.hmdp.utils.*;
import org.redisson.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.*;
import java.time.ZoneId;
import java.util.*;

@Service
public class AsyncSeckillService {
    public static final String STREAM="stream.orders",GROUP="orders",CONSUMER="local-worker";
    private final StringRedisTemplate redis;private final RedissonClient locks;
    private final ISeckillVoucherService vouchers;private final IVoucherOrderService orders;
    private final OrderWriter writer;private final RedisIdWorker ids;
    private final DefaultRedisScript<Long> reserve=new DefaultRedisScript<>();
    private static final Logger log=LoggerFactory.getLogger(AsyncSeckillService.class);
    @org.springframework.beans.factory.annotation.Value("${hmdp.order-worker-enabled:true}")
    private boolean workerEnabled;
    @org.springframework.beans.factory.annotation.Value("${hmdp.order-prefix:}")
    private String prefix = "";
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<RabbitOrderTransport> rabbit;
    public AsyncSeckillService(StringRedisTemplate redis,RedissonClient locks,ISeckillVoucherService vouchers,
                               IVoucherOrderService orders,OrderWriter writer,RedisIdWorker ids){
        this.redis=redis;this.locks=locks;this.vouchers=vouchers;this.orders=orders;this.writer=writer;this.ids=ids;
        reserve.setLocation(new ClassPathResource("seckill.lua"));reserve.setResultType(Long.class);
    }
    private String activity(Long id){return prefix+"seckill:activity:"+id;}
    public Result submit(Long voucherId){
        if(voucherId==null || voucherId<1)return Result.fail("券ID无效");
        if(!prepare(voucherId))return Result.fail("秒杀缓存未准备或需对账恢复");
        String key=activity(voucherId);
        long id=ids.nextId("order");Long uid=UserHolder.getUser().getId();
        Long code=redis.execute(reserve,Arrays.asList(key,prefix+"seckill:purchased:"+voucherId,prefix+STREAM,prefix+"seckill:pending"),uid.toString(),voucherId.toString(),Long.toString(id));
        if(code==null)return Result.fail("秒杀服务不可用");
        String[] errors={"", "库存不足","秒杀尚未开始","秒杀已经结束","秒杀缓存未就绪","不能重复下单"};
        return code==0?Result.ok(Long.toString(id)):Result.fail(code>=1 && code<errors.length?errors[code.intValue()]:"秒杀服务不可用");
    }
    public boolean prepare(Long voucherId){
        String key=activity(voucherId);
        if(!Boolean.TRUE.equals(redis.hasKey(key))){
            // Serializing with the worker prevents rebuilding stock across an in-flight commit.
            RLock gate=locks.getLock("lock:orders:worker");gate.lock();
            try{
                if(!Boolean.TRUE.equals(redis.hasKey(key))){
                    SeckillVoucher v=vouchers.getById(voucherId);if(v==null)return false;
                    // Only this voucher's pending reservations block safe initialization.
                    List<MapRecord<String,Object,Object>> outstanding=redis.opsForStream().range(prefix+STREAM,org.springframework.data.domain.Range.unbounded());
                    if(outstanding!=null)for(MapRecord<String,Object,Object> record:outstanding)
                        if(voucherId.toString().equals(String.valueOf(record.getValue().get("voucherId"))))return false;
                    String purchased=prefix+"seckill:purchased:"+voucherId;redis.delete(purchased);
                    for(VoucherOrder o:orders.list(new QueryWrapper<VoucherOrder>().eq("voucher_id",voucherId)))redis.opsForSet().add(purchased,o.getUserId().toString());
                    Map<String,String> data=new HashMap<>();data.put("stock",v.getStock().toString());
                    data.put("begin",Long.toString(v.getBeginTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()));
                    data.put("end",Long.toString(v.getEndTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()));
                    redis.opsForHash().putAll(key,data);
                }
            }finally{gate.unlock();}
        }
        return true;
    }
    public Result status(Long id){
        Long uid=UserHolder.getUser().getId();VoucherOrder order=orders.getOne(new QueryWrapper<VoucherOrder>().eq("id",id).eq("user_id",uid));
        Map<String,Object> result=new LinkedHashMap<>();result.put("orderId",id.toString());
        if(order!=null){result.put("state","SUCCESS");result.put("status",order.getStatus());return Result.ok(result);}
        Object owner=redis.opsForHash().get(prefix+"seckill:pending",id.toString());
        if(!uid.toString().equals(owner))return Result.fail("订单不存在或无权查看");
        result.put("state","PROCESSING");return Result.ok(result);
    }
    @Scheduled(fixedDelay=200)
    public void consume(){
        if(!workerEnabled)return;
        processBatch();
    }
    public void processBatch(){
        RLock gate=locks.getLock("lock:orders:worker");if(!gate.tryLock())return;
        try{
            DefaultRedisScript<Long> init=new DefaultRedisScript<>("local r=redis.pcall('XGROUP','CREATE',KEYS[1],ARGV[1],'0','MKSTREAM'); if type(r)=='table' and r.err and not string.find(r.err,'BUSYGROUP') then return redis.error_reply(r.err) end; return 1",Long.class);
            redis.execute(init,Collections.singletonList(prefix+STREAM),GROUP);
            List<MapRecord<String,Object,Object>> list=redis.opsForStream().read(Consumer.from(GROUP,CONSUMER),StreamReadOptions.empty().count(10),StreamOffset.create(prefix+STREAM,ReadOffset.from("0")));
            if(list==null || list.isEmpty())list=redis.opsForStream().read(Consumer.from(GROUP,CONSUMER),StreamReadOptions.empty().count(10),StreamOffset.create(prefix+STREAM,ReadOffset.lastConsumed()));
            if(list==null)return;
            for(MapRecord<String,Object,Object> record:list){
                Map<Object,Object> body=record.getValue();VoucherOrder order=new VoucherOrder();
                order.setId(Long.valueOf(body.get("id").toString()));order.setUserId(Long.valueOf(body.get("userId").toString()));order.setVoucherId(Long.valueOf(body.get("voucherId").toString()));
                RabbitOrderTransport transport=rabbit.getIfAvailable();
                if(transport!=null)transport.publish(order);else writer.persist(order);
                redis.opsForStream().acknowledge(prefix+STREAM,GROUP,record.getId());
                redis.opsForStream().delete(prefix+STREAM,record.getId());
                if(transport==null)redis.opsForHash().delete(prefix+"seckill:pending",order.getId().toString());
            }
        }catch(Exception e){log.warn("订单消费失败，消息保留于Pending等待重试: {}",e.getClass().getSimpleName());}
        finally{gate.unlock();}
    }
}

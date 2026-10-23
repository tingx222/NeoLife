package com.hmdp.service;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.redisson.api.*;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** A missing/unready filter must fail open, never reject real shops during bootstrap. */
@Service
public class ShopBloomFilter {
    private final RedissonClient redisson; private final ShopMapper shops; private final StringRedisTemplate redis;
    private static final String NAME="shop:bloom:v1",READY=NAME+":ready";
    public ShopBloomFilter(RedissonClient redisson,ShopMapper shops,StringRedisTemplate redis){this.redisson=redisson;this.shops=shops;this.redis=redis;}
    public void add(Long id){RBloomFilter<Long> bloom=redisson.getBloomFilter(NAME);bloom.tryInit(1000000,0.01);bloom.add(id);}
    public boolean mightExist(Long id){
        if(id==null || id<1)return false;
        try{
            RBloomFilter<Long> bloom=redisson.getBloomFilter(NAME);
            return !Boolean.TRUE.equals(redis.hasKey(READY)) || !bloom.isExists() || bloom.contains(id);
        }catch(RuntimeException e){return true;}
    }
    @EventListener(ApplicationReadyEvent.class)
    public void warm(){
        RLock lock=redisson.getLock(NAME+":init");lock.lock();
        try{
            redis.delete(READY);RBloomFilter<Long> bloom=redisson.getBloomFilter(NAME);bloom.tryInit(1000000,0.01);
            for(int page=1;;page++){
                Page<Shop> rows=shops.selectPage(new Page<>(page,500),new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Shop>().select("id").orderByAsc("id"));
                for(Shop shop:rows.getRecords())bloom.add(shop.getId());
                if(rows.getRecords().size()<500)break;
            }
            redis.opsForValue().set(READY,"1");
        }finally{lock.unlock();}
    }
}

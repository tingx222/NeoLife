package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.utils.UserHolder;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.redisson.api.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DiscoveryService {
    private final IShopService shops;private final StringRedisTemplate redis;private final RedissonClient locks;
    public DiscoveryService(IShopService shops,StringRedisTemplate redis,RedissonClient locks){this.shops=shops;this.redis=redis;this.locks=locks;}
    public void invalidate(Long type){if(type!=null)redis.delete("shop:geo:"+type);}
    public Result nearby(int type,int page,Double x,Double y){
        if(page<1 || page>1000 || type<1)return Result.fail("分类或页码无效");
        if(x==null && y==null)return Result.ok(shops.page(new Page<>(page,5),new QueryWrapper<Shop>().eq("type_id",type).orderByAsc("id")).getRecords());
        if(x==null || y==null || !Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x)>180 || Math.abs(y)>85.05112878)return Result.fail("经纬度无效");
        String key="shop:geo:"+type;
        if(!Boolean.TRUE.equals(redis.hasKey(key))){RLock lock=locks.getLock("lock:"+key);lock.lock();try{
            if(!Boolean.TRUE.equals(redis.hasKey(key)))for(Shop s:shops.list(new QueryWrapper<Shop>().eq("type_id",type))){
                if(s.getX()!=null && s.getY()!=null)redis.opsForGeo().add(key,new Point(s.getX(),s.getY()),s.getId().toString());
            }
        }finally{lock.unlock();}}
        GeoResults<RedisGeoCommands.GeoLocation<String>> results=redis.opsForGeo().radius(key,
                new Circle(new Point(x,y),new Distance(5,Metrics.KILOMETERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending().limit(page*5));
        if(results==null)return Result.ok(Collections.emptyList());
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> selected=results.getContent().stream().skip((page-1)*5L).collect(Collectors.toList());
        List<Shop> ordered=new ArrayList<>();
        for(GeoResult<RedisGeoCommands.GeoLocation<String>> hit:selected){Shop s=shops.getById(Long.valueOf(hit.getContent().getName()));if(s!=null){s.setDistance(hit.getDistance().getValue()*1000);ordered.add(s);}}
        return Result.ok(ordered);
    }
    private LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
    private String signKey(LocalDate date){return "sign:"+UserHolder.getUser().getId()+":"+date.format(DateTimeFormatter.ofPattern("yyyyMM"));}
    public Result sign(){LocalDate date=today();redis.opsForValue().setBit(signKey(date),date.getDayOfMonth()-1,true);return Result.ok();}
    public Result signCount(){LocalDate date=today();List<Long> values=redis.opsForValue().bitField(signKey(date),BitFieldSubCommands.create()
            .get(BitFieldSubCommands.BitFieldType.unsigned(date.getDayOfMonth())).valueAt(0));
        return Result.ok(trailingOnes(values==null || values.isEmpty() || values.get(0)==null?0:values.get(0)));}
    public static int trailingOnes(long bits){int count=0;while((bits&1)==1){count++;bits>>>=1;}return count;}
    public Result visit(){redis.opsForHyperLogLog().add("uv:"+today(),UserHolder.getUser().getId().toString());return Result.ok();}
    public Result uv(){return Result.ok(redis.opsForHyperLogLog().size("uv:"+today()));}
}

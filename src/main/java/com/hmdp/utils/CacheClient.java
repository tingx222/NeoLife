package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    private  StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //用于处理缓存击穿问题
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {

        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    public <R,ID> R queryWithPassThrough
            (String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        String key = keyPrefix + id;

        //1.查询redis
        String Json = stringRedisTemplate.opsForValue().get(key);

        /**
         * StrUtil.isNotBlank(null) //false -->不存在
         * StrUtil.isNotBlank("") //false  -->存在
         * StrUtil.isNotBlank("\t\n") //false
         * StrUtil.isNotBlank("abc") //true
         *
         */
        //2.判断是否命中缓存，命中即存在，返回(string->json)
        if(StrUtil.isNotBlank(Json)){
            return JSONUtil.toBean(Json, type);
        }
        //判断命中是否为空值
        if(Json != null){ //即shopJson == "" 为存在
            //返回一个错误信息
            return null;
        }

        //3.不存在，查询数据库 --函数式编程，有参有返回值
        R r = dbFallback.apply(id);
        //Shop shop = getById(id);

        //4.不存在，报错
        if(r == null){

            //将空值返回redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }

        //5.存在，写入redis
        this.set(key, r, time, unit);

        //6.返回
        return r;
    }



    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private boolean tryLock(String key){
        //setnx
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        /**
         * 这里直接return flag会进行自动拆箱
         * 因为这个不是基本类型的boolean，这个Boolean是boolean的包装类
         * 网络问题或键不存在但 Redis 未响应，setIfAbsent 可能会返回 null
         */
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    //逻辑过期 解决缓存击穿
    public <R,ID> R queryWithLogicalExpire
    (String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){

        String key = keyPrefix + id;

        //1.查询redis
        String Json = stringRedisTemplate.opsForValue().get(key);

        /**
         * StrUtil.isNotBlank(null) //false -->不存在
         * StrUtil.isNotBlank("") //false  -->存在
         * StrUtil.isNotBlank("\t\n") //false
         * StrUtil.isNotBlank("abc") //true
         *
         */
        //2.判断是否命中缓存,未命中返回null
        if(StrUtil.isBlank(Json)){
            return null;
        }

        //3.命中，判断是否过期(反序列化)
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //3.1 未过期，直接返回店铺信息
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }

        //4.2 过期，需要缓存重建

        //5 缓存重建
        //5.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //5.2 判断是否成功获取锁

        if(isLock){
            //5.2.1 成功，获取锁
            //拿到锁后，重新从Redis读取最新数据
            /**
             * 如果没有DoubleCheck：线程B直接开启另一个线程再次重建缓存（浪费CPU）
             * 有了DoubleCheck：线程B重新读Redis，发现缓存已是最新，直接返回旧数据，不再重建
             */
            String newJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(newJson)){
                RedisData newRedisData = JSONUtil.toBean(newJson, RedisData.class);
                R newr = JSONUtil.toBean((JSONObject) newRedisData.getData(), type);
                LocalDateTime newExpireTime = newRedisData.getExpireTime();

                //再次检查redis缓存是否过期，做doublecheck,如果存在则无需重建缓存
                if(newExpireTime.isAfter(LocalDateTime.now())){
                    return newr;
                }
            }

            //5.2.2 开启独立的线程，实现缓存重建
            //线程池完成
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //cache rebuild
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redius
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //5.2.3 返回过期的店铺信息
        return r;



    }

}

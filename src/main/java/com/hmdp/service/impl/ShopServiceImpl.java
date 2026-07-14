package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {

        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);

        if(shop == null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期 解决缓存击穿
    public Shop queryWithLogicalExpire(Long id){

        String key = CACHE_SHOP_KEY + id;

        //1.查询redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        /**
         * StrUtil.isNotBlank(null) //false -->不存在
         * StrUtil.isNotBlank("") //false  -->存在
         * StrUtil.isNotBlank("\t\n") //false
         * StrUtil.isNotBlank("abc") //true
         *
         */
        //2.判断是否命中缓存,未命中返回null
        if(StrUtil.isBlank(shopJson)){
            return null;
        }

        //3.命中，判断是否过期(反序列化)
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //3.1 未过期，直接返回店铺信息
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
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
            String newShopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(newShopJson)){
                RedisData newRedisData = JSONUtil.toBean(newShopJson, RedisData.class);
                JSONObject newData = (JSONObject) newRedisData.getData();
                Shop newShop = JSONUtil.toBean(newData, Shop.class);
                LocalDateTime newExpireTime = newRedisData.getExpireTime();

                //再次检查redis缓存是否过期，做doublecheck,如果存在则无需重建缓存
                if(newExpireTime.isAfter(LocalDateTime.now())){
                    return newShop;
                }
            }

            //5.2.2 开启独立的线程，实现缓存重建
            //线程池完成
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //cache rebuild
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //5.2.3 返回过期的店铺信息
        return shop;



    }


    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id){

        String key = CACHE_SHOP_KEY + id;

        //1.查询redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        /**
         * StrUtil.isNotBlank(null) //false -->不存在
         * StrUtil.isNotBlank("") //false  -->存在
         * StrUtil.isNotBlank("\t\n") //false
         * StrUtil.isNotBlank("abc") //true
         *
         */
        //2.判断是否命中缓存，命中即存在，返回(string->json)
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中是否为空值
        if(shopJson != null){ //即shopJson == "" 为存在
            //返回一个错误信息
            return null;
        }

        String lockKey = LOCK_SHOP_KEY + id;

        //3 实现缓存重建（缓存未命中，尝试获取锁）
        try {
            //3.1 获取互斥锁
            boolean isLock = tryLock(lockKey);
            //3.2 判断是否获取成功
            if(!isLock) {
                //3.3 获取失败，休眠，并再次尝试获取
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //3.4获取成功
            //获取锁成功应该再次检测redis缓存是否存在，做DoubleCheck,如果存在则无需重建缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if(shopJson != null){ //即shopJson == "" 为存在
                return null;
            }

            //3.5查询数据库
            Shop shop = getById(id);
            //模拟重建的延时
            Thread.sleep(200);

            //4.查询失败 ，数据库不存在，报错
            if(shop == null){

                //将空值返回redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }

            //5.查询成功，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unlock(lockKey);
        }


        //6.返回
        return null;
    }


    //缓存击穿 封装
    public Shop queryWithPassThrough(Long id){

        String key = CACHE_SHOP_KEY + id;

        //1.查询redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        /**
         * StrUtil.isNotBlank(null) //false -->不存在
         * StrUtil.isNotBlank("") //false  -->存在
         * StrUtil.isNotBlank("\t\n") //false
         * StrUtil.isNotBlank("abc") //true
         *
         */
        //2.判断是否命中缓存，命中即存在，返回(string->json)
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中是否为空值
        if(shopJson != null){ //即shopJson == "" 为存在
            //返回一个错误信息
            return null;
        }

        //3.不存在，查询数据库
        Shop shop = getById(id);

        //4.不存在，报错
        if(shop == null){

            //将空值返回redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }

        //5.存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //6.返回
        return null;
    }

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


    //保存店铺信息到redis
    public void saveShop2Redis(Long id,Long expireTime) throws InterruptedException {
        //1.查询店铺信息
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));

        //3.写入redis string->json
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    @Transactional
    @Override
    public Result update(Shop shop) {

        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}

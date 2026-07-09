package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE_LIST_KEY;

        // 1. 从 Redis 查 String
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 命中，JSON -> List<ShopType>
        if (StrUtil.isNotBlank(shopTypeJson)) {
            List<ShopType> list = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(list);
        }

        // 3. 未命中，查数据库
        List<ShopType> list = query().orderByAsc("sort").list();
        if (list == null || list.isEmpty()) {
            return Result.fail("店铺类型不存在");
        }

        // 4. 写入 Redis String，整个list序列化
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(list),
                CACHE_SHOP_TYPE_TTL, TimeUnit.SECONDS);

        return Result.ok(list);

    }
}

package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    public static final String KEY_PREFIX = "lock:";
    public static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    //lua脚本的初始化
    /**
     * 1. 项目启动
     *        ↓
     * 2. 加载 SimpleRedisLock 类
     *        ↓
     * 3. 执行 static {} 静态代码块
     *        ↓
     * 4. 从 resources/unlock.lua 读取脚本内容
     *        ↓
     * 5. 保存到 UNLOCK_SCRIPT 中
     *        ↓
     * 6. 项目运行中，所有解锁都复用这个脚本
     */
    //这个对象专门装 Lua 脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //静态代码块，在类第一次被加载时执行，且只执行一次。
    //因为 static，所有 SimpleRedisLock 对象都共用同一个 Lua 脚本对象，节省内存。
    static {
        //创建一个 DefaultRedisScript 对象，用来承载 Lua 脚本。
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //设置脚本文件位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置返回类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        /*
        直接return success会产生自动拆箱，如果放回的是nil,会出现空指针异常
         */
        return BooleanUtil.isTrue(success);

    }

    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }

    /*    @Override
    public void unlock() {
        //获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断标示是否一致
        if(threadId.equals(id)){
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}

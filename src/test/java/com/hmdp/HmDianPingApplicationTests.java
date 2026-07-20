package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    // ==================== Redis自增ID测试 ====================
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for(int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();

        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    // ==================== 雪花算法实现 ====================

    // 雪花算法配置参数
    private final long workerId = 1L;
    private final long datacenterId = 1L;
    private final long twepoch = 1640995200000L; // 2022-01-01 00:00:00

    private final long workerIdBits = 5L;  // 机器ID占5位
    private final long datacenterIdBits = 5L;  // 数据中心ID占5位
    private final long sequenceBits = 12L;  // 序列号占12位

    // ========== 最大值计算 ==========
    //左移5位 = 32，-1的补码是11111，结果是31
    /*
    -1L 的二进制：11111111 11111111 11111111 11111111 11111111 11111111 11111111 11111111
    左移12位：    11111111 11111111 11111111 11111111 11111111 11111111 11110000 00000000
    取反(~)：     00000000 00000000 00000000 00000000 00000000 00000000 00001111 11111111
    结果：        4095 (2^12 - 1)
     */
    private final long maxWorkerId = ~(-1L << workerIdBits);
    private final long maxDatacenterId = ~(-1L << datacenterIdBits);

    // 序列号掩码：4095 (0~4095循环)
    private final long sequenceMask = ~(-1L << sequenceBits);

    // 机器ID左移12位（序列号 12位）
    private final long workerIdShift = sequenceBits;
    // 数据中心ID左移17位（机器ID 5位 + 序列号 12位）
    private final long datacenterIdShift = sequenceBits + workerIdBits;
    // 时间戳左移22位（机器ID 5位 + 数据中心ID 5位 + 序列号 12位）
    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;

    // 状态变量（注意：这些是实例变量，每个测试方法独立）
    // 【第6部分】状态变量 - 记录上次生成的状态
    private long sequence = 0L;          // 当前毫秒内的序列号
    private long lastTimestamp = -1L;    // 上次生成ID的时间戳

    /**
     * 生成雪花ID（测试类内部方法）
     */
    private synchronized long nextSnowflakeId() {
        long timestamp = System.currentTimeMillis();

        // 时钟回拨处理
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                // 回拨不超过5ms，等待
                try {
                    wait(offset << 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                timestamp = System.currentTimeMillis();
                if (timestamp < lastTimestamp) {
                    throw new RuntimeException("时钟回拨超过5ms");
                }
            } else {
                throw new RuntimeException("时钟回拨: " + offset + "ms");
            }
        }

        // 同一毫秒内
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) {
                // 序列号用完，等待下一毫秒
                while (timestamp <= lastTimestamp) {
                    timestamp = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 组装ID
        return ((timestamp - twepoch) << timestampLeftShift)
                | (datacenterId << datacenterIdShift)
                | (workerId << workerIdShift)
                | sequence;
    }

    /**
     * 测试雪花算法 - 单线程生成10个ID
     */
    @Test
    void testSnowflakeId() {
        System.out.println("========== 雪花算法生成10个ID ==========");
        for (int i = 0; i < 10; i++) {
            long id = nextSnowflakeId();
            System.out.println("雪花ID: " + id);
        }
    }

    /**
     * 测试雪花算法 - 并发性能测试（和Redis方案对比）
     */
    @Test
    void testSnowflakeConcurrent() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = nextSnowflakeId();
                System.out.println("雪花ID: " + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();

        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("雪花算法生成30000个ID耗时: " + (end - begin) + "ms");
    }

    /**
     * 对比测试：Redis方案 vs 雪花算法
     */
    @Test
    void testComparePerformance() throws InterruptedException {
        // 1. 测试Redis方案
        long redisTime = testRedisIdPerformance();

        // 2. 测试雪花方案
        long snowflakeTime = testSnowflakePerformance();

        // 3. 输出对比结果
        System.out.println("========================================");
        System.out.println("Redis方案生成30000个ID耗时: " + redisTime + "ms");
        System.out.println("雪花方案生成30000个ID耗时: " + snowflakeTime + "ms");
        System.out.println("雪花比Redis快: " + (redisTime - snowflakeTime) + "ms");
        System.out.println("性能提升: " + String.format("%.2f", (double)redisTime / snowflakeTime) + "倍");
        System.out.println("========================================");
    }

    /**
     * 测试Redis方案性能（复用已有的testIdWorker逻辑）
     */
    private long testRedisIdPerformance() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                redisIdWorker.nextId("order");
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        return System.currentTimeMillis() - begin;
    }

    /**
     * 测试雪花方案性能
     */
    private long testSnowflakePerformance() throws InterruptedException {
        // 重置雪花算法的状态（因为sequence和lastTimestamp是实例变量）
        this.sequence = 0L;
        this.lastTimestamp = -1L;

        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                nextSnowflakeId();
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        return System.currentTimeMillis() - begin;
    }

    // ==================== 原有测试方法 ====================
    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+1L, shop, 10L, TimeUnit.SECONDS);
    }
}
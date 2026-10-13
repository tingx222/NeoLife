package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(RedisProperties redis){
        //配置类
        Config config = new Config();
        //添加redis地址，这里添加了单点的地址，也可以使用config.useClusterServers()添加集群的地址
        config.useSingleServer()
                .setAddress("redis://" + redis.getHost() + ":" + redis.getPort())
                .setDatabase(redis.getDatabase());
        if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
            config.useSingleServer().setPassword(redis.getPassword());
        }
        //创建客户端,即创建RedissonClient对象
        return Redisson.create(config);


    }
}

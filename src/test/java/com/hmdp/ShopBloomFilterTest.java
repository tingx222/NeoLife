package com.hmdp;
import com.hmdp.service.ShopBloomFilter;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ShopBloomFilterTest {
 @Test @SuppressWarnings("unchecked") void unreadyFailsOpenAndReadyRejectsMissingIds(){
  RedissonClient locks=mock(RedissonClient.class);StringRedisTemplate redis=mock(StringRedisTemplate.class);RBloomFilter<Long> filter=mock(RBloomFilter.class);
  when(locks.<Long>getBloomFilter(anyString())).thenReturn(filter);
  ShopBloomFilter service=new ShopBloomFilter(locks,mock(ShopMapper.class),redis);
  assertTrue(service.mightExist(1L));verify(filter,never()).contains(anyLong());
  when(redis.hasKey(anyString())).thenReturn(true);when(filter.isExists()).thenReturn(true);
  assertFalse(service.mightExist(2L));when(filter.contains(1L)).thenReturn(true);assertTrue(service.mightExist(1L));
  when(filter.contains(1L)).thenThrow(new IllegalStateException());assertTrue(service.mightExist(1L));
 }
}

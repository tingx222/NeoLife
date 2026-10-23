package com.hmdp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerAgentTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeepSeekClient client = mock(DeepSeekClient.class);
    private final CustomerTools tools = mock(CustomerTools.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private CustomerAgent agent() {
        when(redis.opsForValue()).thenReturn(values);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        com.fasterxml.jackson.databind.node.ArrayNode definitions=mapper.createArrayNode();
        for(String name:Arrays.asList("get_my_order","get_faq")){
            com.fasterxml.jackson.databind.node.ObjectNode tool=definitions.addObject();tool.put("type","function");
            tool.putObject("function").put("name",name).putObject("parameters").put("type","object");
        }
        when(tools.definitions()).thenReturn(definitions);
        return new CustomerAgent(client, tools, mapper, redis, redisson);
    }

    @Test void executesToolsWithServerIdentityThenSavesAnswer() throws Exception {
        CustomerAgent agent = agent();
        when(client.complete(anyList(), any(), anyBoolean())).thenReturn(
                mapper.readTree("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"get_my_order\",\"arguments\":\"{\\\"orderId\\\":\\\"123\\\"}\"}}]}"),
                mapper.readTree("{\"role\":\"assistant\",\"content\":\"订单未支付\"}"));
        when(tools.execute(anyString(), anyString(), eq(7L))).thenReturn("未支付");
        Map<String, String> result = agent.chat(7L, null, "查订单123");
        assertEquals("订单未支付", result.get("reply"));
        verify(tools).execute("get_my_order", "{\"orderId\":\"123\"}", 7L);
        verify(values).set(eq("agent:history:7:" + result.get("sessionId")), contains("订单未支付"), eq(30L), any());
        verify(lock).unlock();
    }

    @Test void rejectsInvalidInputBeforeNetwork() {
        CustomerAgent agent = agent();
        assertThrows(IllegalArgumentException.class, () -> agent.chat(7L, "../other", "hello"));
        assertThrows(IllegalArgumentException.class, () -> agent.chat(null, null, "hello"));
        assertThrows(IllegalArgumentException.class, () -> agent.chat(7L, null, ""));
        verifyNoInteractions(client);
    }

    @Test void failuresReleaseLockAndDoNotSavePartialConversation() {
        CustomerAgent agent = agent();
        when(client.complete(anyList(), any(), anyBoolean())).thenThrow(new IllegalStateException("timeout"));
        assertThrows(IllegalStateException.class, () -> agent.chat(7L, null, "hello"));
        verify(lock).unlock();
        verify(values, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test void orderQueryAlwaysIncludesOwnerPredicate() {
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        CustomerTools registry = new CustomerTools(mock(IShopService.class), orders, mapper);
        assertEquals("订单不存在或无权查看", registry.execute("get_my_order", "{\"orderId\":\"123\",\"userId\":99}", 7L));
        verify(orders).getOne(argThat(query -> query.getSqlSegment().contains("user_id")
                && ((com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>) query).getParamNameValuePairs().containsValue(7L)
                && !((com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>) query).getParamNameValuePairs().containsValue(99L)));
    }

    @Test void runawayToolCallsStopWithoutSaving() throws Exception {
        CustomerAgent agent = agent();
        JsonNode call = mapper.readTree("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"get_faq\",\"arguments\":\"{}\"}}]}");
        when(client.complete(anyList(), any(), anyBoolean())).thenReturn(call);
        when(tools.execute(anyString(), anyString(), anyLong())).thenReturn("faq");
        assertThrows(IllegalStateException.class, () -> agent.chat(7L, null, "hello"));
        verify(client, times(3)).complete(anyList(), any(), eq(true));
        verify(client).complete(anyList(), any(), eq(false));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any());
        verify(lock).unlock();
    }

    @Test void sameSessionIdUsesDifferentHistoryKeysForDifferentUsers() throws Exception {
        CustomerAgent agent = agent();
        String session = "12345678-1234-1234-1234-123456789abc";
        when(client.complete(anyList(), any(), anyBoolean()))
                .thenReturn(mapper.readTree("{\"role\":\"assistant\",\"content\":\"你好\"}"));
        agent.chat(7L, session, "hello");
        agent.chat(8L, session, "hello");
        verify(values).get("agent:history:7:" + session);
        verify(values).get("agent:history:8:" + session);
    }
}

package com.hmdp.agent;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class McpToolGatewayTest {
 @Test void protocolInitializationUnknownMethodsAndOwnerPropagation() throws Exception {
  ObjectMapper mapper=new ObjectMapper();CustomerTools tools=mock(CustomerTools.class);
  when(tools.definitions()).thenReturn(mapper.readTree("[{\"function\":{\"name\":\"get_my_order\",\"parameters\":{\"type\":\"object\"}}}]"));
  McpToolGateway gateway=new McpToolGateway(tools,mapper);
  assertEquals("2025-03-26",gateway.dispatch(mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"),7L).path("result").path("protocolVersion").asText());
  assertEquals(-32601,gateway.dispatch(mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"bad\"}"),7L).path("error").path("code").asInt());
  when(tools.execute(eq("get_my_order"),anyString(),eq(7L))).thenReturn("本人订单");
  assertEquals("本人订单",gateway.execute("get_my_order","{\"orderId\":\"1\",\"userId\":99}",7L));
  verify(tools).execute(eq("get_my_order"),anyString(),eq(7L));
  assertEquals(1,gateway.definitions().size());
 }
}

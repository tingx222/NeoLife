package com.hmdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Explicit opt-in: makes paid requests to the configured real model. Never prints credentials. */
@SpringBootTest(properties={"hmdp.order-worker-enabled=false","hmdp.rabbit.enabled=false","logging.level.com.hmdp=info"})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named="runRealDeepSeek",matches="true")
class RealCustomerAgentTest {
    @Autowired MockMvc http;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;

    @Test void actualModelAnswersFaqViaAuthenticatedEndpointAndRemembersConversation() throws Exception {
        String token=UUID.randomUUID().toString();String session=UUID.randomUUID().toString();
        String uid="9223372036854000";
        String tokenKey="login:token:"+token;
        String historyKey="agent:history:"+uid+":"+session;
        try {
            redis.opsForHash().put(tokenKey,"id",uid);redis.expire(tokenKey,5,TimeUnit.MINUTES);
            Map<String,String> body=new LinkedHashMap<>();body.put("sessionId",session);
            body.put("message","请调用get_faq查询这个学习项目如何登录，以及目前是否支持实际退款。请用中文简洁回答。");
            JsonNode first=invoke(token,body);
            assertTrue(first.path("success").asBoolean(),first.path("errorMsg").asText());
            assertTrue(first.path("data").path("toolsUsed").asText().contains("get_faq"),"Real model did not select FAQ tool");
            assertFalse(first.path("data").path("reply").asText().trim().isEmpty());
            System.out.println("REAL_FAQ_REPLY: "+first.path("data").path("reply").asText());
            body.put("message","那你刚才说的登录方式，需要填写什么？");
            JsonNode second=invoke(token,body);
            assertTrue(second.path("success").asBoolean(),second.path("errorMsg").asText());
            assertFalse(second.path("data").path("reply").asText().trim().isEmpty());
            assertEquals(4,mapper.readTree(redis.opsForValue().get(historyKey)).size());
            System.out.println("REAL_FOLLOWUP_REPLY: "+second.path("data").path("reply").asText());
        } finally {redis.delete(Arrays.asList(tokenKey,historyKey));}
    }
    private JsonNode invoke(String token,Map<String,String> body) throws Exception {
        String liveUrl=System.getProperty("liveCustomerUrl");
        if(liveUrl!=null && !liveUrl.trim().isEmpty()){
            org.springframework.http.client.SimpleClientHttpRequestFactory factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5000);factory.setReadTimeout(180000);
            org.springframework.web.client.RestTemplate client=new org.springframework.web.client.RestTemplate(factory);
            org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);headers.set("authorization",token);
            return client.postForObject(liveUrl,new org.springframework.http.HttpEntity<>(body,headers),JsonNode.class);
        }
        String result=http.perform(post("/customer-agent/chat").header("authorization",token)
                .contentType("application/json").content(mapper.writeValueAsBytes(body)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return mapper.readTree(result);
    }
}

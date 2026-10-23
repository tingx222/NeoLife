package com.hmdp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import java.util.List;

/** Small OpenAI-compatible transport, deliberately independent of newer Java/Spring AI versions. */
@Component
public class DeepSeekClient {
    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final RestTemplate http;

    public DeepSeekClient(AgentProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        http = new RestTemplate(factory);
    }

    public JsonNode complete(List<JsonNode> messages, JsonNode tools, boolean allowTools) {
        if (properties.getApiKey().trim().isEmpty()) {
            throw new AgentUnavailableException("智能客服尚未配置密钥，请联系管理员启用");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", properties.getModel());
        body.set("messages", mapper.valueToTree(messages));
        body.set("tools", tools);
        body.put("tool_choice", allowTools ? "auto" : "none");
        body.put("max_tokens", 1000);
        body.putObject("thinking").put("type", "disabled");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String response;
        try {
            response = http.postForObject(properties.getBaseUrl().replaceAll("/+$", "")
                    + "/chat/completions", new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException e) {
            switch (e.getRawStatusCode()) {
                case 401: throw new AgentUnavailableException("客服模型密钥无效，请联系管理员检查密钥");
                case 402: throw new AgentUnavailableException("客服模型账户余额不足，请联系管理员充值");
                case 429: throw new AgentUnavailableException("客服当前请求较多，请稍后重试");
                case 400: case 404: case 422:
                    throw new AgentUnavailableException("客服模型配置不兼容，请联系管理员检查模型名称和接口地址");
                default: throw new AgentUnavailableException("客服模型服务暂时不可用，请稍后重试");
            }
        } catch (ResourceAccessException e) {
            throw new AgentUnavailableException("连接客服模型超时或网络不可用，请稍后重试");
        }
        try {
            JsonNode root = mapper.readTree(response);
            JsonNode message = root.path("choices").path(0).path("message");
            if (!message.isObject() || !"assistant".equals(message.path("role").asText())) {
                throw new IllegalStateException("Invalid model response");
            }
            return message;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid model JSON", e);
        }
    }
}

package com.hmdp.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "customer-agent")
public class AgentProperties {
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-flash";
}

package com.hmdp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class RabbitProfileConfigTest {
    @TempDir Path configuration;
    @Test void externalProfileCredentialsOverridePackagedGuestDefaults() throws Exception {
        Files.write(configuration.resolve("application-rabbit.properties"),
                ("spring.rabbitmq.username=hmdp\nspring.rabbitmq.password=test-only\nspring.rabbitmq.virtual-host=hmdp\n").getBytes(StandardCharsets.UTF_8));
        StandardEnvironment environment=new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testLocation",
                java.util.Collections.singletonMap("spring.config.additional-location",configuration.toUri().toString())));
        environment.setActiveProfiles("rabbit");
        new ConfigFileApplicationListener().postProcessEnvironment(environment,new SpringApplication(HmDianPingApplication.class));
        assertEquals("hmdp",environment.getProperty("spring.rabbitmq.username"));
        assertEquals("test-only",environment.getProperty("spring.rabbitmq.password"));
        assertEquals("hmdp",environment.getProperty("spring.rabbitmq.virtual-host"));
    }
}

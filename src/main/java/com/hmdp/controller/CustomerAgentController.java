package com.hmdp.controller;

import com.hmdp.agent.CustomerAgent;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customer-agent")
public class CustomerAgentController {
    private final CustomerAgent agent;
    public CustomerAgentController(CustomerAgent agent) { this.agent = agent; }

    @PostMapping("/chat")
    public Result chat(@RequestBody ChatRequest request) {
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        try {
            return Result.ok(agent.chat(UserHolder.getUser().getId(), request.getSessionId(), request.getMessage()));
        } catch (com.hmdp.agent.AgentUnavailableException e) {
            return Result.fail(e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (RuntimeException e) {
            // Never return/log upstream response bodies, which can contain credentials or private data.
            return Result.fail("客服暂时不可用，请检查服务配置或稍后重试");
        }
    }

    @Data
    public static class ChatRequest {
        private String sessionId;
        private String message;
    }
}

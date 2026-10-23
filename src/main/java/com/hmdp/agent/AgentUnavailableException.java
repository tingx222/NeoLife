package com.hmdp.agent;

/** Contains only a safe, user-facing message, never an upstream response body. */
public class AgentUnavailableException extends RuntimeException {
    public AgentUnavailableException(String message) { super(message); }
}

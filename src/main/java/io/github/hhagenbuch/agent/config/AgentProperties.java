package io.github.hhagenbuch.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Central agent configuration, bound from the {@code agent.*} namespace.
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("claude-sonnet-5") String model,
        @DefaultValue("1024") int maxTokens,
        @DefaultValue("6") int maxToolIterations,
        @DefaultValue("3") int maxRetries) {
}

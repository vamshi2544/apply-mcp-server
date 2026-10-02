package dev.applymcp.prequal.backend;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the real HTTP backend (prequal.backend=http).
 * Credentials come from environment variables or a service binding, never from tool arguments.
 */
@ConfigurationProperties(prefix = "prequal.http")
public record PrequalHttpProperties(
        String baseUrl,
        String clientId,
        String apiKey,
        String channel,
        Duration connectTimeout,
        Duration readTimeout) {

    public PrequalHttpProperties {
        if (channel == null || channel.isBlank()) {
            channel = "AI_AGENT";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(2);
        }
        if (readTimeout == null) {
            // Keep below the MCP request timeout so the tool can return a clear message.
            readTimeout = Duration.ofSeconds(10);
        }
    }
}

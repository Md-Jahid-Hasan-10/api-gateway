package com.banking.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(List<Rule> rules) {

    public record Rule(String path, int limit, int windowSeconds) {}

    public RateLimitProperties {
        rules = rules == null ? List.of() : rules;
    }
}
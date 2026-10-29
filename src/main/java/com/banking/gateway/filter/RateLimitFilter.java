package com.banking.gateway.filter;

import com.banking.gateway.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * Limits how often one IP address may call the sensitive auth endpoints. Counters live in Redis
 * and reset when their window ends.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final GatewayErrorWriter errorWriter;

    public RateLimitFilter(ReactiveStringRedisTemplate redis,
                           RateLimitProperties properties,
                           GatewayErrorWriter errorWriter) {
        this.redis = redis;
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (request.getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }

        String path = request.getURI().getPath();
        RateLimitProperties.Rule rule = properties.rules().stream()
                .filter(r -> r.path().equals(path))
                .findFirst()
                .orElse(null);
        if (rule == null) {
            return chain.filter(exchange);
        }

        String key = "rl:" + path + ":" + clientIp(request);
        Duration window = Duration.ofSeconds(rule.windowSeconds());

        // The window starts with the first request, because setIfAbsent attaches the expiry.
        Mono<Boolean> allowed = redis.opsForValue().setIfAbsent(key, "0", window)
                .then(redis.opsForValue().increment(key))
                .map(count -> count <= rule.limit())
                .onErrorResume(ex -> {
                    // Fail open: a Redis problem must not take login down. The error is logged.
                    log.error("Rate limiter unavailable, allowing the request: {}", ex.getMessage());
                    return Mono.just(true);
                });

        return allowed.flatMap(ok -> ok ? chain.filter(exchange) : reject(exchange, key, window));
    }

    private Mono<Void> reject(ServerWebExchange exchange, String key, Duration window) {
        return redis.getExpire(key)
                .onErrorReturn(window)
                .defaultIfEmpty(window)
                .flatMap(ttl -> {
                    exchange.getResponse().getHeaders()
                            .set("Retry-After", String.valueOf(Math.max(1, ttl.toSeconds())));
                    return errorWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                            "Too many requests, please try again later");
                });
    }

    // The socket address, not X-Forwarded-For: a client can write any value into that header.
    private String clientIp(ServerHttpRequest request) {
        InetSocketAddress address = request.getRemoteAddress();
        if (address == null || address.getAddress() == null) {
            return "unknown";
        }
        return address.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
package com.banking.gateway.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.event.Level;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records every routed request at the moment its response is committed, so the final status is known
 * (also when the gateway itself refuses the request). No bodies: they are logged by the services.
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    /** Set by JwtAuthenticationFilter after the token was checked. */
    public static final String USER_ID_ATTRIBUTE = "banking.userId";

    private static final Set<String> KNOWN_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final int MAX_PATH_LENGTH = 200;

    private final TrafficLog traffic;
    private final MeterRegistry meters;

    public RequestLoggingFilter(TrafficLog traffic, MeterRegistry meters) {
        this.traffic = traffic;
        this.meters = meters;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.nanoTime();
        AtomicBoolean recorded = new AtomicBoolean(false);
        exchange.getResponse().beforeCommit(() -> {
            if (recorded.compareAndSet(false, true)) {
                record(exchange, start);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    private void record(ServerWebExchange exchange, long startNanos) {
        try {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = route == null ? "none" : route.getId();
            HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
            int status = statusCode == null ? 200 : statusCode.value();
            String rawMethod = exchange.getRequest().getMethod().name();
            String method = KNOWN_METHODS.contains(rawMethod) ? rawMethod : "OTHER";

            Timer.builder("gateway.requests")
                    .tag("route", routeId)
                    .tag("method", method)
                    .tag("status", String.valueOf(status))
                    .register(meters)
                    .record(elapsed);

            boolean read = "GET".equals(method) || "HEAD".equals(method);
            Level level = status >= 400 || !read ? Level.INFO : Level.DEBUG;
            if (!traffic.enabled(level)) {
                return;
            }

            String path = exchange.getRequest().getPath().value();
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("event", "gateway.request");
            fields.put("route", routeId);
            fields.put("httpMethod", method);
            fields.put("path", path.length() <= MAX_PATH_LENGTH ? path : path.substring(0, MAX_PATH_LENGTH));
            fields.put("httpStatus", String.valueOf(status));
            fields.put("outcome", status >= 500 ? "server_error" : status >= 400 ? "client_error" : "success");
            fields.put("durationMs", String.valueOf(elapsed.toMillis()));
            Object userId = exchange.getAttribute(USER_ID_ATTRIBUTE);
            fields.put("userId", userId instanceof String text && text.matches("\\d{1,18}") ? text : null);

            traffic.log(level, method + " " + routeId + " -> " + status + " in " + elapsed.toMillis() + " ms", fields);
        } catch (RuntimeException ex) {
            // Recording must never change the response.
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
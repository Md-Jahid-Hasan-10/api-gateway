package com.banking.gateway.filter;

import com.banking.gateway.security.AuthenticatedUser;
import com.banking.gateway.security.JwtTokenValidator;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_EMAIL = "X-User-Email";
    private static final String HEADER_USER_ROLES = "X-User-Roles";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/verify"
    );

    private final JwtTokenValidator tokenValidator;
    private final GatewayErrorWriter errorWriter;

    public JwtAuthenticationFilter(JwtTokenValidator tokenValidator, GatewayErrorWriter errorWriter) {
        this.tokenValidator = tokenValidator;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. Internal routes are never reachable from outside.
        if (path.equals("/internal") || path.startsWith("/internal/")) {
            return errorWriter.write(exchange, HttpStatus.NOT_FOUND, "Not found");
        }

        // 2. Never trust identity headers sent by a client.
        ServerHttpRequest.Builder builder = request.mutate().headers(headers -> {
            headers.remove(HEADER_USER_ID);
            headers.remove(HEADER_USER_EMAIL);
            headers.remove(HEADER_USER_ROLES);
        });

        // 3. Preflight and public endpoints need no token.
        if (request.getMethod() == HttpMethod.OPTIONS || PUBLIC_PATHS.contains(path)) {
            return chain.filter(exchange.mutate().request(builder.build()).build());
        }

        // 4. Everything else needs a valid Bearer token.
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }

        Optional<AuthenticatedUser> user = tokenValidator.validate(authHeader.substring(7).trim());
        if (user.isEmpty()) {
            return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }

        AuthenticatedUser authenticated = user.get();
        ServerHttpRequest authenticatedRequest = builder.headers(headers -> {
            headers.set(HEADER_USER_ID, authenticated.userId());
            headers.set(HEADER_USER_EMAIL, authenticated.email());
            headers.set(HEADER_USER_ROLES, String.join(",", authenticated.roles()));
        }).build();

        return chain.filter(exchange.mutate().request(authenticatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
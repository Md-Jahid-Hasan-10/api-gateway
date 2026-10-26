package com.banking.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class JwtTokenValidator {

    private final SecretKey key;

    public JwtTokenValidator(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<AuthenticatedUser> validate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();
            if (userId == null || userId.isBlank()) {
                return Optional.empty();
            }

            String email = claims.get("username", String.class);

            List<String> roles = new ArrayList<>();
            Object rolesClaim = claims.get("roles");
            if (rolesClaim instanceof List<?> list) {
                for (Object role : list) {
                    roles.add(String.valueOf(role));
                }
            }

            return Optional.of(new AuthenticatedUser(userId, email == null ? "" : email, roles));
        } catch (JwtException | IllegalArgumentException ex) {
            // Bad signature, expired, malformed, or unsigned token.
            return Optional.empty();
        }
    }
}
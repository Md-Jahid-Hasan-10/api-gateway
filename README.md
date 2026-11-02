# api-gateway

> The single front door of the banking platform: it routes public URL prefixes to Eureka services via `lb://`, validates the JWT bearer token and turns it into trusted identity headers, applies CORS for the web UI, and rate-limits the sensitive auth endpoints with Redis. Public port **8080**, actuator port **18080**.

## Table of Contents

- [What It Does](#what-it-does)
- [Architecture At A Glance](#architecture-at-a-glance)
- [HTTP API](#http-api)
- [Error Response Shape](#error-response-shape)
- [Database](#database)
- [Kafka Topics](#kafka-topics)
- [Configuration](#configuration)
- [Behaviour Rules](#behaviour-rules)
- [Running It](#running-it)
- [API Quick Reference (curl examples)](#api-quick-reference-curl-examples)
- [Configuration Reference (quick table)](#configuration-reference-quick-table)
- [Troubleshooting](#troubleshooting)
- [Notes and Known Limitations](#notes-and-known-limitations)

## What It Does

The API gateway is the **single front door** of the platform. It is a *reactive* Spring Cloud
Gateway (`spring-cloud-starter-gateway-server-webflux`; the `pom.xml` warns: *"Gateway is reactive.
Do NOT add spring-boot-starter-web here."*).

It owns four things:

1. **The routing table** from public URL prefixes to Eureka service names via `lb://` URIs.
2. **JWT authentication** — it validates the bearer token and turns it into trusted identity headers.
3. **CORS** for the browser-based web UI (`banking-web-ui`, port 3000).
4. A **Redis-backed rate limiter** on the sensitive auth endpoints.

It owns no business logic and no database. It is the only place where the browser's `Authorization`
header is accepted; every downstream service trusts the identity headers the gateway injects
(`X-User-Id`, `X-User-Email`, `X-User-Roles`).

The three routed services are `user-service` (port 8083), `account-service` (port 8081) and
`fund-transfer-service` (port 8082). A fourth "route" exists only to *block* `/internal/**`, which
is where the internal APIs of the domain services live (`/internal/events`, `/internal/otp`,
`/internal/users`, …).

## Architecture At A Glance

```
  browser / curl
        │  Authorization: Bearer <jwt>
        ▼
 ┌──────────────────────────────────────────────────────────┐
 │  api-gateway :8080  (WebFlux, reactive)                  │
 │                                                          │
 │  1. RateLimitFilter  order -200   POST only, Redis      │
 │  2. JwtAuthenticationFilter order -100                    │
 │        - /internal/**        → 404                       │
 │        - strip X-User-Id/Email/Roles from the request    │
 │        - public paths + OPTIONS → pass through            │
 │        - otherwise validate the JWT → 401 or continue    │
 │        - set X-User-Id / X-User-Email / X-User-Roles     │
 │  3. Route (lb://)  → Eureka registry (discovery :8761)  │
 └──────┬───────────────┬────────────────┬─────────────────┘
        │               │                │
        ▼               ▼                ▼
   user-service   account-service  fund-transfer-service
      :8083            :8081              :8082

  Redis :6379  → rate-limit counters, key "rl:<path>:<clientIp>"
```

| Service | Depends on | Depended on by |
|---|---|---|
| `api-gateway` (8080) | the discovery service (8761) for route resolution; Redis (6379) for the rate limiter; `user-service` (8083), `account-service` (8081), `fund-transfer-service` (8082) for the routes themselves | `banking-web-ui` (3000) — it is the only thing the UI talks to |

## HTTP API

The gateway exposes **no endpoints of its own**. It forwards requests to the three downstream
services and answers with its own error bodies when a request is rejected before routing.

### The route table

`src/main/resources/application.yaml`, `spring.cloud.gateway.server.webflux.routes`:

| # | Route id | Predicate | Target URI | Auth | Downstream service + path |
|---|---|---|---|---|---|
| 1 | `user-service-auth` | `Path=/api/auth/**` | `lb://user-service` | **mixed** — `/api/auth/register`, `/api/auth/login`, `/api/auth/verify` are public; any *other* `/api/auth/**` path needs a token | `user-service` `/api/auth/**` |
| 2 | `account-service` | `Path=/api/accounts/**` | `lb://account-service` | **protected** | `account-service` `/api/accounts/**` |
| 3 | `fund-transfer-service` | `Path=/api/transfers/**` | `lb://fund-transfer-service` | **protected** | `fund-transfer-service` `/api/transfers/**` |
| 4 | `blocked-internal` | `Path=/internal/**` | `no://op` | **never reachable** — answered **404** by the filter | – |

**There are no other routes.** Any path that matches none of the four predicates is not routed at
all; the gateway itself answers **404** with the *Spring WebFlux default* error body (not the
gateway's own error shape):

```json
{"timestamp":"2026-11-02T11:29:21.331+00:00","path":"/nope","status":404,"error":"Not Found","requestId":"c32250c7-310"}
```

### Route 1 — `lb://user-service`

The downstream controller is `user-service/src/main/java/com/banking/user/controller/AuthController.java`
(`@RequestMapping("/api/auth")`).

| Method | Path | Status | Notes |
|---|---|---|---|
| `POST` | `/api/auth/register` | **202 Accepted** | `MessageResponse` → `{"message":"If this email can be registered, we have sent you a message. Please check your inbox."}` |
| `POST` | `/api/auth/verify` | **200 OK** | `MessageResponse` |
| `POST` | `/api/auth/login` | **200 OK** | `AuthResponse` → `{"token","tokenType","expiresIn","userId","fullName","email","role"}` |

Request fields for `POST /api/auth/login`:

| Field | Type | Required | Notes |
|---|---|---|---|
| `email` | string | yes | |
| `password` | string | yes | |

Request fields for `POST /api/auth/register`:

| Field | Type | Required | Notes |
|---|---|---|---|
| `fullName` | string | yes | |
| `email` | string | yes | |
| `password` | string | yes | 8 to 64 characters |

Request fields for `POST /api/auth/verify`:

| Field | Type | Required | Notes |
|---|---|---|---|
| `token` | string | yes | The token from the verification e-mail link |

### Route 2 — `lb://account-service`

The downstream controller is `AccountController.java` (`@RequestMapping("/api/accounts")`).

| Method | Path | Status | Header needed |
|---|---|---|---|
| `GET` | `/api/accounts` | 200 | `X-User-Id` |
| `GET` | `/api/accounts/{accountNumber}` | 200 | `X-User-Id` |
| `POST` | `/api/accounts` | **202 Accepted** | `X-User-Id`, body `{"accountType":"SAVINGS"\|"CURRENT"}` |

### Route 3 — `lb://fund-transfer-service`

The downstream controller is `TransferController.java` (`@RequestMapping("/api/transfers")`).

| Method | Path | Status | Header needed |
|---|---|---|---|
| `POST` | `/api/transfers` | **202** (saga) / 200 (sync) | `X-User-Id`, `X-User-Email` |
| `POST` | `/api/transfers/deposit` | **202** / 200 | `X-User-Id`, `X-User-Email` |
| `POST` | `/api/transfers/withdraw` | **202** / 200 | `X-User-Id`, `X-User-Email` |
| `POST` | `/api/transfers/{transferId}/confirm` | 200 (finished) / 202 (running) | `X-User-Id`, `X-User-Email` |
| `POST` | `/api/transfers/{transferId}/resend-otp` | **202** | `X-User-Id` |
| `GET` | `/api/transfers/{transferId}` | 200 | `X-User-Id` |
| `GET` | `/api/transfers` | 200 | `X-User-Id` |

### Route 4 — `blocked-internal`

Nothing. The gateway deliberately refuses to forward `/internal/**` to the event bus service, the
notification service, the OTP service, the account service, the core banking service, the
charge-fee service or the user service. Each of those has its own `/internal/**` controller
(`/internal/events`, `/internal/notifications`, `/internal/otp`, `/internal/accounts`,
`/internal/cbs`, `/internal/fees`, `/internal/users`) reachable only on that service's own port.

### The identity headers the gateway injects

On a successful authentication the gateway **sets** three headers on the forwarded request,
overwriting whatever the client sent (step 2 already stripped them):

| Header | Value |
|---|---|
| `X-User-Id` | `claims.getSubject()` — the user id as a string |
| `X-User-Email` | the `username` claim; `""` if the claim is absent |
| `X-User-Roles` | `String.join(",", roles)` — the `roles` claim, comma-separated |

Downstream, `account-service` reads `X-User-Id`, and `fund-transfer-service` reads `X-User-Id` and
`X-User-Email`. **No service reads `X-User-Roles`** — the gateway adds it but nothing consumes it.

### Error Response Shape

All gateway-generated errors are written by
`src/main/java/com/banking/gateway/filter/GatewayErrorWriter.java`:

```java
String body = String.format(
        "{\"timestamp\":\"%s\",\"status\":%d,\"error\":\"%s\",\"message\":\"%s\"}",
        LocalDateTime.now(), status.value(), status.getReasonPhrase(), message);
```

with `Content-Type: application/json`. The `timestamp` is a **local** `LocalDateTime`
(e.g. `2026-11-02T17:26:12.727697958`, no timezone offset).

| Field | Type | Notes |
|---|---|---|
| `timestamp` | string | Local `LocalDateTime`, no timezone offset |
| `status` | integer | The HTTP status code, repeated in the body |
| `error` | string | The HTTP reason phrase, e.g. `Unauthorized` |
| `message` | string | The gateway's own message |

| Status | `error` | `message` | When |
|---|---|---|---|
| **401** | `Unauthorized` | `Missing or invalid Authorization header` | Protected path, no `Authorization` header or it does not start with `Bearer ` |
| **401** | `Unauthorized` | `Invalid or expired token` | Protected path, the token failed validation (bad signature / expired / malformed / blank `sub`) |
| **404** | `Not Found` | `Not found` | The path is `/internal` or starts with `/internal/` |
| **429** | `Too Many Requests` | `Too many requests, please try again later` | The rate limiter rejected the request; a `Retry-After` header is added |
| **503** | *(Spring default)* | `No servers available for service: <name>` | The load balancer has no instance yet — produced by Spring Cloud Gateway, not by the gateway's own code |

**There is no 403.** A repo-wide grep for `FORBIDDEN`, `403` and `SC_FORBIDDEN` in `api-gateway/`
returns **no matches**. The gateway performs no authorization, only authentication, so an
authenticated user never receives a 403 from the gateway itself.

Example:

```json
{"timestamp":"2026-11-02T17:26:12.727697958","status":401,"error":"Unauthorized","message":"Missing or invalid Authorization header"}
```

## Database

**Not applicable.** The gateway has no datasource, no JDBC driver and no entity classes. The only
state it keeps is in **Redis**: the rate-limit counters. They are ephemeral by design — each key
carries the window as its TTL.

## Kafka Topics

**Not applicable.** The gateway has no Kafka dependency in `pom.xml` and neither publishes nor
consumes any event. It is a synchronous HTTP router.

## Configuration

### Server and Spring properties

`src/main/resources/application.yaml`:

| Name | Default | Meaning |
|---|---|---|
| `server.port` | `8080` | Public port |
| `spring.application.name` | `api-gateway` | The Eureka name is `API-GATEWAY` |
| `spring.config.import` | `optional:file:.env[.properties]` | Loads `api-gateway/.env` when the working directory is `api-gateway/` |
| `spring.data.redis.host` | `${REDIS_HOST:127.0.0.1}` | Redis host |
| `spring.data.redis.port` | `${REDIS_PORT:6379}` | Redis port |
| `spring.data.redis.password` | `${REDIS_PASSWORD}` | **No default** — the gateway will not start without it |
| `spring.data.redis.timeout` | `2s` | Command timeout |
| `spring.data.redis.connect-timeout` | `2s` | Connect timeout |
| `spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowedOrigins` | `http://localhost:3000`, `http://127.0.0.1:3000` | The only two allowed browser origins |
| `...allowedMethods` | `GET, POST, PUT, DELETE, OPTIONS` | |
| `...allowedHeaders` | `Authorization, Content-Type, Accept` | |
| `...maxAge` | `3600` | Preflight cache, in seconds |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka` | **Hard-coded**, no env override here |
| `eureka.client.registry-fetch-interval-seconds` | **`5`** | Lowered from the Spring default of 30 to close the 503 window after a restart |
| `eureka.instance.prefer-ip-address` | `true` | |
| `management.server.port` | `18080` | Actuator on a private port |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Only these three actuator endpoints are exposed |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | `true` | Enables the latency histogram for HTTP requests |

The routes live under `spring.cloud.gateway.server.webflux.routes` — this is the Spring Cloud
Gateway 4.2+ *"server"* namespace, **not** the older `spring.cloud.gateway.routes`.

### Custom `app.*` properties

| Name | Default | Meaning |
|---|---|---|
| `app.jwt.secret` | `${JWT_SECRET:banking-microservices-local-dev-secret-key-2026}` | The HMAC key used to verify the bearer token. The in-file comment says: *"MUST be identical to user-service. At least 32 characters."* |
| `app.rate-limit.rules[0].path` | `/api/auth/register` | The path the rule applies to |
| `app.rate-limit.rules[0].limit` | `10` | Requests per window |
| `app.rate-limit.rules[0].window-seconds` | `3600` | 1 hour |
| `app.rate-limit.rules[1].path` | `/api/auth/login` | |
| `app.rate-limit.rules[1].limit` | `30` | Requests per window |
| `app.rate-limit.rules[1].window-seconds` | `300` | 5 minutes |
| `app.rate-limit.rules[2].path` | `/api/auth/verify` | |
| `app.rate-limit.rules[2].limit` | `20` | Requests per window |
| `app.rate-limit.rules[2].window-seconds` | `3600` | 1 hour |

The rules are bound by `src/main/java/com/banking/gateway/config/RateLimitProperties.java`:

```java
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(List<Rule> rules) {
    public record Rule(String path, int limit, int windowSeconds) {}
    public RateLimitProperties {
        rules = rules == null ? List.of() : List.of();
    }
}
```

(`@ConfigurationPropertiesScan` on `ApiGatewayApplication` picks this up.)

### Environment variables

| Name | Default | Meaning |
|---|---|---|
| `JWT_SECRET` | `banking-microservices-local-dev-secret-key-2026` | The JWT signing key. **Must be identical to the user-service secret.** |
| `REDIS_PASSWORD` | *(none)* | The Redis password. **Mandatory** — the gateway fails to start without it |
| `REDIS_HOST` | `127.0.0.1` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `SERVER_PORT` | `8080` | The public port. `run-all.sh` sets it explicitly to `8080` |

### Environment variables you must set

| Variable | Why |
|---|---|
| `REDIS_PASSWORD` | `spring.data.redis.password` has **no default**. The gateway will not start without it. |
| `JWT_SECRET` | Optional in the YAML, but in practice **mandatory for a working system**: it must equal the user-service secret, otherwise every token is rejected with `401 Invalid or expired token`. |

#### The `JWT_SECRET` trap — read this before you start the gateway

* The gateway's own YAML default is `banking-microservices-local-dev-secret-key-2026`.
* The **user-service** default is different:
  `78f2b81005ac23edf307e4368594d68fb1038cc667bc12f5b99946b8ea9e5ccb`
  (`user-service/src/main/resources/application.yaml:61`).
* **The two defaults do not match.** In the integrated system this does not bite, because
  `api-gateway/.env` sets
  `JWT_SECRET=78f2b81005ac23edf307e4368594d68fb1038cc667bc12f5b99946b8ea9e5ccb`
  — the *same* value as the user-service default — and `api-gateway/.env` is imported via
  `spring.config.import: optional:file:.env[.properties]` when the gateway is started with
  `api-gateway/` as the working directory. `run-all.sh` does exactly that (`pushd "$ROOT/$name"`).
* A JWT signed with the *user-service* secret is **accepted** by the gateway
  (`GET /api/accounts` → `200`), while a JWT signed with the *gateway's own YAML default* is
  **rejected** with `401 "Invalid or expired token"`.
* `api-gateway/.env` also sets `REDIS_PASSWORD=redis2188`.
* `user-service/.env` sets the same `JWT_SECRET`, so both services agree.
* Token lifetime: `app.jwt.expiration-ms: 3600000` (**60 minutes**) in the user service.

### The `local` profile

`src/main/resources/application-local.yml` is used when `SPRING_PROFILES_ACTIVE=local`:

```yaml
eureka:
  client:
    enabled: false

spring:
  cloud:
    discovery:
      client:
        simple:
          instances:
            user-service:
              - uri: http://localhost:8083
            account-service:
              - uri: http://localhost:8081
            fund-transfer-service:
              - uri: http://localhost:8082
```

It replaces Eureka with three hard-coded instance URLs. `run-all.sh` explicitly **unsets**
`SPRING_PROFILES_ACTIVE` — *"The 'local' profile must NOT be active in the integrated system."*

## Behaviour Rules

### Filter order

| Filter | Order | What it does |
|---|---|---|
| `RateLimitFilter` | **`-200`** | Runs **before** the JWT filter |
| `JwtAuthenticationFilter` | **`-100`** | Authentication and identity headers |

### The JWT filter runs in four steps, in this exact order

1. **Internal routes are never reachable from outside.**

   ```java
   if (path.equals("/internal") || path.startsWith("/internal/")) {
       return errorWriter.write(exchange, HttpStatus.NOT_FOUND, "Not found");
   }
   ```

   This runs before any routing — the request never reaches the `blocked-internal` route.

2. **Never trust identity headers sent by a client.** The three identity headers are removed from
   the incoming request: `headers.remove(HEADER_USER_ID)`, `headers.remove(HEADER_USER_EMAIL)`,
   `headers.remove(HEADER_USER_ROLES)`.

3. **Preflight and public endpoints need no token.**

   ```java
   if (request.getMethod() == HttpMethod.OPTIONS || PUBLIC_PATHS.contains(path)) {
       return chain.filter(exchange.mutate().request(builder.build()).build());
   }
   ```

   So the **auth-free** paths are exactly `POST /api/auth/register`, `POST /api/auth/login`,
   `POST /api/auth/verify` and **any** `OPTIONS` request to any path.

   The path match is **exact** (`Set.contains(path)`), not prefix-based. So `/api/auth/login/extra`
   is **not** public — it needs a token and then 404s downstream.

4. **Everything else needs a valid Bearer token.**

   ```java
   String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
   if (authHeader == null || !authHeader.startsWith("Bearer ")) {
       return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
   }
   Optional<AuthenticatedUser> user = tokenValidator.validate(authHeader.substring(7).trim());
   if (user.isEmpty()) {
       return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
   }
   ```

   The header must literally start with `"Bearer "` (capital B, trailing space). The token is
   `authHeader.substring(7).trim()`.

### How the JWT is validated

`src/main/java/com/banking/gateway/security/JwtTokenValidator.java`:

* Algorithm: **HMAC-SHA** (`Keys.hmacShaKeyFor`), verified with jjwt 0.12.6
  (`Jwts.parser().verifyWith(key).build().parseSignedClaims(token)`).
* The key comes from `app.jwt.secret` → the `JWT_SECRET` env var.
* A token is rejected (→ `401`) when: the signature does not match, the token is expired, the token
  is malformed, the token is unsigned, **or the `sub` claim is missing/blank**.
* The `roles` claim is only read if it is a `List`; a non-list value yields an empty role list.
* `AuthenticatedUser` is `record AuthenticatedUser(String userId, String email, List<String> roles)`.

The user service signs the token in `user-service/src/main/java/com/banking/user/security/JwtService.java`:
`subject` = `String.valueOf(user.getId())`, claim `username` = the e-mail, claim `roles` =
`List.of(user.getRole().name())`, plus `issuedAt` and `expiration` = now + `app.jwt.expiration-ms`
(3600000 ms = 60 min).

### The rate limiter

`src/main/java/com/banking/gateway/filter/RateLimitFilter.java`:

* It only applies to **`POST`** requests (`if (request.getMethod() != HttpMethod.POST) return chain.filter(exchange);`).
* The path must match a rule **exactly** (`r.path().equals(path)`).
* Counter key: **`"rl:" + path + ":" + clientIp(request)`** in Redis.
* The window starts with the first request, because `setIfAbsent` attaches the expiry:

  ```java
  Mono<Boolean> allowed = redis.opsForValue().setIfAbsent(key, "0", window)
          .then(redis.opsForValue().increment(key))
          .map(count -> count <= rule.limit())
  ```

* The client IP is the **socket address**, not `X-Forwarded-For` — the comment says:
  *"The socket address, not X-Forwarded-For: a client can write any value into that header."*
  If the address is unknown the key uses the literal `"unknown"`.
* **Fail open**: if Redis is unavailable the request is allowed and the error is logged:

  ```java
  .onErrorResume(ex -> {
      // Fail open: a Redis problem must not take login down. The error is logged.
      log.error("Rate limiter unavailable, allowing the request: {}", ex.getMessage());
      return Mono.just(true);
  });
  ```

* On rejection it sets `Retry-After: <remaining seconds, min 1>` and returns 429.

Live verification — 21 POSTs to `/api/auth/verify` from `::1`:

```
req 1  -> 400   (the user service rejected the body)
...
req 20 -> 400
req 21 -> 429
BODY: {"timestamp":"2026-11-02T17:32:23.611879110","status":429,"error":"Too Many Requests","message":"Too many requests, please try again later"}
Retry-After: 3599
```

The Redis keys created are `rl:/api/auth/register:0:0:0:0:0:0:0:1`,
`rl:/api/auth/login:0:0:0:0:0:0:0:1`, `rl:/api/auth/verify:0:0:0:0:0:0:0:1` (the counter is
incremented by `increment` after `setIfAbsent` sets it to `"0"`, so the first request leaves the
counter at `1`).

### CORS

Configured globally under
`spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**]`:

* `allowedOrigins`: `http://localhost:3000`, `http://127.0.0.1:3000`
* `allowedMethods`: `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`
* `allowedHeaders`: `Authorization`, `Content-Type`, `Accept`
* `maxAge`: `3600`

A live preflight:

```
$ curl -i -X OPTIONS -H 'Origin: http://localhost:3000' \
       -H 'Access-Control-Request-Method: POST' \
       -H 'Access-Control-Request-Headers: Authorization,Content-Type' \
       http://localhost:8080/api/auth/login
HTTP/1.1 200 OK
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Access-Control-Allow-Origin: http://localhost:3000
Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS
Access-Control-Allow-Headers: Authorization, Content-Type
Access-Control-Max-Age: 3600
content-length: 0
```

The `OPTIONS` preflight is answered **200** only for a path that has a route — for example
`OPTIONS /api/anything` answers **404**, because the global CORS filter only adds headers to a
response it can produce and an unrouted path never reaches a handler.

### The registry is fetched every 5 seconds

`eureka.client.registry-fetch-interval-seconds: 5` — set in
`api-gateway/src/main/resources/application.yaml:85`. The in-file comment explains the reason:

> `# The registry was fetched once at startup and then only every 30 seconds.`
> `# After a restart the gateway answered the first request of every route`
> `# with 503 "No servers available for service: ...", because the load`
> `# balancer cache was still empty. A shorter interval closes that window.`

The Spring Cloud default is `30` (confirmed in `spring-cloud-netflix-eureka-client-5.0.3-sources.jar`,
`EurekaClientConfigBean.registryFetchIntervalSeconds = 30`). The gateway's `defaultZone` is
hard-coded to `http://localhost:8761/eureka` (no `${EUREKA_URL:...}` placeholder).

### Status codes at a glance

| Situation | Status | Body |
|---|---|---|
| Public auth endpoint reached | forwarded | — |
| Protected path, no `Authorization` header | `401` | `Missing or invalid Authorization header` |
| Protected path, header not starting with `Bearer ` | `401` | `Missing or invalid Authorization header` |
| Protected path, token invalid | `401` | `Invalid or expired token` |
| `/internal` or `/internal/...` | `404` | `Not found` |
| Rate limit exceeded | `429` | `Too many requests, please try again later` (+ `Retry-After`) |
| Route exists but the load balancer has no instance | `503` | `No servers available for service: <name>` |
| Path matches no route | `404` | Spring WebFlux default body (`timestamp`, `path`, `status`, `error`, `requestId`) |
| Authenticated but not authorised | — | **no 403 exists** — the gateway does no authorization |

## Running It

### Prerequisites

- Java 17
- Maven 3.9+ (or the bundled wrapper `./mvnw`)
- The discovery service on port 8761 (route resolution)
- Redis on port 6379 (rate limiter)
- Transitively: `user-service` (8083), `account-service` (8081) and `fund-transfer-service` (8082),
  or at least their registrations in Eureka

### Build and run

```bash
cd api-gateway
mvn clean package -DskipTests
java -jar target/api-gateway-0.0.1-SNAPSHOT.jar
```

Or as part of the whole platform:

```bash
cd /home/bs01092/Projects/banking-microservices
./run-all.sh
```

`run-all.sh` starts the gateway **last**, on port 8080, and then waits until it can actually route
a request (it POSTs to `/api/auth/login` and accepts `401` or `400` as success — `503` means the
gateway had no instance for the route yet).

### Environment variables to export

```bash
export REDIS_PASSWORD='redis2188'
export JWT_SECRET='78f2b81005ac23edf307e4368594d68fb1038cc667bc12f5b99946b8ea9e5ccb'
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export SERVER_PORT=8080
```

**The working directory matters.** `spring.config.import: optional:file:.env[.properties]` reads
`./.env` relative to the working directory, so the gateway must be started **from inside
`api-gateway/`** for `api-gateway/.env` to be picked up. `run-all.sh` does `pushd "$ROOT/$name"`.

### How to check it is healthy

```bash
# The actuator, on the private port
curl -s http://localhost:18080/actuator/health
# {"status":"UP"}

# The actuator is not on the public port
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/health
# 404

# The gateway routes: a login attempt without valid credentials reaches the user service
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
  -d '{"email":"nobody@example.invalid","password":"x"}' http://localhost:8080/api/auth/login
# 401  (a 503 means the gateway has no instance for the route yet)
```

To check the whole platform:

```bash
cd /home/bs01092/Projects/banking-microservices
./check-all.sh
```

### How to stop it

```bash
# If you started it with ./run-all.sh
cd /home/bs01092/Projects/banking-microservices
./stop-all.sh

# If you started it by hand
kill <pid>
```

## API Quick Reference (curl examples)

```bash
# Register (public, no token)
curl -i -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Ada Lovelace","email":"ada@example.com","password":"supersecret"}'
# 202 {"message":"If this email can be registered, we have sent you a message. Please check your inbox."}

# Verify the e-mail (public, no token)
curl -i -X POST http://localhost:8080/api/auth/verify \
  -H 'Content-Type: application/json' \
  -d '{"token":"<token-from-the-email>"}'
# 200

# Log in (public, no token)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"supersecret"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

# A protected route without a token
curl -i http://localhost:8080/api/accounts
# 401 {"timestamp":"...","status":401,"error":"Unauthorized","message":"Missing or invalid Authorization header"}

# A protected route with a broken token
curl -i -H 'Authorization: Bearer garbage' http://localhost:8080/api/accounts
# 401 {"timestamp":"...","status":401,"error":"Unauthorized","message":"Invalid or expired token"}

# A protected route with a valid token
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/accounts
# 200

# Open an account (202 — the account is PENDING until the ledger created it)
curl -i -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"accountType":"SAVINGS"}'
# 202

# Start a transfer (202 — the saga runs in the background)
TRANSFER=$(curl -s -X POST http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"fromAccountNumber":"ACC-12345678","toAccountNumber":"ACC-87654321","amount":10.00}')
echo "$TRANSFER"

# Follow the transfer
TRANSFER_ID=$(echo "$TRANSFER" | python3 -c 'import json,sys; print(json.load(sys.stdin)["transferId"])')
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/transfers/$TRANSFER_ID"

# Confirm with the one-time password
curl -i -X POST "http://localhost:8080/api/transfers/$TRANSFER_ID/confirm" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"otp":"123456"}'

# The internal API is never forwarded
curl -i http://localhost:8080/internal/events/topics
# 404 {"timestamp":"...","status":404,"error":"Not Found","message":"Not found"}

# The CORS preflight
curl -i -X OPTIONS -H 'Origin: http://localhost:3000' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: Authorization,Content-Type' \
  http://localhost:8080/api/auth/login
# 200 with Access-Control-Allow-Origin: http://localhost:3000
```

## Configuration Reference (quick table)

| Knob | Default | Change it when |
|---|---|---|
| `server.port` | `8080` | Port 8080 is taken on your machine |
| `app.jwt.secret` (`JWT_SECRET`) | `banking-microservices-local-dev-secret-key-2026` | Always — it must match the user-service secret |
| `spring.data.redis.password` (`REDIS_PASSWORD`) | *(no default)* | Always — the gateway will not start without it |
| `spring.data.redis.host` / `.port` | `127.0.0.1` / `6379` | Your Redis runs elsewhere |
| `app.rate-limit.rules[0]` (`/api/auth/register`) | `10` per `3600` s | Register is being hammered by bots |
| `app.rate-limit.rules[1]` (`/api/auth/login`) | `30` per `300` s | Users share an IP behind a NAT |
| `app.rate-limit.rules[2]` (`/api/auth/verify`) | `20` per `3600` s | Verification is rate-limited too tightly |
| `eureka.client.registry-fetch-interval-seconds` | `5` | You want fewer registry fetches |
| `spring.cloud.gateway.server.webflux.globalcors...allowedOrigins` | `http://localhost:3000`, `http://127.0.0.1:3000` | You serve the web UI from another origin |
| `management.server.port` | `18080` | Prometheus already scrapes something on that port |
| `spring.config.import` | `optional:file:.env[.properties]` | You always pass the secrets as environment variables |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| The gateway does not start, with a Redis error | `spring.data.redis.password` has no default | `export REDIS_PASSWORD=...` (the root `run.env` sets it to `redis2188`) |
| Every protected route answers `401 Invalid or expired token` | The gateway and the user service use **different** secrets — the gateway's YAML default is `banking-microservices-local-dev-secret-key-2026`, the user-service's is `78f2b810…5ccb` | Set `JWT_SECRET` to the **same** value in both services, or start the gateway from `api-gateway/` so `api-gateway/.env` is picked up |
| Every protected route answers `401 Missing or invalid Authorization header` | The header does not start with the literal `Bearer ` (capital B, trailing space), or the path is not one of the three public ones | Check the header format; note that `/api/auth/login/extra` is **not** public |
| `503 No servers available for service: user-service` | The gateway's registry cache is empty or stale | Wait for the next fetch (every 5 s) and check that the service is registered in Eureka on port 8761 |
| `503 No servers available for service: ...` right after a restart | The load balancer cache was still empty | This is exactly what `registry-fetch-interval-seconds: 5` shortens; it is a window of a few seconds |
| The web UI shows a CORS error in the browser console | The UI is not served from `http://localhost:3000` or `http://127.0.0.1:3000` | Serve the UI on one of those two origins; there is no wildcard in `allowedOrigins` |
| `429 Too many requests, please try again later` on `/api/auth/login` | The Redis counter for `rl:/api/auth/login:<ip>` reached the limit | Wait for the window to expire, or delete the key: `redis-cli -a "$REDIS_PASSWORD" DEL "rl:/api/auth/login:<ip>"` |
| `429` even though you did not make 30 logins | The counter key uses the **socket address** — every user behind one NAT shares the counter | Raise `app.rate-limit.rules[1].limit`, or delete the key |
| The rate limiter does not limit anything | Redis is down — the filter **fails open** and logs `Rate limiter unavailable, allowing the request` | Fix Redis |
| `404 {"message":"Not found"}` on `/internal/...` | This is intended — the gateway blocks `/internal/**` | Call the owning service directly on its own port |
| `404` with a body that has `requestId` instead of `message` | The path matched **no route** at all, so the WebFlux default error handler answered | Use one of `/api/auth/**`, `/api/accounts/**`, `/api/transfers/**` |
| `Port 8080 was already in use` | Another instance is still running | `./stop-all.sh`, or `lsof -i :8080` |
| `run-all.sh` prints `The gateway still does not route requests` | The gateway is up but has no instance for a route | Check `logs/api-gateway.log` and the Eureka dashboard on port 8761 |

## Notes and Known Limitations

- **The JWT secret defaults do not match between the gateway and the user service.** The running
  system only works because `api-gateway/.env` and `user-service/.env` both set
  `JWT_SECRET=78f2b810…5ccb`. Anyone starting the gateway from another working directory will get
  `401` on every protected route.
- **The gateway performs no authorization.** There is no `hasRole` / `hasAuthority` check anywhere
  in `api-gateway/`, and no `403` can ever come from the gateway. `X-User-Roles` is injected but
  **no service reads it**.
- **The `blocked-internal` route is dead code in practice.** The `JwtAuthenticationFilter` answers
  `404` for `/internal/**` before routing, so the route with the URI `no://op` is never reached.
- **The rate limiter is POST-only and path-exact.** A `GET` to `/api/auth/login` is not limited,
  and `/api/auth/login/extra` does not match the rule.
- **The rate limiter fails open.** A Redis outage removes the protection silently apart from the
  log line `Rate limiter unavailable, allowing the request: …`.
- **The client IP is the socket address, not `X-Forwarded-For`.** Behind a reverse proxy every user
  shares one counter, and the counter key can contain a literal `unknown` if the address is missing.
- **The gateway is reactive.** The `pom.xml` carries an explicit warning: *"Gateway is reactive.
  Do NOT add spring-boot-starter-web here."*
- **The gateway's `defaultZone` is hard-coded** to `http://localhost:8761/eureka` — there is no
  `${EUREKA_URL:...}` placeholder, unlike every other service.
- **`spring.config.import` reads `./.env` relative to the working directory.** The gateway must be
  started from inside `api-gateway/`.
- **The `local` profile must not be active in the integrated system.** `run-all.sh` unsets
  `SPRING_PROFILES_ACTIVE` for exactly that reason; with the profile active, Eureka is disabled and
  the three services are reached through hard-coded URLs.
- **`run-all.sh` prints a wrong hint for Kafka.** It says
  *"Start it first: docker compose up -d kafka kafka-init"* and refers to a `kafka-init` container,
  but **no `kafka-init` service exists** in either compose file. The command that actually works is
  `docker compose -f event-bus-service/docker-compose.yml up -d`.
- **The test is a plain `contextLoads()`** in
  `src/test/java/com/banking/gateway/ApiGatewayApplicationTests.java`.
- **Maven coordinates**: `com.banking:api-gateway:0.0.1-SNAPSHOT`. Dependencies:
  `spring-cloud-starter-gateway-server-webflux`,
  `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-loadbalancer`,
  `jjwt-api`/`jjwt-impl`/`jjwt-jackson` 0.12.6, `spring-boot-starter-data-redis-reactive`,
  `spring-boot-starter-actuator`, `micrometer-registry-prometheus`,
  `spring-boot-starter-test`. Spring Boot 3.5.5, Spring Cloud BOM 2025.0.0, Java 17.

# api-gateway

**Author:** [Jahid Hasan](https://github.com/Md-Jahid-Hasan-10)

**Project:** Banking Microservices

> The single front door of the banking platform: it routes public URL prefixes to Eureka services via `lb://`, validates the JWT bearer token and turns it into trusted identity headers, applies CORS for the web UI, and rate-limits the sensitive auth endpoints with Redis. Public port **8080**, actuator port **18080**.

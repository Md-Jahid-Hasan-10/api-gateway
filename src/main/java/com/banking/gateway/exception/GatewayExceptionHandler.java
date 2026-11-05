package com.banking.gateway.exception;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private static final String TIMEOUT_MESSAGE = "The service did not respond in time";
    private static final String UNAVAILABLE_MESSAGE = "The service is currently unavailable";
    private static final String BAD_RESPONSE_MESSAGE = "The service sent an unexpected answer";

    private final GatewayErrorWriter errorWriter;

    public GatewayExceptionHandler(GatewayErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        // The connect timeout is also a ConnectException, so the timeouts must be checked first.
        if (hasCause(ex, ConnectTimeoutException.class)
                || hasCause(ex, ReadTimeoutException.class)
                || hasCause(ex, TimeoutException.class)) {
            return errorWriter.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "DOWNSTREAM_TIMEOUT", TIMEOUT_MESSAGE);
        }
        if (hasCause(ex, ConnectException.class)) {
            return errorWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "DOWNSTREAM_UNAVAILABLE", UNAVAILABLE_MESSAGE);
        }
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status != null && status != HttpStatus.INTERNAL_SERVER_ERROR) {
                return switch (status) {
                    case GATEWAY_TIMEOUT -> errorWriter.write(exchange, status, "DOWNSTREAM_TIMEOUT", TIMEOUT_MESSAGE);
                    case SERVICE_UNAVAILABLE -> errorWriter.write(exchange, status, "DOWNSTREAM_UNAVAILABLE", UNAVAILABLE_MESSAGE);
                    case BAD_GATEWAY -> errorWriter.write(exchange, status, "DOWNSTREAM_BAD_RESPONSE", BAD_RESPONSE_MESSAGE);
                    case NOT_FOUND -> errorWriter.write(exchange, status, "NOT_FOUND", "Not found");
                    default -> errorWriter.write(exchange, status, status.getReasonPhrase());
                };
            }
        }

        log.error("Unhandled gateway error on {}", exchange.getRequest().getURI().getPath(), ex);
        return errorWriter.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
    }

    private boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
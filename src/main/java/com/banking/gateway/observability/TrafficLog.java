package com.banking.gateway.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes one structured log line. The fields are put into the logging context (MDC) only while the line is
 * written. The JSON log file then holds them as separate members, so Loki can filter on them.
 * Use the logger name "banking.traffic" to change the level (DEBUG also shows reads).
 */
@Component
public class TrafficLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("banking.traffic");

    public boolean enabled(Level level) {
        return switch (level) {
            case TRACE -> LOGGER.isTraceEnabled();
            case DEBUG -> LOGGER.isDebugEnabled();
            case INFO -> LOGGER.isInfoEnabled();
            case WARN -> LOGGER.isWarnEnabled();
            case ERROR -> LOGGER.isErrorEnabled();
        };
    }

    public void log(Level level, String message, Map<String, String> fields) {
        Map<String, String> before = new HashMap<>();
        fields.forEach((key, value) -> {
            if (value != null) {
                before.put(key, MDC.get(key));
                MDC.put(key, value);
            }
        });
        try {
            switch (level) {
                case TRACE -> LOGGER.trace(message);
                case DEBUG -> LOGGER.debug(message);
                case INFO -> LOGGER.info(message);
                case WARN -> LOGGER.warn(message);
                case ERROR -> LOGGER.error(message);
            }
        } finally {
            // Give back what was there before (for example the transferId set by a Kafka listener).
            before.forEach((key, old) -> {
                if (old == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, old);
                }
            });
        }
    }
}

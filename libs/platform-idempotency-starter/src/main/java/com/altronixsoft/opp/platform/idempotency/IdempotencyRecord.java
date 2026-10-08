package com.altronixsoft.opp.platform.idempotency;

import java.util.List;
import java.util.Map;

/**
 * A row of {@code idempotency_record}.
 *
 * @param requestHash hex SHA-256 of method, path and canonical body of the request that claimed the key
 * @param responseStatus HTTP status of the stored response; {@code null} while {@code IN_PROGRESS}
 * @param responseHeaders replayable response headers; {@code null} while {@code IN_PROGRESS}
 * @param responseBody response bytes; {@code null} while {@code IN_PROGRESS}
 */
public record IdempotencyRecord(
        Status status,
        String requestHash,
        Integer responseStatus,
        Map<String, List<String>> responseHeaders,
        byte[] responseBody) {

    /** State of the key. */
    public enum Status {
        IN_PROGRESS,
        COMPLETED
    }
}

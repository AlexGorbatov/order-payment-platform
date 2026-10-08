package com.altronixsoft.opp.platform.idempotency;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * The buffering response, which also remembers whether the response was produced with {@code sendError}.
 *
 * <p>Spring MVC's default handling of, for example, a validation failure calls {@code sendError(400)}; the body is then
 * rendered later by the container's error dispatch to {@code /error} and is not part of the buffered content. Such a
 * response is stored as "status via sendError" and replayed the same way, so the client gets an answer of the same shape
 * as the original one instead of an empty body.
 */
final class BufferedResponse extends ContentCachingResponseWrapper {

    private boolean errorSent;

    BufferedResponse(HttpServletResponse response) {
        super(response);
    }

    boolean errorSent() {
        return errorSent;
    }

    @Override
    public void sendError(int status) throws IOException {
        errorSent = true;
        super.sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
        errorSent = true;
        super.sendError(status, message);
    }
}

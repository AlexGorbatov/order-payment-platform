package com.altronixsoft.opp.platform.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Buffers what the {@link IdempotencyInterceptor} needs and nothing else, and only for requests that carry an
 * {@code Idempotency-Key}: the request body is read fully (so it can be hashed before the controller runs and still be
 * read by it), and the response is captured by a {@link BufferedResponse} (so the interceptor can store
 * it after the controller ran). The captured body is written to the client when the chain returns.
 *
 * <p>Requests without the header — including every request to a streaming endpoint — are not touched. Form posts are
 * not buffered either (reading the body would consume the parameters); their parameters are hashed instead.
 */
public class IdempotencyBufferingFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;

    public IdempotencyBufferingFilter(DataSize maxBodySize) {
        this.maxBodyBytes = maxBodySize.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(IdempotencyInterceptor.HEADER_KEY) == null) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest forwarded = request;
        if (!isForm(request)) {
            if (request.getContentLengthLong() > maxBodyBytes) {
                tooLarge(response);
                return;
            }
            byte[] body = request.getInputStream().readNBytes((int) maxBodyBytes + 1);
            if (body.length > maxBodyBytes) {
                tooLarge(response);
                return;
            }
            forwarded = new CachedBodyRequest(request, body);
        }
        BufferedResponse buffered = new BufferedResponse(response);
        try {
            chain.doFilter(forwarded, buffered);
        } finally {
            buffered.copyBodyToResponse();
        }
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        ProblemResponses.write(
                response,
                413,
                ProblemResponses.BODY_TOO_LARGE,
                "Request body too large",
                "A request with an Idempotency-Key may not exceed " + maxBodyBytes + " bytes.");
    }

    static boolean isForm(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith("application/x-www-form-urlencoded");
    }
}

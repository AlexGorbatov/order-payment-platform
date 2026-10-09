package com.altronixsoft.opp.order.adapter.in.web;

import com.altronixsoft.opp.order.application.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id (architecture §9.2): the caller's {@code X-Correlation-Id} when it is a UUID,
 * otherwise a new one. The id is echoed in the response header, put into the logging context and handed to the use
 * cases, whose events carry it. Runs first, so even a rejected request answers with its id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    /** Request attribute holding the {@link UUID}. */
    public static final String ATTRIBUTE = "com.altronixsoft.opp.order.correlationId";

    private static final String MDC_KEY = "correlationId";

    private final IdGenerator ids;

    CorrelationIdFilter(IdGenerator ids) {
        this.ids = ids;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID correlationId = parse(request.getHeader(HEADER)).orElseGet(ids::newId);
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId.toString());
        MDC.put(MDC_KEY, correlationId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** A UUID in canonical form, or nothing: anything else from the outside is replaced rather than propagated. */
    static Optional<UUID> parse(String header) {
        if (header == null || header.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(header));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

package com.altronixsoft.opp.platform.messaging.deadletter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator API for dead letters (architecture §11), restricted to the {@code OPS} role by {@code @PreAuthorize}.
 *
 * <p>This starter configures <b>no</b> security: the service owns its filter chain (authentication, JWT → authorities)
 * and must enable method security ({@code @EnableMethodSecurity}); the controller is only registered when Spring
 * Security is present, and application startup fails if method security is missing, so the endpoints can never be
 * exposed without their role check. Errors are RFC 9457 problem details.
 *
 * <pre>
 * GET  /admin/dead-letters?status=&amp;topic=&amp;page=&amp;size=   list, newest first
 * GET  /admin/dead-letters/{id}                          one dead letter with payload and headers
 * POST /admin/dead-letters/{id}/replay                   re-publish to the original topic (once), status REPLAYED
 * POST /admin/dead-letters/{id}/resolve                  {"comment": "..."} status RESOLVED
 * </pre>
 */
@RestController
@RequestMapping("/admin/dead-letters")
@PreAuthorize("hasRole('OPS')")
public class DeadLetterAdminController {

    static final int MAX_PAGE_SIZE = 100;

    private final DeadLetterService service;

    public DeadLetterAdminController(DeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<Summary> list(
            @RequestParam(required = false) DeadLetterStatus status,
            @RequestParam(required = false) String topic,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        DeadLetterService.Page result = service.list(status, topic, page, size);
        List<Summary> items = result.items().stream().map(Summary::of).toList();
        int totalPages = (int) ((result.totalItems() + size - 1) / size);
        return new PageResponse<>(items, page, size, result.totalItems(), totalPages);
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable UUID id) {
        return Detail.of(service.get(id));
    }

    @PostMapping("/{id}/replay")
    public Detail replay(@PathVariable UUID id) {
        return Detail.of(service.replay(id));
    }

    @PostMapping("/{id}/resolve")
    public Detail resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest request) {
        return Detail.of(service.resolve(id, request.comment()));
    }

    @ExceptionHandler(DeadLetterException.NotFound.class)
    ProblemDetail notFound(DeadLetterException.NotFound e) {
        return problem(HttpStatus.NOT_FOUND, "Dead letter not found", e);
    }

    @ExceptionHandler(DeadLetterException.InvalidState.class)
    ProblemDetail conflict(DeadLetterException.InvalidState e) {
        return problem(HttpStatus.CONFLICT, "Dead letter is in the wrong state", e);
    }

    @ExceptionHandler(DeadLetterException.NotReplayable.class)
    ProblemDetail notReplayable(DeadLetterException.NotReplayable e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Dead letter cannot be replayed", e);
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(title);
        return problem;
    }

    /** @param comment what was done about the dead letter; stored as its note */
    public record ResolveRequest(@NotBlank @Size(max = 1000) String comment) {}

    /** One page of results. */
    public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}

    /** Dead letter without payload and headers, for lists. */
    public record Summary(
            UUID id,
            DeadLetterStatus status,
            String originalTopic,
            String dltTopic,
            int partition,
            long offset,
            String messageKey,
            String exceptionClass,
            String exceptionMessage,
            String note,
            Instant createdAt,
            Instant updatedAt) {

        static Summary of(DeadLetter d) {
            return new Summary(
                    d.id(),
                    d.status(),
                    d.originalTopic(),
                    d.dltTopic(),
                    d.partition(),
                    d.offset(),
                    d.messageKey(),
                    d.exceptionClass(),
                    d.exceptionMessage(),
                    d.note(),
                    d.createdAt(),
                    d.updatedAt());
        }
    }

    /**
     * Full dead letter.
     *
     * @param payload the record value as text (invalid UTF-8 is replaced); may contain personal data, hence the role
     */
    public record Detail(
            UUID id,
            DeadLetterStatus status,
            String originalTopic,
            String dltTopic,
            int partition,
            long offset,
            Integer originalPartition,
            Long originalOffset,
            String messageKey,
            String payload,
            Map<String, String> headers,
            String exceptionClass,
            String exceptionMessage,
            String note,
            Instant createdAt,
            Instant updatedAt) {

        static Detail of(DeadLetter d) {
            return new Detail(
                    d.id(),
                    d.status(),
                    d.originalTopic(),
                    d.dltTopic(),
                    d.partition(),
                    d.offset(),
                    d.originalPartition(),
                    d.originalOffset(),
                    d.messageKey(),
                    new String(d.payload(), StandardCharsets.UTF_8),
                    d.headers(),
                    d.exceptionClass(),
                    d.exceptionMessage(),
                    d.note(),
                    d.createdAt(),
                    d.updatedAt());
        }
    }
}

package com.altronixsoft.opp.payment.adapter.in.web;

import com.altronixsoft.opp.payment.application.ReconcilePaymentsService;
import com.altronixsoft.opp.payment.application.ReconciliationSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator access to reconciliation (architecture §8.4, §11): run it now, or read what the latest run of this instance
 * found. Role {@code ops}; the summary lives in memory and is lost on restart (the metrics are not).
 */
@RestController
@RequestMapping(path = "/admin/reconciliation", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('OPS')")
class ReconciliationAdminController {

    private final ReconcilePaymentsService service;

    ReconciliationAdminController(ReconcilePaymentsService service) {
        this.service = service;
    }

    /** One run, as {@code GET /admin/reconciliation/last} shows it. */
    record SummaryResponse(
            String trigger,
            Instant startedAt,
            Instant finishedAt,
            int checked,
            int drifted,
            int unchanged,
            int failed,
            int deferred,
            List<DriftResponse> drifts) {

        static SummaryResponse of(ReconciliationSummary summary) {
            return new SummaryResponse(
                    summary.trigger().name(),
                    summary.startedAt(),
                    summary.finishedAt(),
                    summary.checked(),
                    summary.drifted(),
                    summary.unchanged(),
                    summary.failed(),
                    summary.deferred(),
                    summary.drifts().stream()
                            .map(d -> new DriftResponse(d.paymentId(), d.from().name(), d.to().name()))
                            .toList());
        }
    }

    record DriftResponse(UUID paymentId, String from, String to) {}

    /** Checks the stale payments against Stripe synchronously and returns the summary. */
    @PostMapping("/run")
    SummaryResponse run() {
        return SummaryResponse.of(service.run(ReconciliationSummary.Trigger.MANUAL));
    }

    /** The latest run of this instance; 404 if none ran since it started. */
    @GetMapping("/last")
    ResponseEntity<?> last() {
        return service.lastRun()
                .<ResponseEntity<?>>map(summary -> ResponseEntity.ok(SummaryResponse.of(summary)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(Problems.of(
                                HttpStatus.NOT_FOUND,
                                Problems.NOT_FOUND,
                                "No reconciliation has run since this instance started")));
    }
}

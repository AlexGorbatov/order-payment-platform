package com.altronixsoft.opp.platform.idempotency.testapp;

import com.altronixsoft.opp.platform.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints with every behaviour the idempotency tests need. Each execution gets a fresh id, so a replay is visible. */
@RestController
public class DemoController {

    public record OrderRequest(@NotBlank String sku, int quantity) {}

    public record OrderResponse(UUID id, String sku, int quantity) {}

    private final DemoState state;

    public DemoController(DemoState state) {
        this.state = state;
    }

    private ResponseEntity<OrderResponse> created(String endpoint, OrderRequest request) {
        state.executed(endpoint);
        UUID id = UUID.randomUUID();
        return ResponseEntity.created(URI.create("/orders/" + id))
                .body(new OrderResponse(id, request.sku(), request.quantity()));
    }

    @PostMapping("/orders")
    @Idempotent
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        return created("orders", request);
    }

    @PostMapping("/slow-orders")
    @Idempotent
    public ResponseEntity<OrderResponse> createSlowOrder(@Valid @RequestBody OrderRequest request)
            throws InterruptedException {
        ResponseEntity<OrderResponse> response = created("slow-orders", request);
        Thread.sleep(400);
        return response;
    }

    @PostMapping("/flaky-exception")
    @Idempotent
    public ResponseEntity<OrderResponse> flakyException(@Valid @RequestBody OrderRequest request) {
        if (state.executed("flaky-exception") == 1) {
            throw new IllegalStateException("first call fails with an unhandled exception");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OrderResponse(UUID.randomUUID(), request.sku(), request.quantity()));
    }

    @PostMapping("/flaky-status")
    @Idempotent
    public ResponseEntity<OrderResponse> flakyStatus(@Valid @RequestBody OrderRequest request) {
        if (state.executed("flaky-status") == 1) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OrderResponse(UUID.randomUUID(), request.sku(), request.quantity()));
    }

    @PostMapping("/always-conflict")
    @Idempotent
    public ResponseEntity<Void> alwaysConflict(@RequestBody OrderRequest request) {
        state.executed("always-conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PostMapping("/always-throttled")
    @Idempotent
    public ResponseEntity<Void> alwaysThrottled(@RequestBody OrderRequest request) {
        state.executed("always-throttled");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }

    @PostMapping("/rejected")
    @Idempotent
    public ResponseEntity<String> rejected(@RequestBody OrderRequest request) {
        state.executed("rejected");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .header("Content-Type", "application/problem+json")
                .header("Location", "/problems/" + UUID.randomUUID())
                .body("{\"type\":\"urn:test:rejected\",\"title\":\"Rejected\",\"status\":422,\"id\":\""
                        + UUID.randomUUID() + "\"}");
    }

    @PostMapping("/no-content")
    @Idempotent
    public ResponseEntity<Void> noContent(@RequestBody OrderRequest request) {
        state.executed("no-content");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/optional")
    @Idempotent(required = false)
    public ResponseEntity<OrderResponse> optional(@Valid @RequestBody OrderRequest request) {
        return created("optional", request);
    }

    @PostMapping("/short-lived")
    @Idempotent(ttl = "PT1S")
    public ResponseEntity<OrderResponse> shortLived(@Valid @RequestBody OrderRequest request) {
        return created("short-lived", request);
    }

    @PostMapping("/plain")
    public ResponseEntity<OrderResponse> plain(@Valid @RequestBody OrderRequest request) {
        return created("plain", request);
    }

    @PostMapping(value = "/form", consumes = "application/x-www-form-urlencoded")
    @Idempotent
    public ResponseEntity<OrderResponse> form(@RequestParam String sku, @RequestParam int quantity) {
        return created("form", new OrderRequest(sku, quantity));
    }

    @GetMapping("/orders/{id}")
    public String get(@org.springframework.web.bind.annotation.PathVariable String id) {
        return id;
    }
}

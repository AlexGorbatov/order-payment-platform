package com.altronixsoft.opp.e2e.support;

import com.altronixsoft.opp.e2e.support.StripeSimulator.SimEvent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Posts webhook events to {@code payment-service} the way Stripe does: one at a time and in the order given, signed
 * ({@code Stripe-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t.payload)>}), and again, with a pause, as long
 * as the endpoint is unreachable or answers with a server error. A {@code 4xx} is final: what the receiver refused as
 * invalid is not sent again.
 *
 * <p>With {@link #setCopies(int) copies} above one, each event is posted that many times concurrently, which is the
 * duplicate delivery of F09.
 */
final class WebhookSender implements AutoCloseable {

    private static final int MAX_ATTEMPTS = 120;
    private static final Duration RETRY_PAUSE = Duration.ofMillis(500);

    private final Supplier<String> endpoint;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ExecutorService ordered = Executors.newSingleThreadExecutor(r -> daemon(r, "webhook-sender"));
    private final ExecutorService copiesPool = Executors.newCachedThreadPool(r -> daemon(r, "webhook-copy"));
    private final AtomicInteger pending = new AtomicInteger();
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();
    private volatile int copies = 1;

    WebhookSender(Supplier<String> endpoint, String secret) {
        this.endpoint = endpoint;
        this.secret = secret;
    }

    void setCopies(int copies) {
        this.copies = Math.max(1, copies);
    }

    void enqueue(SimEvent event) {
        pending.incrementAndGet();
        ordered.execute(() -> {
            try {
                deliver(event);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    boolean awaitIdle(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (pending.get() > 0) {
            if (Instant.now().isAfter(deadline)) {
                return false;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    List<Integer> statuses() {
        return List.copyOf(statuses);
    }

    /** One POST, signed now, no retries; the HTTP status of the endpoint. */
    int sendOnce(String payload, Instant signedAt) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.get() + "/webhooks/stripe"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Stripe-Signature", signature(payload, signedAt))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            statuses.add(response.statusCode());
            return response.statusCode();
        } catch (IOException e) {
            statuses.add(-1);
            throw new IllegalStateException("Webhook endpoint unreachable: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The header for an arbitrary payload, for tests that post by hand. */
    String signature(String payload, Instant signedAt) {
        long t = signedAt.getEpochSecond();
        return "t=" + t + ",v1=" + hmacSha256(secret, t + "." + payload);
    }

    private void deliver(SimEvent event) {
        int n = copies;
        if (n == 1) {
            sendWithRetry(event);
            return;
        }
        List<Future<?>> sends = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            sends.add(copiesPool.submit(() -> sendWithRetry(event)));
        }
        for (Future<?> send : sends) {
            try {
                send.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void sendWithRetry(SimEvent event) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                int status = sendOnce(event.payload(), Instant.now());
                if (status < 500) {
                    return;
                }
            } catch (IllegalStateException unreachable) {
                // the service is down or restarting; Stripe would try again later, and so do we
            }
            try {
                Thread.sleep(RETRY_PAUSE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("Webhook " + event.id() + " could not be delivered");
    }

    @Override
    public void close() {
        ordered.shutdownNow();
        copiesPool.shutdownNow();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

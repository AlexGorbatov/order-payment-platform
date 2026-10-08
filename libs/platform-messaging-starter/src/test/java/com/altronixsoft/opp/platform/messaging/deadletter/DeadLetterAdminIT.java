package com.altronixsoft.opp.platform.messaging.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.platform.messaging.consumer.AbstractMessagingITAccess;
import com.altronixsoft.opp.platform.messaging.outbox.KafkaTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class DeadLetterAdminIT extends AbstractMessagingITAccess {

    private static final RequestPostProcessor OPS = user("ops1").roles("OPS");
    private static final RequestPostProcessor CUSTOMER = user("customer1").roles("CUSTOMER");

    @Autowired
    MockMvc mvc;

    @Autowired
    DeadLetterRepository repository;

    // ------------------------------------------------------------------------------------------------- security

    @Test
    void onlyOpsMayUseTheApi() throws Exception {
        UUID id = storeDeadLetter("a");

        mvc.perform(get("/admin/dead-letters")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/dead-letters").with(CUSTOMER)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/dead-letters/{id}", id).with(CUSTOMER)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/dead-letters/{id}/replay", id).with(CUSTOMER).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/dead-letters/{id}/resolve", id)
                        .with(CUSTOMER)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/dead-letters").with(OPS)).andExpect(status().isOk());

        // nothing was changed by the rejected calls
        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(DeadLetterStatus.NEW);
        assertThat(count("outbox_event", "true")).isZero();
    }

    // ------------------------------------------------------------------------------------------------- list / get

    @Test
    void listsNewestFirstWithFiltersAndPagination() throws Exception {
        UUID first = storeDeadLetter("orders", 0);
        UUID second = storeDeadLetter("orders", 1);
        UUID third = storeDeadLetter("payments", 2);
        repository.updateStatus(second, DeadLetterStatus.RESOLVED, "done");
        jdbc.sql("UPDATE dead_letter_message SET created_at = now() - interval '2 hours' WHERE id = :id")
                .param("id", first)
                .update();
        jdbc.sql("UPDATE dead_letter_message SET created_at = now() - interval '1 hour' WHERE id = :id")
                .param("id", second)
                .update();

        mvc.perform(get("/admin/dead-letters").with(OPS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].id").value(third.toString()))
                .andExpect(jsonPath("$.items[1].id").value(second.toString()))
                .andExpect(jsonPath("$.items[2].id").value(first.toString()))
                .andExpect(jsonPath("$.items[0].payload").doesNotExist());

        mvc.perform(get("/admin/dead-letters").param("status", "NEW").with(OPS))
                .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/admin/dead-letters").param("status", "RESOLVED").with(OPS))
                .andExpect(jsonPath("$.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.items[0].note").value("done"));
        mvc.perform(get("/admin/dead-letters")
                        .param("topic", "orders.events.v1")
                        .with(OPS))
                .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/admin/dead-letters")
                        .param("topic", "orders.events.v1")
                        .param("status", "NEW")
                        .with(OPS))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()));

        mvc.perform(get("/admin/dead-letters")
                        .param("size", "2")
                        .param("page", "0")
                        .with(OPS))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/admin/dead-letters")
                        .param("size", "2")
                        .param("page", "1")
                        .with(OPS))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()));
    }

    @Test
    void rejectsInvalidPagingAndStatus() throws Exception {
        mvc.perform(get("/admin/dead-letters").param("size", "101").with(OPS)).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/dead-letters").param("size", "0").with(OPS)).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/dead-letters").param("page", "-1").with(OPS)).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/dead-letters").param("status", "BOGUS").with(OPS))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturnsPayloadHeadersAndFailureDetails() throws Exception {
        UUID id = storeDeadLetter("orders", 0);

        mvc.perform(get("/admin/dead-letters/{id}", id).with(OPS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.originalTopic").value("orders.events.v1"))
                .andExpect(jsonPath("$.payload").value("{\"hello\":\"world\"}"))
                .andExpect(jsonPath("$.headers.eventType").value("OrderCreated"))
                .andExpect(jsonPath("$.exceptionClass").value("java.lang.IllegalStateException"));
    }

    @Test
    void unknownIdIsAProblemDetailWith404() throws Exception {
        mvc.perform(get("/admin/dead-letters/{id}", UUID.randomUUID()).with(OPS))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Dead letter not found"));
        mvc.perform(post("/admin/dead-letters/{id}/replay", UUID.randomUUID())
                        .with(OPS)
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------------- replay

    /** Spec: replay after a "fix" is processed — the inbox does not take it for a duplicate. */
    @Test
    void replayAfterTheFixIsProcessedAndASecondReplayIsRejected() throws Exception {
        EventEnvelope<?> envelope = event();
        handler.failEverything(true);
        send(envelope);
        await().atMost(seconds(60)).until(() -> count("dead_letter_message", "true") == 1);
        UUID id =
                jdbc.sql("SELECT id FROM dead_letter_message").query(UUID.class).single();
        assertThat(count("inbox_message", "event_id = '" + envelope.eventId() + "'"))
                .isZero();
        assertThat(handler.effects(envelope.eventId())).isZero();

        handler.failEverything(false); // "the bug is fixed"

        mvc.perform(post("/admin/dead-letters/{id}/replay", id).with(OPS).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLAYED"));

        await().atMost(seconds(30))
                .untilAsserted(
                        () -> assertThat(handler.effects(envelope.eventId())).isEqualTo(1));
        assertThat(count("inbox_message", "event_id = '" + envelope.eventId() + "'"))
                .isEqualTo(1);
        assertThat(handler.lastHeaders())
                .containsEntry("x-replay-of", id.toString())
                .containsEntry("eventType", "OrderCreated")
                .containsEntry("correlationId", envelope.correlationId().toString());
        // the replay travelled through the outbox, with its own row id and the original envelope
        await().atMost(seconds(10)).until(() -> count("outbox_event", "published_at IS NOT NULL") == 1);
        assertThat(jdbc.sql("SELECT topic FROM outbox_event")
                        .query(String.class)
                        .single())
                .isEqualTo(TOPIC);
        assertThat(jdbc.sql("SELECT payload->>'eventId' FROM outbox_event")
                        .query(String.class)
                        .single())
                .isEqualTo(envelope.eventId().toString());
        assertThat(jdbc.sql("SELECT id FROM outbox_event").query(UUID.class).single())
                .isNotEqualTo(envelope.eventId());

        mvc.perform(post("/admin/dead-letters/{id}/replay", id).with(OPS).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Dead letter is in the wrong state"));
        assertThat(count("outbox_event", "true")).isEqualTo(1);
        mvc.perform(get("/admin/dead-letters/{id}", id).with(OPS))
                .andExpect(jsonPath("$.status").value("REPLAYED"));
    }

    @Test
    void concurrentReplaysQueueTheEventOnce() throws Exception {
        UUID id = storeValidEnvelopeDeadLetter(event());

        var calls = java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return mvc.perform(post("/admin/dead-letters/{id}/replay", id)
                                        .with(OPS)
                                        .with(csrf()))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }))
                .toList();
        var statuses =
                calls.stream().map(java.util.concurrent.CompletableFuture::join).toList();

        assertThat(statuses).containsOnlyOnce(200).filteredOn(s -> s != 200).containsOnly(409);
        assertThat(count("outbox_event", "true")).isEqualTo(1);
    }

    @Test
    void aPayloadThatIsNotAnEventEnvelopeCannotBeReplayed() throws Exception {
        UUID id = storeDeadLetter("orders", 0); // payload {"hello":"world"}

        mvc.perform(post("/admin/dead-letters/{id}/replay", id).with(OPS).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Dead letter cannot be replayed"));

        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(DeadLetterStatus.NEW);
        assertThat(count("outbox_event", "true")).isZero();
    }

    @Test
    void aBrokenJsonDeadLetterFromKafkaCannotBeReplayedButCanBeResolved() throws Exception {
        KafkaTestSupport.send(TOPIC, "k", "{broken", Map.of());
        await().atMost(seconds(30)).until(() -> count("dead_letter_message", "true") == 1);
        UUID id =
                jdbc.sql("SELECT id FROM dead_letter_message").query(UUID.class).single();

        mvc.perform(post("/admin/dead-letters/{id}/replay", id).with(OPS).with(csrf()))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/admin/dead-letters/{id}/resolve", id)
                        .with(OPS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"producer bug, message discarded\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    // ------------------------------------------------------------------------------------------------- resolve

    @Test
    void resolveRecordsTheCommentAndCannotBeRepeated() throws Exception {
        UUID id = storeDeadLetter("orders", 0);

        mvc.perform(post("/admin/dead-letters/{id}/resolve", id)
                        .with(OPS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"duplicate of INC-42, handled manually\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.note").value("duplicate of INC-42, handled manually"));

        mvc.perform(post("/admin/dead-letters/{id}/resolve", id)
                        .with(OPS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"again\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/dead-letters/{id}/replay", id).with(OPS).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(repository.findById(id).orElseThrow().note()).isEqualTo("duplicate of INC-42, handled manually");
    }

    @Test
    void resolveRequiresAComment() throws Exception {
        UUID id = storeDeadLetter("orders", 0);

        for (String body : new String[] {"{}", "{\"comment\":\"\"}", "{\"comment\":\"   \"}"}) {
            mvc.perform(post("/admin/dead-letters/{id}/resolve", id)
                            .with(OPS)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(DeadLetterStatus.NEW);
    }

    @Test
    void aReplayedDeadLetterCanBeResolvedAfterwards() throws Exception {
        UUID id = storeValidEnvelopeDeadLetter(event());
        mvc.perform(post("/admin/dead-letters/{id}/replay", id).with(OPS).with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(post("/admin/dead-letters/{id}/resolve", id)
                        .with(OPS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"replayed and verified\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    // ------------------------------------------------------------------------------------------------- fixtures

    private UUID storeDeadLetter(String topicPrefix) {
        return storeDeadLetter(topicPrefix, 0);
    }

    private UUID storeDeadLetter(String topicPrefix, int offset) {
        return store(topicPrefix + ".events.v1", offset, "{\"hello\":\"world\"}", "k-" + offset);
    }

    private UUID storeValidEnvelopeDeadLetter(EventEnvelope<?> envelope) {
        return store(TOPIC, 0, SERDE.toJson(envelope), envelope.partitionKey());
    }

    private UUID store(String originalTopic, int offset, String payload, String key) {
        repository.insertIfAbsent(new DeadLetter(
                null,
                originalTopic,
                originalTopic + "-dlt",
                0,
                offset + 1_000L * Math.abs(payload.hashCode() % 1000),
                0,
                (long) offset,
                key,
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of(
                        "eventType",
                        "OrderCreated",
                        "correlationId",
                        UUID.randomUUID().toString()),
                "java.lang.IllegalStateException",
                "boom",
                DeadLetterStatus.NEW,
                null,
                null,
                null));
        return jdbc.sql("SELECT id FROM dead_letter_message WHERE dlt_topic = :t ORDER BY created_at DESC, id LIMIT 1")
                .param("t", originalTopic + "-dlt")
                .query(UUID.class)
                .single();
    }
}

package com.altronixsoft.opp.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EventCatalogTest {

    private static final Path EVENTS_DOC = Path.of("..", "..", "docs", "events.md");

    static Stream<EventDescriptor> descriptors() {
        return EventCatalog.all().stream();
    }

    @Test
    void catalogCoversEveryPermittedPayloadRecordExactlyOnce() {
        Set<Class<?>> sealedLeaves = new HashSet<>();
        collectLeaves(DomainEvent.class, sealedLeaves);

        List<Class<?>> catalogued = EventCatalog.all().stream()
                .<Class<?>>map(EventDescriptor::payloadType)
                .toList();

        assertThat(catalogued).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(sealedLeaves);
        assertThat(sealedLeaves).hasSize(12).allMatch(Class::isRecord);
    }

    @Test
    void eventTypeNamesAreUniqueAndEqualTheRecordNames() {
        assertThat(EventCatalog.all().stream().map(EventDescriptor::eventType))
                .doesNotHaveDuplicates()
                .allSatisfy(name -> assertThat(EventCatalog.find(name, 1)).isPresent());
        assertThat(EventCatalog.all())
                .allSatisfy(
                        d -> assertThat(d.eventType()).isEqualTo(d.payloadType().getSimpleName()));
    }

    @Test
    void producersAndTopicsFollowTheEventFamily() {
        assertThat(EventCatalog.all()).allSatisfy(d -> {
            if (OrderEvent.class.isAssignableFrom(d.payloadType())) {
                assertThat(d.topic()).isEqualTo(Topics.ORDER_EVENTS);
                assertThat(d.producer()).isEqualTo(Producers.ORDER_SERVICE);
                assertThat(d.aggregateType()).isEqualTo("Order");
            } else {
                assertThat(d.topic()).isEqualTo(Topics.PAYMENT_EVENTS);
                assertThat(d.producer()).isEqualTo(Producers.PAYMENT_SERVICE);
                assertThat(d.aggregateType()).isEqualTo("Payment");
            }
        });
        assertThat(Topics.ORDER_EVENTS).isEqualTo("order.events.v1");
        assertThat(Topics.PAYMENT_EVENTS).isEqualTo("payment.events.v1");
    }

    @Test
    void lookupOfUnknownTypeOrVersionIsEmpty() {
        assertThat(EventCatalog.find("Nope", 1)).isEmpty();
        assertThat(EventCatalog.find("OrderCreated", 2)).isEmpty();
        assertThat(EventCatalog.find(null, 1)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unregisteredPayloadClassIsRejected() {
        Class<? extends DomainEvent> rogue = (Class<? extends DomainEvent>) (Class<?>) String.class;

        assertThatThrownBy(() -> EventCatalog.of(rogue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("descriptors")
    void everyEventHasASchemaOnTheClasspath(EventDescriptor descriptor) {
        assertThat(descriptor.schemaResource()).isEqualTo("schemas/" + descriptor.eventType() + ".v1.json");
        assertThat(getClass().getClassLoader().getResource(descriptor.schemaResource()))
                .as(descriptor.schemaResource())
                .isNotNull();
    }

    @Test
    void everySchemaFileBelongsToACataloguedEvent() throws IOException {
        Path schemas = Path.of("src", "main", "resources", "schemas");
        try (Stream<Path> files = Files.list(schemas)) {
            List<String> names =
                    files.map(p -> p.getFileName().toString()).sorted().toList();
            List<String> expected = new ArrayList<>(EventCatalog.all().stream()
                    .map(d -> d.schemaResource().substring("schemas/".length()))
                    .toList());
            expected.add("envelope.v1.json");

            assertThat(names).containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("descriptors")
    void eventsMdDocumentsEveryEvent(EventDescriptor descriptor) throws IOException {
        String doc = Files.readString(EVENTS_DOC);

        assertThat(doc).contains("### " + descriptor.eventType());
        assertThat(doc).contains(descriptor.topic()).contains(descriptor.producer());
    }

    @Test
    void eventTypeConstantsMatchTheCatalog() throws IllegalAccessException {
        List<String> constants = new ArrayList<>();
        for (var field : EventTypes.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.add((String) field.get(null));
            }
        }

        assertThat(constants)
                .containsExactlyInAnyOrderElementsOf(EventCatalog.all().stream()
                        .map(EventDescriptor::eventType)
                        .toList());
    }

    private static void collectLeaves(Class<?> type, Set<Class<?>> leaves) {
        if (type.isSealed()) {
            for (Class<?> permitted : type.getPermittedSubclasses()) {
                collectLeaves(permitted, leaves);
            }
        } else {
            leaves.add(type);
        }
    }
}

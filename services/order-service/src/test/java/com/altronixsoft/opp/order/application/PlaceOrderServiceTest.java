package com.altronixsoft.opp.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.order.domain.InvalidOrderException;
import com.altronixsoft.opp.order.domain.Money;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderDomainEvent;
import com.altronixsoft.opp.order.domain.OrderStatus;
import com.altronixsoft.opp.order.domain.Product;
import com.altronixsoft.opp.order.domain.TransitionSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaceOrderServiceTest {

    private static final UUID CORRELATION_ID = UUID.fromString("0199e0a0-3333-7000-8000-000000000003");

    private final RecordingEventPublisher events = new RecordingEventPublisher();

    private static final UUID ORDER_ID = UUID.fromString("0199e0a0-1111-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00.123456789Z");

    private final Map<UUID, Order> stored = new HashMap<>();
    private final List<Collection<String>> catalogQueries = new ArrayList<>();
    private final Map<String, Product> catalog = new HashMap<>();
    private PlaceOrderService service;

    @BeforeEach
    void wire() {
        add("MUG-JAVA", "Coffee mug", 1299, "EUR", true);
        add("STICKERS-PACK", "Sticker pack", 499, "EUR", true);
        add("OLD-MOUSE", "Wired mouse", 1599, "EUR", false);
        add("DOLLAR-ITEM", "Priced in dollars", 1000, "USD", true);
        OrderRepository repository = new OrderRepository() {
            @Override
            public Optional<Order> findById(UUID id) {
                return Optional.ofNullable(stored.get(id));
            }

            @Override
            public OrderPage findPage(int page, int size) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OrderPage findPageByCustomer(String customerId, int page, int size) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Order save(Order order) {
                stored.put(order.id(), order);
                return order;
            }

            @Override
            public List<Order> lockOverduePendingPayment(Instant placedBefore, int limit) {
                throw new UnsupportedOperationException();
            }
        };
        ProductCatalog products = new ProductCatalog() {
            @Override
            public List<Product> findBySkus(Collection<String> skus) {
                catalogQueries.add(List.copyOf(skus));
                return skus.stream().map(catalog::get).filter(p -> p != null).toList();
            }

            @Override
            public List<Product> findActive() {
                throw new UnsupportedOperationException();
            }
        };
        service = new PlaceOrderService(repository, events, products, () -> ORDER_ID, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void add(String sku, String name, long price, String currency, boolean active) {
        catalog.put(sku, new Product(sku, name, Money.of(price, currency), active));
    }

    private static PlaceOrderCommand command(PlaceOrderCommand.Line... lines) {
        return new PlaceOrderCommand("customer-1", List.of(lines));
    }

    @Test
    void placesAnOrderWithCatalogNamesAndPrices() {
        Order order = service.place(
                command(new PlaceOrderCommand.Line("MUG-JAVA", 2), new PlaceOrderCommand.Line("STICKERS-PACK", 1)),
                CORRELATION_ID);

        assertThat(order.id()).isEqualTo(ORDER_ID);
        assertThat(order.customerId()).isEqualTo("customer-1");
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.total()).isEqualTo(Money.of(2 * 1299 + 499, "EUR"));
        assertThat(order.items())
                .extracting("sku", "name", "quantity", "unitPrice")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MUG-JAVA", "Coffee mug", 2, Money.of(1299, "EUR")),
                        org.assertj.core.groups.Tuple.tuple("STICKERS-PACK", "Sticker pack", 1, Money.of(499, "EUR")));
        assertThat(stored).containsKey(ORDER_ID);
    }

    @Test
    void theTimestampIsTruncatedToMicrosecondsLikeTheDatabaseStoresIt() {
        Order order = service.place(command(new PlaceOrderCommand.Line("MUG-JAVA", 1)), CORRELATION_ID);

        assertThat(order.createdAt()).isEqualTo(Instant.parse("2026-10-08T12:00:00.123456Z"));
        assertThat(order.history().getFirst().source()).isEqualTo(TransitionSource.API);
    }

    @Test
    void thePlacedEventGoesToThePublisherWithTheCorrelationId() {
        Order order = service.place(command(new PlaceOrderCommand.Line("MUG-JAVA", 3)), CORRELATION_ID);

        assertThat(order.pullDomainEvents()).isEmpty();
        assertThat(events.published)
                .singleElement()
                .satisfies(published -> assertThat(published.correlationId()).isEqualTo(CORRELATION_ID));
        assertThat(events.events()).singleElement().isInstanceOfSatisfying(OrderDomainEvent.Placed.class, placed -> {
            assertThat(placed.customerId()).isEqualTo("customer-1");
            assertThat(placed.total()).isEqualTo(Money.of(3 * 1299, "EUR"));
            assertThat(placed.itemCount()).isEqualTo(1);
        });
    }

    @Test
    void theCatalogIsAskedOnceForTheDistinctSkus() {
        service.place(
                command(new PlaceOrderCommand.Line("MUG-JAVA", 1), new PlaceOrderCommand.Line("STICKERS-PACK", 1)),
                CORRELATION_ID);

        assertThat(catalogQueries).hasSize(1);
        assertThat(catalogQueries.getFirst()).containsExactlyInAnyOrder("MUG-JAVA", "STICKERS-PACK");
    }

    @Test
    void unknownAndInactiveProductsAreReportedTogetherAndSorted() {
        assertThatThrownBy(() -> service.place(
                        command(
                                new PlaceOrderCommand.Line("NOPE", 1),
                                new PlaceOrderCommand.Line("MUG-JAVA", 1),
                                new PlaceOrderCommand.Line("OLD-MOUSE", 1)),
                        CORRELATION_ID))
                .isInstanceOfSatisfying(ProductNotAvailableException.class, e -> {
                    assertThat(e.skus()).containsExactly("NOPE", "OLD-MOUSE");
                    assertThat(e).hasMessageContaining("NOPE").hasMessageContaining("OLD-MOUSE");
                });
        assertThat(stored).isEmpty();
    }

    @Test
    void tooFewOrTooManyLinesAreRejectedBeforeTheCatalogIsAsked() {
        assertThatThrownBy(() -> service.place(command(), CORRELATION_ID)).isInstanceOf(InvalidOrderException.class);
        PlaceOrderCommand.Line[] twentyOne = IntStream.rangeClosed(1, 21)
                .mapToObj(i -> new PlaceOrderCommand.Line("SKU-" + i, 1))
                .toArray(PlaceOrderCommand.Line[]::new);
        assertThatThrownBy(() -> service.place(command(twentyOne), CORRELATION_ID))
                .isInstanceOf(InvalidOrderException.class);

        assertThat(catalogQueries).isEmpty();
        assertThat(stored).isEmpty();
    }

    @Test
    void quantitiesOutsideOneToTenAreRejected() {
        assertThatThrownBy(() -> service.place(command(new PlaceOrderCommand.Line("MUG-JAVA", 0)), CORRELATION_ID))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> service.place(command(new PlaceOrderCommand.Line("MUG-JAVA", 11)), CORRELATION_ID))
                .isInstanceOf(InvalidOrderException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    void theSameSkuTwiceIsRejected() {
        assertThatThrownBy(() -> service.place(
                        command(new PlaceOrderCommand.Line("MUG-JAVA", 1), new PlaceOrderCommand.Line("MUG-JAVA", 2)),
                        CORRELATION_ID))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("MUG-JAVA");
        assertThat(stored).isEmpty();
    }

    @Test
    void mixedCurrenciesAreRejected() {
        assertThatThrownBy(() -> service.place(
                        command(
                                new PlaceOrderCommand.Line("MUG-JAVA", 1),
                                new PlaceOrderCommand.Line("DOLLAR-ITEM", 1)),
                        CORRELATION_ID))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("one currency");
        assertThat(stored).isEmpty();
    }

    @Test
    void aBlankCustomerIsRejected() {
        assertThatThrownBy(() -> service.place(
                        new PlaceOrderCommand(" ", List.of(new PlaceOrderCommand.Line("MUG-JAVA", 1))), CORRELATION_ID))
                .isInstanceOf(InvalidOrderException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    void theCommandHasNoPriceToSpoof() {
        assertThat(PlaceOrderCommand.Line.class.getRecordComponents())
                .extracting("name")
                .containsExactly("sku", "quantity");
    }

    @Test
    void aCommandKeepsItsOwnCopyOfTheLines() {
        List<PlaceOrderCommand.Line> lines = new ArrayList<>(List.of(new PlaceOrderCommand.Line("MUG-JAVA", 1)));
        PlaceOrderCommand command = new PlaceOrderCommand("customer-1", lines);

        lines.clear();

        assertThat(command.lines()).hasSize(1);
    }
}

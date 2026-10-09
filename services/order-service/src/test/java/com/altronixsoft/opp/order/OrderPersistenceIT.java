package com.altronixsoft.opp.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.order.application.OrderConcurrentlyModifiedException;
import com.altronixsoft.opp.order.application.OrderRepository;
import com.altronixsoft.opp.order.application.PlaceOrderCommand;
import com.altronixsoft.opp.order.application.PlaceOrderService;
import com.altronixsoft.opp.order.application.ProductNotAvailableException;
import com.altronixsoft.opp.order.domain.CancelReason;
import com.altronixsoft.opp.order.domain.InvalidOrderException;
import com.altronixsoft.opp.order.domain.Money;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderStatus;
import com.altronixsoft.opp.order.domain.RefundReason;
import com.altronixsoft.opp.order.domain.StatusChange;
import com.altronixsoft.opp.order.domain.TransitionSource;
import com.altronixsoft.opp.order.domain.Trigger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The persistence adapter and the Flyway schema against a real PostgreSQL, through the ports only. */
@SpringBootTest
class OrderPersistenceIT {

    @Autowired
    OrderRepository orders;

    @Autowired
    PlaceOrderService placeOrder;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestDatabase.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", TestKafka::bootstrapServers);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE order_status_history, order_item, orders").update();
    }

    private static Trigger api() {
        return Trigger.api(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private Order place(String customer, PlaceOrderCommand.Line... lines) {
        return placeOrder.place(new PlaceOrderCommand(customer, List.of(lines)), UUID.randomUUID());
    }

    private Order placeDefault() {
        return place(
                "customer-1",
                new PlaceOrderCommand.Line("MUG-JAVA", 2),
                new PlaceOrderCommand.Line("STICKERS-PACK", 1));
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    // ------------------------------------------------------------------------------------------ schema, catalog

    @Test
    void flywayAppliedTheServiceMigrationsAndHibernateAcceptsTheSchema() {
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                        .query(String.class)
                        .list())
                .contains("1", "2");
    }

    @Test
    void theSeededCatalogHasFiveActiveProductsAndOneInactiveAllInEuro() {
        List<Map<String, Object>> products = jdbc.sql(
                        "SELECT sku, price_minor, currency, active FROM product ORDER BY sku")
                .query()
                .listOfRows();

        assertThat(products).hasSize(6);
        assertThat(products).allSatisfy(p -> {
            assertThat(p.get("currency")).isEqualTo("EUR");
            assertThat((Long) p.get("price_minor")).isPositive();
        });
        assertThat(products)
                .filteredOn(p -> Boolean.TRUE.equals(p.get("active")))
                .hasSize(5);
        assertThat(products)
                .filteredOn(p -> Boolean.FALSE.equals(p.get("active")))
                .extracting(p -> p.get("sku"))
                .containsExactly("DISCONTINUED-MOUSE");
    }

    // ------------------------------------------------------------------------------------------ round trip

    @Test
    void aPlacedOrderIsStoredWithCatalogPricesAndReadsBackIdentically() {
        Order placed = placeDefault();

        Order loaded = orders.findById(placed.id()).orElseThrow();

        assertThat(loaded.id()).isEqualTo(placed.id());
        assertThat(loaded.customerId()).isEqualTo("customer-1");
        assertThat(loaded.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(loaded.total()).isEqualTo(Money.of(2 * 1299 + 499, "EUR"));
        assertThat(loaded.items()).isEqualTo(placed.items());
        assertThat(loaded.items()).extracting("sku").containsExactly("MUG-JAVA", "STICKERS-PACK");
        assertThat(loaded.history()).isEqualTo(placed.history());
        assertThat(loaded.createdAt()).isEqualTo(placed.createdAt());
        assertThat(loaded.updatedAt()).isEqualTo(placed.updatedAt());
        assertThat(loaded.disputed()).isFalse();
        assertThat(loaded.cancelReason()).isNull();
        assertThat(loaded.version()).isZero();
        assertThat(loaded.pullDomainEvents()).as("loading registers no events").isEmpty();
    }

    @Test
    void theRowsHoldWhatTheSpecificationSays() {
        Order placed = placeDefault();

        Map<String, Object> order = jdbc.sql("SELECT * FROM orders WHERE id = :id")
                .param("id", placed.id())
                .query()
                .singleRow();
        List<Map<String, Object>> lines = jdbc.sql("SELECT * FROM order_item WHERE order_id = :id ORDER BY id")
                .param("id", placed.id())
                .query()
                .listOfRows();
        Map<String, Object> history = jdbc.sql("SELECT * FROM order_status_history WHERE order_id = :id")
                .param("id", placed.id())
                .query()
                .singleRow();

        assertThat(order)
                .containsEntry("customer_id", "customer-1")
                .containsEntry("status", "PENDING_PAYMENT")
                .containsEntry("currency", "EUR")
                .containsEntry("total_minor", 3097L)
                .containsEntry("disputed", false)
                .containsEntry("version", 0L);
        assertThat(order.get("cancel_reason")).isNull();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .containsEntry("sku", "MUG-JAVA")
                .containsEntry("name", "Coffee mug \"Java\"")
                .containsEntry("quantity", 2)
                .containsEntry("unit_price_minor", 1299L)
                .containsEntry("line_total_minor", 2598L);
        assertThat(history).containsEntry("to_status", "PENDING_PAYMENT").containsEntry("source", "API");
        assertThat(history.get("from_status")).isNull();
    }

    @Test
    void theClientNeverSuppliesAPriceSoThePriceIsTheCatalogsEvenIfItChangesLater() {
        Order placed = place("customer-1", new PlaceOrderCommand.Line("MUG-JAVA", 1));
        jdbc.sql("UPDATE product SET price_minor = 99999 WHERE sku = 'MUG-JAVA'")
                .update();

        try {
            Order loaded = orders.findById(placed.id()).orElseThrow();
            assertThat(loaded.items().getFirst().unitPrice())
                    .as("snapshot at ordering time")
                    .isEqualTo(Money.of(1299, "EUR"));
            assertThat(place("customer-1", new PlaceOrderCommand.Line("MUG-JAVA", 1))
                            .total())
                    .as("new orders use the new catalog price")
                    .isEqualTo(Money.of(99999, "EUR"));
        } finally {
            jdbc.sql("UPDATE product SET price_minor = 1299 WHERE sku = 'MUG-JAVA'")
                    .update();
        }
    }

    @Test
    void unknownIdIsEmpty() {
        assertThat(orders.findById(UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    @Test
    void everyTransitionIsStoredWithItsHistoryAndTheVersionGrows() {
        UUID paymentEvent = UUID.randomUUID();
        Order order = placeDefault();
        assertThat(order.version()).isZero();

        order.markPaid(Trigger.event(paymentEvent, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        order = orders.save(order);
        assertThat(order.version()).isEqualTo(1L);

        order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api());
        order = orders.save(order);
        order.markRefundFailed("insufficient_balance", Trigger.job(Instant.now().truncatedTo(ChronoUnit.MICROS)));
        order = orders.save(order);
        order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api());
        order = orders.save(order);
        order.markRefunded(api());
        order = orders.save(order);

        Order loaded = orders.findById(order.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(loaded.version()).isEqualTo(5L);
        assertThat(loaded.history())
                .extracting(StatusChange::to)
                .containsExactly(
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.PAID,
                        OrderStatus.REFUND_REQUESTED,
                        OrderStatus.REFUND_FAILED,
                        OrderStatus.REFUND_REQUESTED,
                        OrderStatus.REFUNDED);
        assertThat(loaded.history().get(1).source()).isEqualTo(TransitionSource.EVENT);
        assertThat(loaded.history().get(1).sourceEventId()).isEqualTo(paymentEvent);
        assertThat(loaded.history().get(3).source()).isEqualTo(TransitionSource.JOB);
        assertThat(loaded.history().get(3).reason()).isEqualTo("insufficient_balance");
        assertThat(loaded.history()).isEqualTo(order.history());
        assertThat(count("order_status_history")).isEqualTo(6);
        assertThat(count("order_item")).as("lines are written once").isEqualTo(2);
    }

    @Test
    void theCancelReasonAndTheDisputeFlagAreStored() {
        Order order = placeDefault();
        order.cancel(CancelReason.TIMEOUT, Trigger.job(Instant.now().truncatedTo(ChronoUnit.MICROS)));
        order.markDisputed(api());
        orders.save(order);

        Order loaded = orders.findById(order.id()).orElseThrow();

        assertThat(loaded.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(loaded.cancelReason()).isEqualTo(CancelReason.TIMEOUT);
        assertThat(loaded.disputed()).isTrue();
        assertThat(loaded.history().getLast().reason()).isEqualTo("DISPUTED");
        assertThat(jdbc.sql("SELECT cancel_reason FROM orders")
                        .query(String.class)
                        .single())
                .isEqualTo("TIMEOUT");
    }

    @Test
    void aLateRefundAfterCancellationIsStored() {
        Order order = placeDefault();
        order.cancel(CancelReason.CUSTOMER, api());
        order = orders.save(order);

        order.requestRefund(
                RefundReason.LATE_PAYMENT_AFTER_CANCEL,
                UUID.randomUUID(),
                Trigger.event(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MICROS)));
        orders.save(order);

        Order loaded = orders.findById(order.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(OrderStatus.REFUND_REQUESTED);
        assertThat(loaded.cancelReason()).isEqualTo(CancelReason.CUSTOMER);
    }

    @Test
    void aVeryLongFailureReasonIsTruncatedToTheColumn() {
        Order order = placeDefault();
        order.markPaid(api());
        order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api());
        order.markRefundFailed("x".repeat(1000), api());

        orders.save(order);

        assertThat(jdbc.sql("SELECT reason FROM order_status_history WHERE to_status = 'REFUND_FAILED'")
                        .query(String.class)
                        .single())
                .hasSize(255);
    }

    // ------------------------------------------------------------------------------------------ optimistic locking

    @Test
    void savingAnOrderThatWasChangedInTheMeantimeIsRefused() {
        Order original = placeDefault();
        Order first = orders.findById(original.id()).orElseThrow();
        Order second = orders.findById(original.id()).orElseThrow();

        first.markPaid(api());
        orders.save(first);
        second.cancel(CancelReason.CUSTOMER, api());

        assertThatThrownBy(() -> orders.save(second)).isInstanceOf(OrderConcurrentlyModifiedException.class);

        Order stored = orders.findById(original.id()).orElseThrow();
        assertThat(stored.status()).as("the first change stands").isEqualTo(OrderStatus.PAID);
        assertThat(stored.history()).hasSize(2);
        assertThat(count("order_status_history")).isEqualTo(2);
    }

    @Test
    void theArgumentOfSaveIsStaleAfterwardsAndOnlyTheReturnedOrderMayBeChangedFurther() {
        Order order = placeDefault();
        order.markPaid(api());
        Order saved = orders.save(order);

        saved.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api());
        orders.save(saved);

        Order stale = order;
        stale.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api());
        assertThatThrownBy(() -> orders.save(stale)).isInstanceOf(OrderConcurrentlyModifiedException.class);
    }

    /**
     * The version check at load time passes (the transaction sees the version it read), but another transaction commits
     * before this one flushes: the guard is the {@code UPDATE ... WHERE version = ?} of the {@code @Version} column.
     */
    @Test
    void aChangeCommittedBetweenTheCheckAndTheFlushIsDetectedByTheVersionColumn() {
        Order original = placeDefault();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    Order loaded = orders.findById(original.id()).orElseThrow();
                    other.executeWithoutResult(
                            inner -> jdbc.sql("UPDATE orders SET version = version + 1 WHERE id = :id")
                                    .param("id", original.id())
                                    .update());
                    loaded.markPaid(api());
                    orders.save(loaded);
                }))
                .isInstanceOf(OrderConcurrentlyModifiedException.class);

        assertThat(orders.findById(original.id()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(count("order_status_history")).isEqualTo(1);
    }

    @Test
    void ofTwoConcurrentCommandsOnTheSameVersionExactlyOneWins() throws Exception {
        Order original = placeDefault();
        Order forPayment = orders.findById(original.id()).orElseThrow();
        Order forCancel = orders.findById(original.id()).orElseThrow();
        forPayment.markPaid(api());
        forCancel.cancel(CancelReason.CUSTOMER, api());
        CountDownLatch start = new CountDownLatch(1);

        List<CompletableFuture<Boolean>> attempts = List.of(forPayment, forCancel).stream()
                .map(order -> CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        orders.save(order);
                        return true;
                    } catch (OrderConcurrentlyModifiedException e) {
                        return false;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }))
                .toList();
        start.countDown();
        List<Boolean> results = attempts.stream().map(CompletableFuture::join).toList();

        assertThat(results).containsExactlyInAnyOrder(true, false);
        Order stored = orders.findById(original.id()).orElseThrow();
        assertThat(stored.status()).isIn(OrderStatus.PAID, OrderStatus.CANCELLED);
        assertThat(stored.version()).isEqualTo(1L);
        assertThat(stored.history()).hasSize(2);
    }

    // ------------------------------------------------------------------------------------------ use case

    @Test
    void anUnavailableProductFailsTheWholeOrderAndWritesNothing() {
        assertThatThrownBy(() -> place(
                        "customer-1",
                        new PlaceOrderCommand.Line("MUG-JAVA", 1),
                        new PlaceOrderCommand.Line("DISCONTINUED-MOUSE", 1),
                        new PlaceOrderCommand.Line("NO-SUCH-SKU", 1)))
                .isInstanceOfSatisfying(
                        ProductNotAvailableException.class,
                        e -> assertThat(e.skus()).containsExactly("DISCONTINUED-MOUSE", "NO-SUCH-SKU"));

        assertThat(count("orders")).isZero();
        assertThat(count("order_item")).isZero();
    }

    @Test
    void theLimitsHoldEndToEnd() {
        assertThatThrownBy(() -> place("customer-1", new PlaceOrderCommand.Line("MUG-JAVA", 11)))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> place("customer-1")).isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> place(
                        "customer-1",
                        new PlaceOrderCommand.Line("MUG-JAVA", 1),
                        new PlaceOrderCommand.Line("MUG-JAVA", 1)))
                .isInstanceOf(InvalidOrderException.class);
        PlaceOrderCommand.Line[] many = IntStream.rangeClosed(1, 21)
                .mapToObj(i -> new PlaceOrderCommand.Line("SKU-" + i, 1))
                .toArray(PlaceOrderCommand.Line[]::new);
        assertThatThrownBy(() -> place("customer-1", many)).isInstanceOf(InvalidOrderException.class);

        assertThat(count("orders")).isZero();
    }

    @Test
    void aMaximumSizedOrderIsStored() {
        // five active products only; one line per product, quantity 10
        Order order = place(
                "customer-1",
                new PlaceOrderCommand.Line("BOOK-CLEAN-CODE", 10),
                new PlaceOrderCommand.Line("BOOK-DDIA", 10),
                new PlaceOrderCommand.Line("MUG-JAVA", 10),
                new PlaceOrderCommand.Line("TSHIRT-OPP", 10),
                new PlaceOrderCommand.Line("STICKERS-PACK", 10));

        assertThat(orders.findById(order.id()).orElseThrow().total())
                .isEqualTo(Money.of(10L * (3499 + 4999 + 1299 + 1999 + 499), "EUR"));
    }

    @Test
    void theSchemaItselfRejectsWhatTheDomainForbids() {
        UUID id = placeDefault().id();

        assertThatThrownBy(() -> jdbc.sql("UPDATE orders SET status = 'BOGUS' WHERE id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE order_item SET quantity = 11 WHERE order_id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE orders SET total_minor = 0 WHERE id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO order_item (order_id, sku, name, quantity, unit_price_minor, line_total_minor) SELECT order_id, sku, name, 1, 1, 1 FROM order_item LIMIT 1")
                        .update())
                .as("one line per sku and order")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}

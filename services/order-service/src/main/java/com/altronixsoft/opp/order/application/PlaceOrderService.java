package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderItem;
import com.altronixsoft.opp.order.domain.Product;
import com.altronixsoft.opp.order.domain.Trigger;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: a customer places an order.
 *
 * <p>The customer sends SKUs and quantities; this service looks the products up in the catalog, so names and prices are
 * always the server's, and the {@link Order} aggregate enforces the limits (1–20 lines, quantity 1–10, one currency,
 * no duplicate SKU). The order is stored in status {@code PENDING_PAYMENT}.
 *
 * <p>Publishing {@code OrderCreated} is not done here yet: the aggregate has registered its domain event, and handing it
 * to the outbox in this same transaction is the next step (T09).
 */
@Service
public class PlaceOrderService {

    private final OrderRepository orders;
    private final ProductCatalog catalog;
    private final IdGenerator ids;
    private final Clock clock;

    public PlaceOrderService(OrderRepository orders, ProductCatalog catalog, IdGenerator ids, Clock clock) {
        this.orders = orders;
        this.catalog = catalog;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @return the stored order
     * @throws com.altronixsoft.opp.order.domain.InvalidOrderException a limit is exceeded or a line is invalid
     * @throws ProductNotAvailableException a SKU is unknown or inactive
     */
    @Transactional
    public Order place(PlaceOrderCommand command) {
        Order.requireLineCount(command.lines().size());

        List<String> skus = command.lines().stream()
                .map(PlaceOrderCommand.Line::sku)
                .distinct()
                .toList();
        Map<String, Product> products =
                catalog.findBySkus(skus).stream().collect(Collectors.toMap(Product::sku, Function.identity()));
        List<String> unavailable = skus.stream()
                .filter(sku -> products.get(sku) == null || !products.get(sku).active())
                .sorted()
                .toList();
        if (!unavailable.isEmpty()) {
            throw new ProductNotAvailableException(unavailable);
        }

        List<OrderItem> items = command.lines().stream()
                .map(line -> {
                    Product product = products.get(line.sku());
                    return new OrderItem(product.sku(), product.name(), line.quantity(), product.price());
                })
                .toList();
        // The database stores microseconds; truncate here so a stored order equals the one that was placed.
        Trigger trigger = Trigger.api(clock.instant().truncatedTo(ChronoUnit.MICROS));
        return orders.save(Order.place(ids.newId(), command.customerId(), items, trigger));
    }
}

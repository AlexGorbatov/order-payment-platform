package com.altronixsoft.opp.order.adapter.in.web;

import com.altronixsoft.opp.order.application.CancelOrderService;
import com.altronixsoft.opp.order.application.GetOrderService;
import com.altronixsoft.opp.order.application.ListOrdersService;
import com.altronixsoft.opp.order.application.OrderPage;
import com.altronixsoft.opp.order.application.PlaceOrderService;
import com.altronixsoft.opp.order.application.RefundOrderService;
import com.altronixsoft.opp.platform.idempotency.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Orders API (architecture §11). Who may call what is decided in the security filter chain (roles); which orders a
 * caller may see is decided by the use cases (ownership), which answer 404 for orders that are not theirs.
 */
@RestController
@RequestMapping(path = "/api/v1/orders", produces = "application/json")
@Tag(name = "Orders")
class OrderController {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IDEMPOTENT_TTL = "PT24H";

    private final PlaceOrderService placeOrder;
    private final GetOrderService getOrder;
    private final ListOrdersService listOrders;
    private final CancelOrderService cancelOrder;
    private final RefundOrderService refundOrder;

    OrderController(
            PlaceOrderService placeOrder,
            GetOrderService getOrder,
            ListOrdersService listOrders,
            CancelOrderService cancelOrder,
            RefundOrderService refundOrder) {
        this.placeOrder = placeOrder;
        this.getOrder = getOrder;
        this.listOrders = listOrders;
        this.cancelOrder = cancelOrder;
        this.refundOrder = refundOrder;
    }

    @PostMapping
    @Idempotent(required = true, ttl = IDEMPOTENT_TTL)
    @Operation(
            summary = "Place an order",
            description = "Role `customer`. The order is priced from the catalog and starts as PENDING_PAYMENT. "
                    + "Repeating a request with the same `Idempotency-Key` returns the original response "
                    + "(`Idempotent-Replayed: true`).",
            parameters =
                    @Parameter(
                            name = IDEMPOTENCY_KEY,
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Unique per request, 1-255 printable ASCII characters (a UUID is fine)",
                            schema = @Schema(type = "string"),
                            example = "6f1c2c1e-3a5d-4e55-9b1d-0f7a3c1d2e11"))
    @ApiResponse(
            responseCode = "201",
            description = "Order placed",
            headers =
                    @io.swagger.v3.oas.annotations.headers.Header(
                            name = "Location",
                            description = "URL of the new order"))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request or missing Idempotency-Key",
            content =
                    @Content(
                            mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"type\":\"urn:problem-type:validation-failed\",\"title\":\"Bad Request\",\"status\":400,\"detail\":\"Request validation failed\",\"instance\":\"/api/v1/orders\",\"errors\":[{\"field\":\"items[0].quantity\",\"message\":\"must be less than or equal to 10\"}]}")))
    @ApiResponse(
            responseCode = "422",
            description = "Unknown or discontinued SKU, or the Idempotency-Key was used with a different request",
            content =
                    @Content(
                            mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"type\":\"urn:problem-type:product-not-available\",\"title\":\"Unprocessable Content\",\"status\":422,\"detail\":\"Products not available: DISCONTINUED-MOUSE\",\"instance\":\"/api/v1/orders\",\"skus\":[\"DISCONTINUED-MOUSE\"]}")))
    ResponseEntity<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
        OrderResponse order = OrderResponse.of(
                placeOrder.place(request.toCommand(Callers.from(authentication).subject())));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(order.id())
                .toUri();
        return ResponseEntity.created(location).body(order);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get an order",
            description =
                    "Roles `customer` (own orders only) and `admin` (any order). Another customer's order is reported as 404.")
    @ApiResponse(
            responseCode = "404",
            description = "No such order, or it belongs to someone else",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    OrderResponse get(@PathVariable UUID id, Authentication authentication) {
        return OrderResponse.of(getOrder.get(id, Callers.from(authentication)));
    }

    @GetMapping
    @Operation(
            summary = "List orders",
            description = "Newest first. A customer gets their own orders, an admin gets all of them.")
    PageResponse<OrderSummaryResponse> list(
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, at most 100")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(ListOrdersService.MAX_PAGE_SIZE)
                    int size,
            Authentication authentication) {
        OrderPage result = listOrders.list(Callers.from(authentication), page, size);
        return new PageResponse<>(
                result.content().stream().map(OrderSummaryResponse::of).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
    }

    @PostMapping("/{id}/cancel")
    @Idempotent(required = true, ttl = IDEMPOTENT_TTL)
    @Operation(
            summary = "Cancel an order",
            description = "Role `customer`, own orders only, and only while the order is PENDING_PAYMENT.",
            parameters =
                    @Parameter(
                            name = IDEMPOTENCY_KEY,
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Unique per request, 1-255 printable ASCII characters",
                            schema = @Schema(type = "string"),
                            example = "0b8e3f0a-6a46-4c2b-8f55-6d8c3c9e7a10"))
    @ApiResponse(
            responseCode = "404",
            description = "No such order, or it belongs to someone else",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The order is not pending payment",
            content =
                    @Content(
                            mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"type\":\"urn:problem-type:order-state-conflict\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"Order 0199e0a0-6666-7000-8000-000000000006 is PAID and cannot be cancelled\",\"instance\":\"/api/v1/orders/0199e0a0-6666-7000-8000-000000000006/cancel\",\"currentStatus\":\"PAID\"}")))
    OrderResponse cancel(@PathVariable UUID id, Authentication authentication) {
        return OrderResponse.of(cancelOrder.cancel(id, Callers.from(authentication)));
    }

    @PostMapping("/{id}/refund")
    @Idempotent(required = true, ttl = IDEMPOTENT_TTL)
    @Operation(
            summary = "Refund an order",
            description =
                    "Role `admin`. Requests a full refund of an order that is PAID, or retries one that is REFUND_FAILED. "
                            + "The order becomes REFUND_REQUESTED; the money moves asynchronously.",
            parameters =
                    @Parameter(
                            name = IDEMPOTENCY_KEY,
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Unique per request, 1-255 printable ASCII characters",
                            schema = @Schema(type = "string"),
                            example = "9d2c8a54-0f4e-4a67-b3e1-5c1f6a7d8b22"))
    @ApiResponse(responseCode = "202", description = "Refund requested")
    @ApiResponse(
            responseCode = "404",
            description = "No such order",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The order is neither PAID nor REFUND_FAILED",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<OrderResponse> refund(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.accepted().body(OrderResponse.of(refundOrder.refund(id, Callers.from(authentication))));
    }
}

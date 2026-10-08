package com.altronixsoft.opp.order.adapter.in.web;

import com.altronixsoft.opp.order.domain.Product;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A product that can be ordered")
public record ProductResponse(
        @Schema(example = "MUG-JAVA") String sku,
        @Schema(example = "Coffee mug \"Java\"") String name,
        MoneyResponse price) {

    static ProductResponse of(Product product) {
        return new ProductResponse(product.sku(), product.name(), MoneyResponse.of(product.price()));
    }
}

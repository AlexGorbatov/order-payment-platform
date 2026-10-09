package com.altronixsoft.opp.order.adapter.in.web;

import com.altronixsoft.opp.order.application.ListProductsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/products", produces = "application/json")
@Tag(name = "Products")
class ProductController {

    private final ListProductsService products;

    ProductController(ListProductsService products) {
        this.products = products;
    }

    @GetMapping
    @Operation(
            summary = "List the catalog",
            description =
                    "The products that can be ordered. Prices are authoritative: an order is always priced from the catalog.")
    List<ProductResponse> list() {
        return products.list().stream().map(ProductResponse::of).toList();
    }
}

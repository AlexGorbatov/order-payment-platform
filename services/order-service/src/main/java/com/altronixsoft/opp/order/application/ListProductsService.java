package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.Product;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use case: browse the catalog. */
@Service
public class ListProductsService {

    private final ProductCatalog catalog;

    public ListProductsService(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<Product> list() {
        return catalog.findActive();
    }
}

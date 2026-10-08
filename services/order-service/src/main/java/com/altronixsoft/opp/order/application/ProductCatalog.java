package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.Product;
import java.util.Collection;
import java.util.List;

/** Port: the server-side product catalog, the only source of prices. */
public interface ProductCatalog {

    /** The products with these SKUs, active or not; unknown SKUs are simply absent from the result. */
    List<Product> findBySkus(Collection<String> skus);

    /** The products a customer can order, sorted by SKU. */
    List<Product> findActive();
}

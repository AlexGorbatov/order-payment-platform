package com.altronixsoft.opp.order.adapter.out.persistence;

import com.altronixsoft.opp.order.application.ProductCatalog;
import com.altronixsoft.opp.order.domain.Product;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@link ProductCatalog} on JPA: reads the {@code product} table. */
@Repository
@Transactional(readOnly = true)
class JpaProductCatalog implements ProductCatalog {

    private final ProductJpaRepository products;

    JpaProductCatalog(ProductJpaRepository products) {
        this.products = products;
    }

    @Override
    public List<Product> findBySkus(Collection<String> skus) {
        return products.findAllById(skus).stream().map(OrderMapper::toDomain).toList();
    }

    @Override
    public List<Product> findActive() {
        return products.findByActiveTrueOrderBySku().stream()
                .map(OrderMapper::toDomain)
                .toList();
    }
}

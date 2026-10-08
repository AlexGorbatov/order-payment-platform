package com.altronixsoft.opp.order.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductJpaRepository extends JpaRepository<ProductEntity, String> {

    List<ProductEntity> findByActiveTrueOrderBySku();
}

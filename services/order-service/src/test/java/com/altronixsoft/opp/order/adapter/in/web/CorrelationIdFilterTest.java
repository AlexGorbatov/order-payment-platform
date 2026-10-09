package com.altronixsoft.opp.order.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrelationIdFilterTest {

    @Test
    void acceptsOnlyCanonicalUuids() {
        UUID id = UUID.fromString("0199e0a0-3333-7000-8000-000000000003");

        assertThat(CorrelationIdFilter.parse(id.toString())).contains(id);
        assertThat(CorrelationIdFilter.parse(null)).isEmpty();
        assertThat(CorrelationIdFilter.parse("")).isEmpty();
        assertThat(CorrelationIdFilter.parse("not-a-uuid")).isEmpty();
        assertThat(CorrelationIdFilter.parse("1-2-3-4-5")).isEmpty();
        assertThat(CorrelationIdFilter.parse("zzzzzzzz-3333-7000-8000-000000000003"))
                .isEmpty();
    }
}

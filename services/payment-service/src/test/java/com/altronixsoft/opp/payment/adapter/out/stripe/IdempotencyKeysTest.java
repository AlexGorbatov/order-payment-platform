package com.altronixsoft.opp.payment.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    private static final UUID ID = UUID.fromString("0199e0a0-1111-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0199e0a0-2222-7000-8000-000000000002");

    @Test
    void keysFollowArchitectureSection7_3() {
        assertThat(IdempotencyKeys.paymentIntentCreate(ID)).isEqualTo("pi-create:0199e0a0-1111-7000-8000-000000000001");
        assertThat(IdempotencyKeys.paymentIntentCancel(ID)).isEqualTo("pi-cancel:0199e0a0-1111-7000-8000-000000000001");
        assertThat(IdempotencyKeys.refund(ID)).isEqualTo("refund:0199e0a0-1111-7000-8000-000000000001");
        assertThat(IdempotencyKeys.paymentIntentConfirm(ID, OTHER))
                .isEqualTo("pi-confirm:0199e0a0-1111-7000-8000-000000000001:0199e0a0-2222-7000-8000-000000000002");
    }

    @Test
    void theSameInputGivesTheSameKeyAndDifferentOperationsGiveDifferentKeys() {
        assertThat(IdempotencyKeys.paymentIntentCreate(ID)).isEqualTo(IdempotencyKeys.paymentIntentCreate(ID));
        assertThat(IdempotencyKeys.paymentIntentCreate(ID)).isNotEqualTo(IdempotencyKeys.paymentIntentCreate(OTHER));
        assertThat(IdempotencyKeys.paymentIntentCreate(ID))
                .isNotEqualTo(IdempotencyKeys.paymentIntentCancel(ID))
                .isNotEqualTo(IdempotencyKeys.refund(ID));
    }

    @Test
    void keysFitStripesLimitOf255Characters() {
        assertThat(IdempotencyKeys.paymentIntentConfirm(ID, OTHER).length()).isLessThanOrEqualTo(255);
    }
}

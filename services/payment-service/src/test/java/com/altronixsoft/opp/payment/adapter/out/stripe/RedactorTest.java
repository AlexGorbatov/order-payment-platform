package com.altronixsoft.opp.payment.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RedactorTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Invalid API Key provided: sk_test_FAKEKEYVALUE1234 | Invalid API Key provided: sk_test_***",
                "key sk_live_abcDEF123_456 leaked | key sk_live_*** leaked",
                "restricted rk_test_abc123 here | restricted rk_test_*** here",
                "publishable pk_test_abc123 | publishable pk_test_***",
                "secret whsec_abc123DEF is set | secret whsec_*** is set",
                "client pi_3PabcDEF123_secret_XyZ789abc done | client pi_3PabcDEF123_secret_*** done",
                "setup seti_1Pabc_secret_Q1w2E3 done | setup seti_1Pabc_secret_*** done",
                "nothing secret here | nothing secret here",
            })
    void removesSecretsAndKeepsTheRest(String input, String expected) {
        assertThat(Redactor.redact(input)).isEqualTo(expected);
    }

    @Test
    void redactsEveryOccurrence() {
        assertThat(Redactor.redact("sk_test_aaa and sk_test_bbb and pi_1_secret_ccc"))
                .isEqualTo("sk_test_*** and sk_test_*** and pi_1_secret_***");
    }

    @Test
    void handlesNullAndCapsTheLength() {
        assertThat(Redactor.redact(null)).isNull();
        assertThat(Redactor.redact("x".repeat(2000))).hasSize(500);
    }
}

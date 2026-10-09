package com.altronixsoft.opp.payment.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LiveModeGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"sk_test_abc123", "sk_test_51Nabc_DEF_456", "rk_test_restricted789", "sk_test_replace_me"})
    void acceptsTestModeSecretKeys(String key) {
        assertThatCode(() -> LiveModeGuard.verify(key)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sk_live_abcdef123456", "rk_live_abcdef123456", "sk_live_", "sk_live_51Nabc"})
    void refusesLiveKeys(String key) {
        assertThatThrownBy(() -> LiveModeGuard.verify(key))
                .isInstanceOfSatisfying(InvalidStripeKeyException.class, e -> {
                    assertThat(e.reason()).isEqualTo(InvalidStripeKeyException.Reason.NOT_A_TEST_KEY);
                    assertThat(e.shownPrefix()).isEqualTo(key.substring(0, 8));
                })
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("sk_test_")
                .hasMessageContaining(key.substring(0, 8));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "pk_test_publishable",
                "pk_live_publishable",
                "whsec_abcdef",
                "SK_TEST_UPPERCASE",
                "sk_test",
                "sk_test_",
                " sk_test_leading_space",
                "sk_test_has space",
                "xk_test_abc",
                "Bearer sk_test_abc",
                "not a key at all"
            })
    void refusesAnythingElseThatIsNotATestSecretKey(String key) {
        assertThatThrownBy(() -> LiveModeGuard.verify(key)).isInstanceOf(InvalidStripeKeyException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void refusesAMissingKeyAndSaysHowToSetOne(String key) {
        assertThatThrownBy(() -> LiveModeGuard.verify(key))
                .isInstanceOfSatisfying(
                        InvalidStripeKeyException.class,
                        e -> assertThat(e.reason()).isEqualTo(InvalidStripeKeyException.Reason.MISSING))
                .hasMessageContaining("STRIPE_API_KEY");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"sk_live_SUPERSECRETVALUE123", "rk_live_SUPERSECRETVALUE123", "not-a-key-SUPERSECRETVALUE123"})
    void neverRevealsMoreThanThePrefixOfARefusedKey(String key) {
        assertThatThrownBy(() -> LiveModeGuard.verify(key))
                .hasMessageNotContaining("SUPERSECRETVALUE123")
                .satisfies(e -> assertThat(((InvalidStripeKeyException) e).shownPrefix())
                        .satisfiesAnyOf(
                                prefix -> assertThat(prefix).isNull(),
                                prefix -> assertThat(prefix).hasSize(8)));
    }

    @Test
    void theFailureAnalyzerExplainsALiveKeyWithoutRepeatingIt() {
        InvalidStripeKeyException failure = captured("sk_live_SUPERSECRETVALUE123");

        var analysis = new LiveModeFailureAnalyzer().analyze(failure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("sk_live_").doesNotContain("SUPERSECRETVALUE123");
        assertThat(analysis.getAction())
                .contains("sk_test_")
                .contains("rk_test_")
                .contains("never accepted");
    }

    @Test
    void theFailureAnalyzerExplainsAMissingKey() {
        var analysis = new LiveModeFailureAnalyzer().analyze(captured(null));

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("STRIPE_API_KEY");
        assertThat(analysis.getAction()).contains(".env.example");
    }

    @Test
    void theAnalyzerIsRegisteredWithSpringBoot() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("META-INF/spring.factories")) {
            assertThat(new String(in.readAllBytes()))
                    .contains("org.springframework.boot.diagnostics.FailureAnalyzer")
                    .contains(LiveModeFailureAnalyzer.class.getName());
        }
    }

    private static InvalidStripeKeyException captured(String key) {
        try {
            LiveModeGuard.verify(key);
            throw new AssertionError("expected a refusal");
        } catch (InvalidStripeKeyException e) {
            return e;
        }
    }
}

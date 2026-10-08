package com.altronixsoft.opp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class IdempotencyRulesTest {

    @ParameterizedTest
    @CsvSource({
        "200,true",
        "201,true",
        "204,true",
        "299,true",
        "100,false",
        "301,false",
        "304,false",
        "400,true",
        "401,true",
        "403,true",
        "404,true",
        "409,false",
        "422,true",
        "429,false",
        "499,true",
        "500,false",
        "502,false",
        "503,false"
    })
    void onlyDeterministicAnswersAreStored(int status, boolean stored) {
        assertThat(IdempotencyInterceptor.isStorable(status)).isEqualTo(stored);
    }

    @Idempotent(ttl = "PT36H")
    void annotated() {}

    @Idempotent(required = false, ttl = "${test.ttl}")
    void withPlaceholder() {}

    @Idempotent(ttl = "tomorrow")
    void notADuration() {}

    @Idempotent(ttl = "PT0S")
    void zero() {}

    @Idempotent
    void defaults() {}

    private static Idempotent annotationOf(String method) throws NoSuchMethodException {
        Method m = IdempotencyRulesTest.class.getDeclaredMethod(method);
        return m.getAnnotation(Idempotent.class);
    }

    @Test
    void defaultsAreRequiredAndTwentyFourHours() throws Exception {
        IdempotencySettings settings = IdempotencySettings.of(annotationOf("defaults"), new MockEnvironment());

        assertThat(settings.required()).isTrue();
        assertThat(settings.ttl()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void isoDurationsAreParsed() throws Exception {
        assertThat(IdempotencySettings.of(annotationOf("annotated"), new MockEnvironment())
                        .ttl())
                .isEqualTo(Duration.ofHours(36));
    }

    @Test
    void placeholdersAreResolved() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("test.ttl", "PT2H");

        IdempotencySettings settings = IdempotencySettings.of(annotationOf("withPlaceholder"), environment);

        assertThat(settings.ttl()).isEqualTo(Duration.ofHours(2));
        assertThat(settings.required()).isFalse();
    }

    @Test
    void anUnusableTtlIsRejected() {
        assertThatThrownBy(() -> IdempotencySettings.of(annotationOf("notADuration"), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ISO-8601");
        assertThatThrownBy(() -> IdempotencySettings.of(annotationOf("zero"), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one second");
        assertThatThrownBy(() -> IdempotencySettings.of(annotationOf("withPlaceholder"), new MockEnvironment()))
                .as("unresolved placeholder")
                .isInstanceOf(IllegalStateException.class);
    }
}

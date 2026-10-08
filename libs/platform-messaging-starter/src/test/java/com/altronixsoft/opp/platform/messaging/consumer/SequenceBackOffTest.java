package com.altronixsoft.opp.platform.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

class SequenceBackOffTest {

    @Test
    void yieldsEachDelayOnceThenStops() {
        BackOffExecution execution = new SequenceBackOff(
                        List.of(Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(60)))
                .start();

        assertThat(execution.nextBackOff()).isEqualTo(1_000);
        assertThat(execution.nextBackOff()).isEqualTo(10_000);
        assertThat(execution.nextBackOff()).isEqualTo(60_000);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void everyExecutionStartsOver() {
        SequenceBackOff backOff = new SequenceBackOff(List.of(Duration.ofMillis(5)));

        BackOffExecution first = backOff.start();
        first.nextBackOff();

        assertThat(backOff.start().nextBackOff()).isEqualTo(5);
    }
}

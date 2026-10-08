package com.altronixsoft.opp.platform.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

class TraceparentHeaderTest {

    @Test
    void noTracerOrNoSpanMeansNoHeader() {
        Tracer tracer = mock(Tracer.class);

        assertThat(TraceparentHeader.current(null)).isEmpty();
        assertThat(TraceparentHeader.current(tracer)).isEmpty();
    }

    @Test
    void formatsW3cTraceparentOfTheCurrentSpan() {
        assertThat(TraceparentHeader.current(tracerWith("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", true)))
                .contains("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    void shortTraceIdsArePaddedAndUnsampledSpansAreFlaggedOff() {
        assertThat(TraceparentHeader.current(tracerWith("a3ce929d0e0e4736", "f067aa0ba902b7", false)))
                .contains("00-0000000000000000a3ce929d0e0e4736-00f067aa0ba902b7-00");
        assertThat(TraceparentHeader.current(tracerWith("a3ce929d0e0e4736", "f067aa0ba902b7", null)))
                .contains("00-0000000000000000a3ce929d0e0e4736-00f067aa0ba902b7-00");
    }

    private static Tracer tracerWith(String traceId, String spanId, Boolean sampled) {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(traceId);
        when(context.spanId()).thenReturn(spanId);
        when(context.sampled()).thenReturn(sampled);
        Span span = mock(Span.class);
        when(span.context()).thenReturn(context);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);
        return tracer;
    }
}

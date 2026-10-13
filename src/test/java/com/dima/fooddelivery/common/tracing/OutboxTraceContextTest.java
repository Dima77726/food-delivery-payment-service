package com.dima.fooddelivery.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Перенос трассы через outbox: что записали при запросе, то и поднимается при публикации.
 */
class OutboxTraceContextTest {

    private final SimpleTracer tracer = new SimpleTracer();

    @SuppressWarnings("unchecked")
    private OutboxTraceContext supportWith(Tracer tracer) {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);

        return new OutboxTraceContext(provider);
    }

    @Test
    void shouldCaptureCurrentSpanAsW3cTraceparent() {
        OutboxTraceContext support = supportWith(tracer);

        // Сэмплированный запрос: берём спан публикации как «текущий» и читаем его контекст обратно.
        try (OutboxTraceContext.Active ignored =
                     support.start("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")) {
            String captured = support.capture();

            assertEquals("00-0af7651916cd43dd8448eb211c80319c-" + tracer.currentSpan().context().spanId() + "-01", captured);
        }
    }

    @Test
    void shouldKeepUnsampledFlag() {
        OutboxTraceContext support = supportWith(tracer);

        try (OutboxTraceContext.Active ignored =
                     support.start("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00")) {
            assertEquals(true, support.capture().endsWith("-00"));
        }
    }

    @Test
    void shouldContinueStoredTraceWhenPublishing() {
        OutboxTraceContext support = supportWith(tracer);
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

        try (OutboxTraceContext.Active ignored = support.start(traceparent)) {
            Span publishing = tracer.currentSpan();

            assertEquals("0af7651916cd43dd8448eb211c80319c", publishing.context().traceId());
            assertEquals("b7ad6b7169203331", publishing.context().parentId());
        }

        assertNull(tracer.currentSpan(), "после закрытия спан публикации не должен оставаться текущим");
    }

    @Test
    void shouldCaptureNothingOutsideOfRequest() {
        assertNull(supportWith(tracer).capture());
        assertNull(supportWith(null).capture());
    }

    @Test
    void shouldPublishWithoutTraceWhenValueIsMissingOrBroken() {
        OutboxTraceContext support = supportWith(tracer);

        for (String value : new String[]{null, "", "garbage", "00-short-b7ad6b7169203331-01"}) {
            try (OutboxTraceContext.Active ignored = support.start(value)) {
                assertNull(tracer.currentSpan(), value);
            }
        }
    }
}

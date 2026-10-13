package com.dima.fooddelivery.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Переносит трассу через outbox.
 *
 * <p>Событие записывается в базу в потоке запроса, а в Kafka уходит позже, из планировщика,
 * где текущего спана нет. Без этого переноса трасса обрывается ровно на границе сервисов:
 * публикация и обработка у потребителя оказываются отдельной трассой, не связанной с запросом.
 *
 * <p>Контекст хранится строкой в формате W3C {@code traceparent} — так его можно прочитать
 * и глазами в базе, и любым инструментом. При публикации из строки поднимается спан-потомок,
 * а продюсер Kafka делает своим родителем текущий спан и сам кладёт заголовок в сообщение.
 */
@Component
public class OutboxTraceContext {

    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private final ObjectProvider<Tracer> tracers;

    public OutboxTraceContext(ObjectProvider<Tracer> tracers) {
        this.tracers = tracers;
    }

    /** Текущий контекст в виде traceparent или {@code null}, если запрос не трассируется. */
    public String capture() {
        Tracer tracer = tracers.getIfAvailable();
        Span span = tracer == null ? null : tracer.currentSpan();

        if (span == null) {
            return null;
        }

        TraceContext context = span.context();
        String flags = Boolean.FALSE.equals(context.sampled()) ? "00" : "01";

        return "00-" + context.traceId() + "-" + context.spanId() + "-" + flags;
    }

    /**
     * Открывает спан публикации как потомка сохранённого контекста. Закрывать обязательно:
     * {@code try-with-resources}. Для пустого или повреждённого значения публикация идёт как раньше.
     */
    public Active start(String traceparent) {
        Tracer tracer = tracers.getIfAvailable();

        if (tracer == null || traceparent == null) {
            return Active.NONE;
        }

        Matcher matcher = TRACEPARENT.matcher(traceparent);

        if (!matcher.matches()) {
            return Active.NONE;
        }

        TraceContext parent = tracer.traceContextBuilder()
                .traceId(matcher.group(1))
                .spanId(matcher.group(2))
                .sampled((Integer.parseInt(matcher.group(3), 16) & 1) == 1)
                .build();

        Span span = tracer.spanBuilder().setParent(parent).name("outbox publish").start();

        return new Active(span, tracer.withSpan(span));
    }

    public record Active(Span span, Tracer.SpanInScope scope) implements AutoCloseable {

        static final Active NONE = new Active(null, null);

        @Override
        public void close() {
            if (scope != null) {
                scope.close();
            }

            if (span != null) {
                span.end();
            }
        }
    }
}

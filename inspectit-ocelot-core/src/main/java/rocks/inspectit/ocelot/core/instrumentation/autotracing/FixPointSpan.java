package rocks.inspectit.ocelot.core.instrumentation.autotracing;

import io.opencensus.trace.*;

import java.util.EnumSet;
import java.util.Map;
import java.util.Random;

/**
 * A span-implemention which enver gets passed to any exporter.
 * It's sole purpose is to "reserve" a Span-ID which will later on be used for a custom span build
 * via the {@link CustomSpanBuilder}
 */
public class FixPointSpan extends Span {

    private static final Random RANDOM = new Random();

    public FixPointSpan(SpanContext parent) {
        super(generateContext(parent), EnumSet.of(Options.RECORD_EVENTS));
    }

    private static SpanContext generateContext(SpanContext parent) {
        SpanId spanId = SpanId.generateRandomId(RANDOM);
        SpanContext ctx = SpanContext.create(parent.getTraceId(), spanId, parent.getTraceOptions(), parent.getTracestate());
        return ctx;
    }

    @Override
    public void addAnnotation(String description, Map<String, AttributeValue> attributes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addAnnotation(Annotation annotation) {
        throw new UnsupportedOperationException();

    }

    @Override
    public void addLink(Link link) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void end(EndSpanOptions options) {
        throw new UnsupportedOperationException();
    }
}

package rocks.inspectit.ocelot.core.instrumentation.autotracing;

import io.opencensus.common.Clock;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.Span;
import io.opencensus.trace.SpanId;
import io.opencensus.trace.Tracing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import rocks.inspectit.ocelot.config.model.tracing.AutoTracingSettings;
import rocks.inspectit.ocelot.core.config.InspectitConfigChangedEvent;
import rocks.inspectit.ocelot.core.config.InspectitEnvironment;
import rocks.inspectit.ocelot.core.utils.HighPrecisionTimer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Component for executing stack-trace-sampling (=auto-tracing).
 */
@Component
@Slf4j
public class StackTraceSampler {

    @Autowired
    private InspectitEnvironment env;

    /**
     * Global map which stores all threads for which stack-trace sampling is currently active.
     * If a thread is present in this map, it will be sampled and the stack trace will be added to the corresponding trace.
     * This implies that {@link #startSampling()} adds the current thread to this map and {@link #finishSampling()} removes it again.
     */
    private ConcurrentHashMap<Thread, SampledTrace> activeSamplings = new ConcurrentHashMap<>();

    private HighPrecisionTimer sampleTimer;

    /**
     * The clock used for timing the stack-traces.
     * This clock msut be the same as used for OpenCensus {@link Span}s, to make sure that the timings are consistent.
     */
    private Clock clock = Tracing.getClock();


    @PostConstruct
    void init() {
        AutoTracingSettings settings = env.getCurrentConfig().getTracing().getAutoTracing();
        sampleTimer = new HighPrecisionTimer("Ocelot stack trace sampler", settings.getFrequency(),
                settings.getShutdownDelay(), this::doSample);
    }

    @EventListener(InspectitConfigChangedEvent.class)
    void updateTimer() {
        AutoTracingSettings settings = env.getCurrentConfig().getTracing().getAutoTracing();
        sampleTimer.setPeriod(settings.getFrequency());
        sampleTimer.setMaximumInactivity(settings.getShutdownDelay());
    }

    @PreDestroy
    void shutdown() {
        sampleTimer.destroy();
    }

    /**
     * Enables stack-trace-sampling for the given scope.
     * The input scope is expected to be a scope which places a trace onto the context (e.g. {@link io.opencensus.trace.Tracer#withSpan(Span)}).
     * The scope is wrapped with start / end sampling instructions and returned.
     * The span which gets active in the given scope is considered to be the root of trace derived via stack-trace-sampling
     *
     * @param spanScope the scope to perform stack trace sampling for
     * @return the wrapped scope or the unchanged spanScope if stack trace sampling could not be enabled
     */
    public AutoCloseable scoped(AutoCloseable spanScope) {
        boolean started = startSampling();
        if (started) {
            return () -> {
                finishSampling();
                spanScope.close();
            };
        } else {
            return spanScope;
        }
    }

    public AutoCloseable enterFixPoint() {
        Thread self = Thread.currentThread();
        SampledTrace activeTrace = activeSamplings.remove(self);
        if (activeTrace != null) {
            StackTrace st = StackTrace.createForCurrentThread();
            st.removeStackTop(); //the method itself should not be part of the sampled trace, as it is manually instrumented
            Span fixPointSpan = activeTrace.createFixPoint(st, clock.nowNanos());
            AutoCloseable spanScope = Tracing.getTracer().withSpan(fixPointSpan);
            return () -> {
                spanScope.close();
                activeTrace.add(st, clock.nowNanos());
                activeSamplings.put(self, activeTrace);
            };
        }
        return null;
    }

    /**
     * Starts stack-trace sampling for the current thread.
     * Sampling will only be activated, if there is an OpenCensus span on the context which also is sampled (in terms of Span-Sampling).
     * Has no effect if stack trace sampling is already enabled for this thread.
     *
     * @return true, if stack trace sampling was enabled, false otherwise
     */
    private boolean startSampling() {
        Thread self = Thread.currentThread();
        if (!activeSamplings.containsKey(self)) {
            Span root = Tracing.getTracer().getCurrentSpan();
            if (root.getContext() == null || !root.getContext().isValid() || !root.getContext().getTraceOptions().isSampled()) {
                return false;
            }
            StackTrace startStackTrace = StackTrace.createForCurrentThread();
            SampledTrace trace = new SampledTrace(root, startStackTrace, clock.nowNanos());
            activeSamplings.put(self, trace);
            sampleTimer.start();
            return true;
        }
        return false;
    }

    /**
     * Ends the stack-trace sampling for the current thread and exports all resulting spans.
     */
    private void finishSampling() {
        Thread self = Thread.currentThread();
        SampledTrace trace = activeSamplings.remove(self);
        if (trace != null) {
            //TODO: offload the trace finishing and exporting to a different Thread
            trace.end();
            SampledSpan rootInvocation = trace.getRoot();
            rootInvocation.getChildren().forEach(
                    child -> convertAndExport(rootInvocation, trace.getRootSpan(), child, new ArrayList<>())
            );
        }
    }

    /**
     * Converts a {@link SampledSpan} to a span and exports it (by calling {@link Span#end()}).
     *
     * @param parent The parent span to use for this {@link SampledSpan}
     * @param invoc  the invocation to export as span
     */
    private void convertAndExport(SampledSpan realParent, Span parent, SampledSpan invoc, List<SampledSpan> collapsedParents) {
        SpanId spanId = null;

        boolean collapseSpan = false;

        if (invoc.getFixPoint() != null) {
            spanId = invoc.getFixPoint().getContext().getSpanId();
        } else {
            if (invoc.getChildren().size() == 1) {
                SampledSpan child = invoc.getLastChild();
                boolean childHasSameTime = child.getEntryTime() == invoc.getEntryTime() && child.getExitTime() == invoc.getExitTime();
                boolean parentHasSameTime = realParent.getEntryTime() == invoc.getEntryTime() && realParent.getExitTime() == invoc.getExitTime();
                collapseSpan = childHasSameTime && parentHasSameTime;
            }
        }
        if (!collapseSpan) {
            Span span = CustomSpanBuilder.builder("*" + invoc.getSimpleName(), parent)
                    .customTiming(invoc.getEntryTime(), invoc.getExitTime(), null)
                    .spanId(spanId)
                    .startSpan();
            span.putAttribute("sampled", AttributeValue.booleanAttributeValue(true));
            span.putAttribute("fqn", AttributeValue.stringAttributeValue(invoc.getFullName()));
            if (!collapsedParents.isEmpty()) {
                String parents = collapsedParents.stream()
                        .map(sp -> sp.getFullName() + (sp.getDeclaringSourceFile() == null ? "" : " (" + sp.getDeclaringSourceFile() + ")"))
                        .collect(Collectors.joining("\n"));
                span.putAttribute("parentFrames", AttributeValue.stringAttributeValue(parents));
            }
            String source = invoc.getDeclaringSourceFile();
            if (source != null) {
                span.putAttribute("source", AttributeValue.stringAttributeValue(source));
            }
            String callOrigin = invoc.getCallOrigin();
            if (callOrigin != null) {
                span.putAttribute("calledFrom", AttributeValue.stringAttributeValue(callOrigin));
            }
            span.end();

            invoc.getChildren().forEach(grandChild -> convertAndExport(invoc, span, grandChild, new ArrayList<>()));
        } else {
            collapsedParents.add(invoc);
            invoc.getChildren().forEach(grandChild -> convertAndExport(invoc, parent, grandChild, collapsedParents));
        }
    }

    /**
     * Method invoked by a timer to sample all threads for which stack trace sampling is activated.
     * Returns true, if any sampling was performed
     */
    private boolean doSample() {
        //copy the map to avoid concurrent modifications due to startSampling() and finishSampling()
        Map<Thread, SampledTrace> samplingsCopy = new HashMap<>(activeSamplings);

        Map<Thread, StackTrace> stackTraces = StackTrace.createFor(samplingsCopy.keySet());
        long timestamp = clock.nowNanos();

        boolean anySampled = false;

        for (Thread thread : samplingsCopy.keySet()) {
            SampledTrace trace = samplingsCopy.get(thread);
            StackTrace stackTrace = stackTraces.get(thread);
            if (activeSamplings.get(thread) == trace) { //recheck for concurrent finishSampling() calls
                anySampled = true;
                trace.add(stackTrace, timestamp); //has no effect if the trace was finished concurrently
            }
        }

        return anySampled;
    }

}

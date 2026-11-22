package org.fuin.porelo.planner.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;

/**
 * Logs each bean that took {@link #SLOW_MILLIS} or more to make while the planner starts, so a
 * start that is slow on one machine names where its time went. A bean's time includes the beans it
 * needs, so a slow bean is logged with those that wait on it; the first one logged is the slow
 * step.
 */
final class StartSteps implements InstantiationAwareBeanPostProcessor {

    /** How long a part may take to be made before its time is logged. */
    static final long SLOW_MILLIS = 100;

    private static final Logger LOG = LoggerFactory.getLogger(StartSteps.class);

    private final LongSupplier nanos;

    // Keyed by name: the planner makes only singletons, each once, while it starts.
    private final Map<String, Long> begun = new ConcurrentHashMap<>();

    /**
     * Times parts against a clock of nanoseconds.
     *
     * @param nanos the clock
     */
    StartSteps(final LongSupplier nanos) {
        this.nanos = nanos;
    }

    @Override
    public @Nullable Object postProcessBeforeInstantiation(final Class<?> type, final String name) {
        begun.putIfAbsent(name, nanos.getAsLong());
        return null;
    }

    @Override
    public Object postProcessAfterInitialization(final Object bean, final String name) {
        final Long start = begun.remove(name);
        if (start != null) {
            final long millis = TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong() - start);
            if (millis >= SLOW_MILLIS) {
                LOG.info("Start step {} took {} ms", name, millis);
            }
        }
        return bean;
    }
}

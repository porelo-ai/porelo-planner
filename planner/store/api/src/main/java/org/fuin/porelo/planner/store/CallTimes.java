package org.fuin.porelo.planner.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Where the time of one call to the planner went: the waits for its locks, the phases of a write
 * and each call to the forge with its answer's status and duration, said in one line when it ends.
 *
 * <p>A call opens it on its own thread; whatever the call runs on that thread adds to it, and code
 * run outside any call adds nothing. Only names the code chooses and numbers are kept, so the line
 * holds no token, no item text and no local path.
 */
public final class CallTimes {

    private static final ThreadLocal<@Nullable CallTimes> OPEN = new ThreadLocal<>();

    /** The parts of one line beyond which further forge calls are only counted. */
    static final int MOST_CALLS = 40;

    private final String call;
    private final long started = System.nanoTime();
    private final List<String> locks = new ArrayList<>();
    private final List<String> phases = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private int callsLeftOut;
    private boolean forge;
    private boolean phased;

    private CallTimes(final String call) {
        this.call = call;
    }

    /**
     * Opens the times of a call on this thread, or answers null when one is open already, so a call
     * made inside another adds to the outer one.
     *
     * @param call what the call is, a name the code chose
     * @return the times opened, which the caller ends with {@link #end()}, or null
     */
    public static @Nullable CallTimes open(final String call) {
        if (OPEN.get() != null) {
            return null;
        }
        final CallTimes times = new CallTimes(call);
        OPEN.set(times);
        return times;
    }

    /**
     * Notes how long a lock was waited for, when a call is open on this thread.
     *
     * @param lock which lock, a name the code chose
     * @param nanos the wait in nanoseconds
     */
    public static void waited(final String lock, final long nanos) {
        final CallTimes times = OPEN.get();
        if (times != null) {
            times.locks.add(lock + " " + millis(nanos));
        }
    }

    /**
     * Runs one phase of a write and notes its duration, when a call is open on this thread; the
     * phase's failure passes on unchanged.
     *
     * @param phase which phase, a name the code chose
     * @param work the phase
     * @param <T> what the phase answers
     * @return what the phase answered
     */
    public static <T extends @Nullable Object> T phase(final String phase, final Supplier<T> work) {
        final CallTimes times = OPEN.get();
        if (times == null) {
            return work.get();
        }
        final long start = System.nanoTime();
        boolean ended = false;
        try {
            final T answer = work.get();
            ended = true;
            return answer;
        } finally {
            times.phased = true;
            times.phases.add(
                    phase + " " + millis(System.nanoTime() - start) + (ended ? "" : " failed"));
        }
    }

    /**
     * Notes one call to the forge, when a call is open on this thread.
     *
     * @param what the forge client's method that made it
     * @param status the answer's HTTP status, or 0 when none came
     * @param nanos how long it took, in nanoseconds
     */
    public static void forge(final String what, final int status, final long nanos) {
        final CallTimes times = OPEN.get();
        if (times == null) {
            return;
        }
        times.forge = true;
        if (times.calls.size() >= MOST_CALLS) {
            times.callsLeftOut++;
            return;
        }
        times.calls.add(what + " " + (status == 0 ? "no answer" : status) + " " + millis(nanos));
    }

    /**
     * Closes the times on this thread and answers its line, or null when the call neither ran a
     * write's phase nor called the forge, so a plain read of what is held says nothing.
     *
     * @return the line, or null
     */
    public @Nullable String end() {
        if (OPEN.get() == this) {
            OPEN.remove();
        }
        if (!forge && !phased) {
            return null;
        }
        final StringBuilder line = new StringBuilder(call);
        line.append(" took ").append(millis(System.nanoTime() - started));
        if (!locks.isEmpty()) {
            line.append("; waited for ").append(String.join(", ", locks));
        }
        if (!phases.isEmpty()) {
            line.append("; ").append(String.join(", ", phases));
        }
        line.append("; GitHub ");
        if (calls.isEmpty()) {
            line.append("not called");
        } else {
            line.append(calls.size() + callsLeftOut).append(" calls: ");
            line.append(String.join(", ", calls));
            if (callsLeftOut > 0) {
                line.append(", ").append(callsLeftOut).append(" more");
            }
        }
        return line.toString();
    }

    private static String millis(final long nanos) {
        return String.format(Locale.ROOT, "%d ms", TimeUnit.NANOSECONDS.toMillis(nanos));
    }
}

package org.fuin.porelo.planner.app;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.CommandQueue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Written;
import org.fuin.porelo.planner.store.git.LogText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs command groups from the outbox one command at a time per item and several items at once:
 * each command is begun on disk, written alone, and ended on disk, so the outbox is right after a
 * crash at any point. A group is judged once before its first write; each command is checked on its
 * own target right before its write, and one whose item moved is merged or refused for the owner
 * account. The reads after are made once per group.
 */
final class CommandRunner implements CommandQueue {

    /** How often a command the store was too busy for is tried before it waits for a later run. */
    static final int BUSY_TRIES = 3;

    private static final Logger LOG = LoggerFactory.getLogger(CommandRunner.class);

    private final CommandOutbox outbox;
    private final PlanningStore store;
    private final Map<String, Claim> claims = new HashMap<>();
    private final Object gate = new Object();
    private final Set<String> running = new HashSet<>();
    private boolean draining;
    private int active;

    /** One item's claim: its lock, fair so commands take it in the order they asked. */
    private static final class Claim {
        final ReentrantLock lock = new ReentrantLock(true);
        int users;
    }

    /**
     * Runs one project's commands.
     *
     * @param outbox the project's outbox, open
     * @param store the project's store
     */
    CommandRunner(final CommandOutbox outbox, final PlanningStore store) {
        this.outbox = outbox;
        this.store = store;
    }

    @Override
    public CommandOutcome submit(final CommandGroup group) {
        enter();
        try {
            if (!take(group.id())) {
                throw new IllegalStateException("The group " + group.id() + " runs already.");
            }
            try {
                outbox.enqueue(group);
                return run(group.id(), true);
            } finally {
                hand(group.id());
            }
        } finally {
            leave();
        }
    }

    /** Runs the groups left waiting at open, one after another, until the runner drains. */
    void resume() {
        enter();
        try {
            for (final String group : outbox.waiting()) {
                if (closing()) {
                    return;
                }
                if (!take(group)) {
                    // Resent or submitted meanwhile: the thread that took it runs it alone.
                    continue;
                }
                try {
                    run(group, false);
                } catch (final RuntimeException failed) {
                    // One group's failure never keeps the next from its run.
                    LOG.warn(
                            "Recovered group {} could not be run: {}",
                            LogText.of(group),
                            cause(failed));
                } finally {
                    hand(group);
                }
            }
        } finally {
            leave();
        }
    }

    /**
     * Runs the groups left waiting at open on one thread of their own.
     *
     * @return the thread
     */
    Thread resumeAside() {
        return Thread.ofPlatform()
                .name("planner-command-resume")
                .daemon(true)
                .start(
                        () -> {
                            try {
                                resume();
                            } catch (final RuntimeException closed) {
                                // Draining before it began, or the outbox closed: the next open
                                // runs what waits.
                                LOG.warn("The recovered groups were not run: {}", cause(closed));
                            }
                        });
    }

    /**
     * Stops the runner: refuses new groups, waits for every group under way, each claim and each
     * write already at the store, and the groups resumed at open, then closes the outbox, all
     * within the time given. A command still at the store then stays begun in the file, and the
     * next open settles it.
     *
     * @param within the most time to wait
     * @return true when every group under way ended in time
     */
    boolean drain(final Duration within) {
        final long end = System.nanoTime() + within.toNanos();
        boolean drained;
        synchronized (gate) {
            draining = true;
            try {
                while (active > 0) {
                    final long left = end - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(gate, left);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drained = active == 0;
        }
        outbox.close();
        return drained;
    }

    private void enter() {
        synchronized (gate) {
            if (draining) {
                // A wait, not a refusal: the call is answered busy and the group stays as it was.
                throw new StoreException.Busy(outbox.source().repository());
            }
            active++;
        }
    }

    private void leave() {
        synchronized (gate) {
            active--;
            gate.notifyAll();
        }
    }

    private boolean closing() {
        synchronized (gate) {
            return draining;
        }
    }

    /**
     * Sends a held group again, each command with the versions it was judged on.
     *
     * @param group the group's id
     * @return what became of it
     * @throws StoreException.Busy while the runner drains; the group stays as it was
     * @throws IllegalStateException when another thread runs the group, or the outbox holds it no
     *     longer or not held
     */
    CommandOutcome resend(final String group) {
        enter();
        try {
            if (!take(group)) {
                throw new IllegalStateException("The group " + group + " runs already.");
            }
            try {
                outbox.resend(group);
                return run(group, false);
            } finally {
                hand(group);
            }
        } finally {
            leave();
        }
    }

    /**
     * Removes a held group from the outbox, never while it runs or the runner drains.
     *
     * @param group the group's id
     * @throws StoreException.Busy while the runner drains; the group stays as it was
     * @throws IllegalStateException when the group runs, or the outbox holds it no longer or not
     *     held
     */
    void drop(final String group) {
        enter();
        try {
            if (!take(group)) {
                throw new IllegalStateException("The group " + group + " runs already.");
            }
            try {
                outbox.drop(group);
            } finally {
                hand(group);
            }
        } finally {
            leave();
        }
    }

    /** Takes a group for this thread alone; false when another thread runs it. */
    private boolean take(final String group) {
        synchronized (gate) {
            return running.add(group);
        }
    }

    private void hand(final String group) {
        synchronized (gate) {
            running.remove(group);
        }
    }

    /**
     * Tells whether a command waits for an item's claim another holds.
     *
     * @param id the item's id
     * @return true when one waits
     */
    boolean waiting(final String id) {
        synchronized (claims) {
            final Claim claim = claims.get(id);
            return claim != null && claim.lock.hasQueuedThreads();
        }
    }

    /** How many items are claimed or asked for now. */
    int claimed() {
        synchronized (claims) {
            return claims.size();
        }
    }

    /**
     * Runs a group of this source.
     *
     * @param group the group's id
     * @param answered whether its caller waits for this very answer, as for a group just queued;
     *     only then is a group refused at its judgment dropped instead of kept for the owner
     *     account
     */
    private CommandOutcome run(final String group, final boolean answered) {
        final List<CommandOutbox.Told> told = outbox.told(group);
        final List<Command> done = new ArrayList<>();
        final List<Command> left = new ArrayList<>();
        final Map<String, String> targets = new LinkedHashMap<>();
        for (final CommandOutbox.Told each : told) {
            final Command command = outbox.command(each.id());
            targets.put(command.id(), each.target());
            if (each.state() == CommandOutbox.State.DONE) {
                done.add(command);
            } else {
                left.add(command);
            }
        }
        try {
            store.judge(new CommandGroup(group, left, outbox.story(group)));
        } catch (final RuntimeException refused) {
            final Command first = left.getFirst();
            final List<Command> rest = left.subList(1, left.size());
            try {
                if (answered && outbox.fresh(group)) {
                    // Refused before anything began, and the caller is answered: nothing stays.
                    outbox.unqueue(group);
                } else {
                    // A group recovered or sent again: kept for the owner account, never dropped.
                    outbox.begin(first.id());
                    outbox.refused(first.id(), refused, false, null);
                }
            } catch (final RuntimeException unkept) {
                LOG.warn(
                        "Group {} was refused at its judgment, but the outbox could not be"
                                + " written, so it may still be sent: {}",
                        LogText.of(group),
                        cause(unkept));
                return new CommandOutcome.Held(
                        done,
                        left,
                        "The save was refused, but the outbox kept it; it may still be sent.");
            }
            return new CommandOutcome.Refused(first, refused, false, done, rest);
        }
        final Map<String, CommandOutcome.Created> created = new LinkedHashMap<>();
        final Set<String> written = new LinkedHashSet<>();
        for (int index = 0; index < left.size(); index++) {
            final Command command = left.get(index);
            final List<Command> unsent = left.subList(index, left.size());
            final List<Command> rest = left.subList(index + 1, left.size());
            final String target = java.util.Objects.requireNonNull(targets.get(command.id()));
            claim(target);
            try {
                try {
                    outbox.begin(command.id());
                } catch (final RuntimeException unbegun) {
                    // Nothing was called: the group is held for the owner account if the outbox
                    // takes it, and waits for the next open otherwise.
                    LOG.warn(
                            "Command {} of group {} could not be marked begun: {}",
                            LogText.of(command.id()),
                            LogText.of(group),
                            cause(unbegun));
                    return held(
                            group,
                            unbegun,
                            done,
                            unsent,
                            written,
                            "The outbox could not be written; the save waits for the owner"
                                    + " account.");
                }
                final Written result;
                try {
                    result = written(command);
                } catch (final StoreException.Busy busy) {
                    try {
                        outbox.requeue(command.id());
                    } catch (final RuntimeException unqueued) {
                        LOG.warn(
                                "Command {} of group {} could not wait again: {}",
                                LogText.of(command.id()),
                                LogText.of(group),
                                cause(unqueued));
                    }
                    return held(
                            group,
                            busy,
                            done,
                            unsent,
                            written,
                            "The project stayed busy; the save waits for the owner account.");
                } catch (final Error error) {
                    // Left begun on disk: the next open settles it against what GitHub holds.
                    return new CommandOutcome.Refused(command, error, true, done, rest);
                } catch (final RuntimeException failed) {
                    final boolean may = !StoreException.refusedBeforeWriting(failed);
                    // A command begun before this run may stand already: the board tells.
                    final Board board =
                            may || outbox.resumed(command.id()) ? readAfter(Set.of(target)) : null;
                    final CommandOutbox.State ended;
                    try {
                        ended = outbox.refused(command.id(), failed, may, board);
                    } catch (final RuntimeException unkept) {
                        return unkept(group, unkept, done, unsent, written);
                    }
                    if (ended == CommandOutbox.State.DONE) {
                        done.add(command);
                        written.add(target);
                        continue;
                    }
                    readAfter(written);
                    return new CommandOutcome.Refused(command, failed, may, done, rest);
                }
                switch (result) {
                    case Written.Done ok -> {
                        done.add(command);
                        written.add(target);
                        if (ok.created() != null) {
                            created.put(command.id(), ok.created());
                        }
                        try {
                            outbox.done(command.id());
                        } catch (final RuntimeException unkept) {
                            // It stands: the next open finds it begun and settles it as done.
                            if (rest.isEmpty()) {
                                LOG.warn(
                                        "Command {} of group {} stands, but could not be marked"
                                                + " done: {}",
                                        LogText.of(command.id()),
                                        LogText.of(group),
                                        cause(unkept));
                                return new CommandOutcome.Done(readAfter(written), created);
                            }
                            return unkept(group, unkept, done, rest, written);
                        }
                    }
                    case Written.Conflict conflict -> {
                        try {
                            outbox.conflict(command.id(), conflict.places());
                        } catch (final RuntimeException unkept) {
                            return unkept(group, unkept, done, unsent, written);
                        }
                        readAfter(written);
                        return new CommandOutcome.Refused(
                                command,
                                new StoreException.Changed(List.of(target)),
                                false,
                                done,
                                rest);
                    }
                }
            } finally {
                release(target);
            }
        }
        return new CommandOutcome.Done(readAfter(written), created);
    }

    /**
     * Holds the group for the owner account, as far as the outbox takes it, and answers it held.
     */
    private CommandOutcome held(
            final String group,
            final Throwable why,
            final List<Command> done,
            final List<Command> unsent,
            final Set<String> written,
            final String words) {
        try {
            outbox.hold(group, why);
        } catch (final RuntimeException unheld) {
            // The file keeps the group waiting: the next open runs it, or keeps it if refused.
            LOG.warn(
                    "Group {} could not be held for the owner account: {}",
                    LogText.of(group),
                    cause(unheld));
        }
        readAfter(written);
        return new CommandOutcome.Held(done, unsent, words);
    }

    /** The outbox could not keep how a command ended: the group may still stand or be sent. */
    private CommandOutcome unkept(
            final String group,
            final RuntimeException unkept,
            final List<Command> done,
            final List<Command> unsent,
            final Set<String> written) {
        LOG.warn(
                "The outbox could not keep how group {} went, so it may still stand or be sent: {}",
                LogText.of(group),
                cause(unkept));
        readAfter(written);
        return new CommandOutcome.Held(
                done,
                unsent,
                "The outbox could not be written; the save may still stand or be sent.");
    }

    private static String cause(final Throwable cause) {
        return LogText.of(cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    /** Writes a command, trying again a bounded number of times while the store is busy. */
    private Written written(final Command command) {
        for (int attempt = 1; ; attempt++) {
            try {
                return store.writeAlone(command);
            } catch (final StoreException.Busy busy) {
                if (attempt >= BUSY_TRIES) {
                    throw busy;
                }
            }
        }
    }

    /**
     * Reads what was written again, once; a read that fails answers no board, so the caller reads
     * again, and never turns a written group into a failure.
     */
    private @Nullable Board readAfter(final Set<String> written) {
        if (written.isEmpty()) {
            return null;
        }
        try {
            return store.readAfter(Set.copyOf(written));
        } catch (final RuntimeException unread) {
            LOG.warn("Reading the items written again failed: {}", cause(unread));
            return null;
        }
    }

    private void claim(final String id) {
        final Claim claim;
        synchronized (claims) {
            claim = claims.computeIfAbsent(id, key -> new Claim());
            claim.users++;
        }
        claim.lock.lock();
    }

    private void release(final String id) {
        synchronized (claims) {
            final Claim claim = claims.get(id);
            if (claim == null) {
                return;
            }
            claim.lock.unlock();
            if (--claim.users == 0) {
                claims.remove(id);
            }
        }
    }
}

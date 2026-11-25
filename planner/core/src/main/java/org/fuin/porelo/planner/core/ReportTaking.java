package org.fuin.porelo.planner.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.CommandQueue;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.jspecify.annotations.Nullable;

/**
 * Takes back what the planner's own tasks reported: it finds each task by the key its start was
 * given, which is the planner's offer, reads the reports of its name after the last one taken, page
 * by page, and saves a report of kind {@value TaskReport#IDEA} as a story with its issues, once per
 * task name and number. An idea its check refuses is taken and said; a report of any other kind is
 * left to the window. Porelo keeps nothing about readers: the last number taken per task name is
 * kept in the work record, so a restart takes no report twice.
 */
public final class ReportTaking {

    /**
     * The most discussions' offers followed per project; past it the oldest is dropped. The closed
     * offers of runs are kept apart from this bound while their runs hold them.
     */
    public static final int MOST_FOLLOWED = 200;

    /** The most report marks kept per project; past it the oldest is dropped. */
    public static final int MOST_MARKS = 1000;

    /** The most pages read of one task's name in one read; the next read goes on. */
    public static final int MOST_PAGES = 20;

    /** How long a discussion whose task Porelo never recorded is followed. */
    public static final Duration UNSEEN = Duration.ofDays(7);

    /** How far before the oldest offer Porelo's records are asked from, for a clock's step. */
    static final Duration MARGIN = Duration.ofMinutes(1);

    /**
     * An offer whose task's reports are read.
     *
     * @param since when it was offered or its task started, a UTC instant ending in Z
     * @param order the record's generation when it was written, the oldest the lowest
     * @param closed whether its task ended and every report was read; a run's offer stays closed
     *     while its run holds it, a discussion's is dropped
     */
    public record Followed(String since, long order, boolean closed) {

        /** Refuses a time that is no UTC instant and a negative order. */
        public Followed {
            instant(since);
            if (order < 0) {
                throw new IllegalArgumentException("a followed offer fails its check");
            }
        }
    }

    /**
     * The last report taken of a task's name.
     *
     * @param seq its number
     * @param order the record's generation when it was written, the oldest the lowest
     */
    public record Mark(long seq, long order) {

        /** Refuses a number below 1 and a negative order. */
        public Mark {
            if (seq < 1 || order < 0) {
                throw new IllegalArgumentException("a report mark fails its check");
            }
        }
    }

    /** Reads an idea from a report's text, outside input, in the planner's idea format. */
    @FunctionalInterface
    public interface IdeaText {
        /**
         * Reads it.
         *
         * @param text the report's text
         * @return the idea and the faults of its shape
         * @throws WorkRefusal {@code IdeaRefused} when the text is no idea at all
         */
        Read read(String text);
    }

    /**
     * An idea as read from a report.
     *
     * @param idea the idea
     * @param shape the faults of its shape, each by its field
     */
    public record Read(NewIdeas.Idea idea, List<String> shape) {

        /** Copies the faults. */
        public Read {
            shape = List.copyOf(shape);
        }
    }

    /**
     * What one read took, in fixed words that never quote a report.
     *
     * @param stories each story saved, as {@code <task>#<seq> <story>}
     * @param refused each idea refused and taken, with its faults
     * @param unread each task whose reports could not be read or taken, with why
     * @param notes each offer closed or dropped
     */
    public record Outcome(
            List<String> stories, List<String> refused, List<String> unread, List<String> notes) {

        /** Copies the lists. */
        public Outcome {
            stories = List.copyOf(stories);
            refused = List.copyOf(refused);
            unread = List.copyOf(unread);
            notes = List.copyOf(notes);
        }
    }

    private final PlanningStore store;
    private final CommandQueue queue;
    private final WorkRun.Record record;
    private final Clock clock;

    private final List<String> stories = new ArrayList<>();
    private final List<String> refused = new ArrayList<>();
    private final List<String> unread = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    ReportTaking(
            final PlanningStore store,
            final CommandQueue queue,
            final WorkRun.Record record,
            final Clock clock) {
        this.store = store;
        this.queue = queue;
        this.record = record;
        this.clock = clock;
    }

    /**
     * Follows a discussion's offer, so its task's reports are read once Porelo records it.
     *
     * @param session the locked record
     * @param offer the offer, a canonical lowercase UUID
     * @param now the time of the offer
     */
    static void follow(final WorkRun.Session session, final String offer, final Instant now) {
        if (!HandedIdeas.offerKey(offer)) {
            throw new IllegalArgumentException("An offer is a canonical lowercase UUID");
        }
        final WorkRun.Snapshot snapshot = session.read();
        final long next = snapshot.generation() + 1;
        final Map<String, Followed> followed = new LinkedHashMap<>(snapshot.followed());
        followed.put(offer, new Followed(now.toString(), next, false));
        // Closed run offers are held by their runs and bounded with them, never evicted here.
        while (followed.values().stream().filter(f -> !f.closed()).count() > MOST_FOLLOWED) {
            oldest(followed).ifPresent(followed::remove);
        }
        final WorkRun.Snapshot written = snapshot.withReading(next, followed, snapshot.reports());
        session.preflight(written);
        session.write(written);
    }

    /**
     * Reads and takes the reports of every task the planner started that is still followed.
     *
     * @param project the project's name
     * @param records Porelo's task records
     * @param reports Porelo's reports
     * @param ideas reads an idea from a report's text
     * @return what was taken
     * @throws TaskRecords.Unanswered when Porelo cannot answer its task records
     */
    Outcome take(
            final String project,
            final TaskRecords records,
            final TaskReports reports,
            final IdeaText ideas) {
        final WorkRun.Snapshot now = record.latest();
        final Map<String, Instant> open = open(now);
        final Set<String> runOffers = runOffers(now);
        final Map<String, WorkRun.Offer> ended = endedOffers(now);
        final Map<String, Followed> changed = new LinkedHashMap<>(now.followed());
        // A closed run's offer is dropped once no run holds it.
        changed.entrySet().removeIf(e -> e.getValue().closed() && !runOffers.contains(e.getKey()));
        if (!open.isEmpty()) {
            final Instant since =
                    open.values().stream().min(Comparator.naturalOrder()).orElseThrow();
            final List<TaskRecord> listed = records.since(project, since.minus(MARGIN));
            final Map<String, List<TaskRecord>> mine = new LinkedHashMap<>();
            for (final TaskRecord task : listed) {
                final String key = task.callerKey();
                // Only the planner's own tasks: a key that is none of its open offers is another
                // caller's, and its reports are never read.
                if (key != null && open.containsKey(key)) {
                    mine.computeIfAbsent(key, k -> new ArrayList<>()).add(task);
                }
            }
            for (final Map.Entry<String, Instant> offer : open.entrySet()) {
                final List<TaskRecord> tasks = mine.get(offer.getKey());
                if (tasks == null) {
                    final Instant limit = clock.instant().minus(UNSEEN);
                    if (!runOffers.contains(offer.getKey())) {
                        if (offer.getValue().isBefore(limit)) {
                            changed.remove(offer.getKey());
                            notes.add(
                                    "Offer "
                                            + offer.getKey()
                                            + " is no longer followed: Porelo recorded no task of"
                                            + " it in "
                                            + UNSEEN.toDays()
                                            + " days.");
                        }
                    } else if (endedBefore(ended.get(offer.getKey()), limit)) {
                        // A run's task that ended long ago and that Porelo has no record of, such
                        // as one started before records named their caller or one aged out.
                        changed.put(
                                offer.getKey(), new Followed(offer.getValue().toString(), 0, true));
                        notes.add(
                                "Offer "
                                        + offer.getKey()
                                        + " is closed: it ended more than "
                                        + UNSEEN.toDays()
                                        + " days ago and Porelo recorded no task of it.");
                    }
                    continue;
                }
                boolean drained = true;
                final Set<String> names = new LinkedHashSet<>();
                tasks.forEach(task -> names.add(task.task()));
                for (final String name : names) {
                    drained &= drain(name, reports, ideas);
                }
                if (drained && tasks.stream().allMatch(task -> task.endedAt() != null)) {
                    if (runOffers.contains(offer.getKey())) {
                        changed.put(
                                offer.getKey(), new Followed(offer.getValue().toString(), 0, true));
                    } else {
                        changed.remove(offer.getKey());
                    }
                    notes.add(
                            "Offer " + offer.getKey() + " ended and every report of it was read.");
                }
            }
        }
        if (!changed.equals(now.followed())) {
            record.locked(
                    session -> {
                        final WorkRun.Snapshot read = session.read();
                        final long next = read.generation() + 1;
                        final Map<String, Followed> written = new LinkedHashMap<>(read.followed());
                        // Only what this read changed: an offer followed meanwhile stays.
                        for (final String offer : now.followed().keySet()) {
                            if (!changed.containsKey(offer)) {
                                written.remove(offer);
                            }
                        }
                        changed.forEach(
                                (offer, followed) -> {
                                    if (!followed.equals(now.followed().get(offer))) {
                                        written.put(
                                                offer,
                                                new Followed(
                                                        followed.since(), next, followed.closed()));
                                    }
                                });
                        final WorkRun.Snapshot written1 =
                                read.withReading(next, written, read.reports());
                        session.preflight(written1);
                        session.write(written1);
                        return Boolean.TRUE;
                    });
        }
        return new Outcome(stories, refused, unread, notes);
    }

    /** The offers whose reports are read, each with the earliest time its task can have ended. */
    private static Map<String, Instant> open(final WorkRun.Snapshot snapshot) {
        final Map<String, Instant> open = new LinkedHashMap<>();
        snapshot.followed()
                .forEach(
                        (offer, followed) -> {
                            if (!followed.closed()) {
                                open.put(offer, instant(followed.since()));
                            }
                        });
        for (final WorkRun.Run run : snapshot.runs().values()) {
            for (final WorkRun.Offer offer : run.offers()) {
                final String since = offer.since();
                if (offer.task() != null
                        && since != null
                        && !snapshot.followed().containsKey(offer.id())) {
                    try {
                        open.putIfAbsent(offer.id(), instant(since));
                    } catch (final IllegalArgumentException unreadable) {
                        // An offer whose time cannot be read is not followed.
                    }
                }
            }
        }
        return open;
    }

    /** The run offers that ended, by offer. */
    private static Map<String, WorkRun.Offer> endedOffers(final WorkRun.Snapshot snapshot) {
        final Map<String, WorkRun.Offer> ended = new LinkedHashMap<>();
        snapshot.runs()
                .values()
                .forEach(
                        run ->
                                run.offers()
                                        .forEach(
                                                o -> {
                                                    if (o.ended() != null) {
                                                        ended.put(o.id(), o);
                                                    }
                                                }));
        return ended;
    }

    /** Whether an offer ended before a limit; one whose end cannot be read has not. */
    private static boolean endedBefore(final WorkRun.@Nullable Offer offer, final Instant limit) {
        final WorkRun.Ended end = offer == null ? null : offer.ended();
        if (end == null) {
            return false;
        }
        try {
            return OffsetDateTime.parse(end.at()).toInstant().isBefore(limit);
        } catch (final DateTimeParseException unreadable) {
            return false;
        }
    }

    private static Set<String> runOffers(final WorkRun.Snapshot snapshot) {
        final Set<String> offers = new LinkedHashSet<>();
        snapshot.runs().values().forEach(run -> run.offers().forEach(o -> offers.add(o.id())));
        return offers;
    }

    /**
     * Reads a task name's reports after its mark, page by page, and takes them.
     *
     * @return whether every report was read and taken
     */
    private boolean drain(final String name, final TaskReports reports, final IdeaText ideas) {
        if (!Ids.isTask(name)) {
            unread.add("A task record names no task's name; its reports are not read.");
            return false;
        }
        try {
            for (int page = 0; page < MOST_PAGES; page++) {
                final long mark = mark(record.latest(), name);
                final TaskReports.Page answered = reports.after(name, mark);
                if (!answered.reports().isEmpty() && answered.reports().getFirst().seq() <= mark) {
                    unread.add(name + ": Porelo answered a report not after " + mark + ".");
                    return false;
                }
                if (answered.reports().isEmpty() && answered.last() != mark) {
                    unread.add(
                            name
                                    + ": Porelo answered no report, yet its last is "
                                    + answered.last()
                                    + " and "
                                    + mark
                                    + " was taken.");
                    return false;
                }
                if (!answered.reports().isEmpty()) {
                    record.locked(
                            session -> {
                                taken(session, name, answered, ideas);
                                return Boolean.TRUE;
                            });
                }
                if (!answered.more()) {
                    return true;
                }
            }
            return false;
        } catch (final TaskRecords.Unanswered e) {
            unread.add(name + ": " + e.getMessage());
            return false;
        } catch (final WorkRefusal e) {
            unread.add(name + ": the record refused (" + e.name() + ").");
            return false;
        } catch (final StoreException e) {
            unread.add(name + ": the store did not save (" + e.getClass().getSimpleName() + ").");
            return false;
        } catch (final IllegalArgumentException e) {
            unread.add(name + ": " + e.getMessage());
            return false;
        }
    }

    /** Takes one page under the record's lock, each report once. */
    private void taken(
            final WorkRun.Session session,
            final String name,
            final TaskReports.Page page,
            final IdeaText ideas) {
        WorkRun.Snapshot snapshot = session.read();
        final long start = mark(snapshot, name);
        long mark = start;
        for (final TaskReport report : page.reports()) {
            if (report.seq() <= mark) {
                continue;
            }
            if (!TaskReport.IDEA.equals(report.kind())) {
                // Left to the window, which shows every report itself.
                mark = report.seq();
                continue;
            }
            if (mark != mark(snapshot, name)) {
                snapshot = marked(snapshot, name, mark);
                session.preflight(snapshot);
                session.write(snapshot);
            }
            final String key = HandedIdeas.key(name, report.seq());
            final long seq = report.seq();
            try {
                final Read read = ideas.read(report.text());
                final String story =
                        HandedIdeas.save(
                                store,
                                queue,
                                session,
                                snapshot,
                                key,
                                read.idea(),
                                read.shape(),
                                written -> marked(written, name, seq));
                stories.add(key + " " + story);
                snapshot = session.read();
                if (mark(snapshot, name) < seq) {
                    // A report saved before a restart: its story stands, its mark is written now.
                    snapshot = marked(snapshot, name, seq);
                    session.preflight(snapshot);
                    session.write(snapshot);
                }
            } catch (final WorkRefusal e) {
                if (!NewIdeas.REFUSED.equals(e.name())) {
                    throw e;
                }
                refused.add(key + ": " + e.getMessage() + " " + faults(e) + pushed(e));
                snapshot = marked(session.read(), name, seq);
                session.preflight(snapshot);
                session.write(snapshot);
            } catch (final StoreException.NotSavable e) {
                refused.add(key + ": The store cannot write the idea: " + bounded(e.getMessage()));
                snapshot = marked(session.read(), name, seq);
                session.preflight(snapshot);
                session.write(snapshot);
            }
            mark = seq;
        }
        if (mark != mark(snapshot, name)) {
            snapshot = marked(snapshot, name, mark);
            session.preflight(snapshot);
            session.write(snapshot);
        }
    }

    /** A store's words, cut to a thousand characters so one refusal never floods the log. */
    private static String bounded(final @Nullable String words) {
        final String text = String.valueOf(words);
        return text.codePointCount(0, text.length()) <= 1000
                ? text
                : text.substring(0, text.offsetByCodePoints(0, 999)) + "…";
    }

    /** The repositories a partly saved idea was pushed to, in words; none for no push. */
    private static String pushed(final WorkRefusal refusal) {
        final Object pushed = refusal.parameters().get("pushed");
        return pushed instanceof String list && !list.isEmpty()
                ? " Already pushed to: " + list + "."
                : "";
    }

    private static String faults(final WorkRefusal refusal) {
        final Object faults = refusal.parameters().get("faults");
        return faults instanceof List<?> list ? String.valueOf(list) : "[]";
    }

    private static long mark(final WorkRun.Snapshot snapshot, final String name) {
        final Mark mark = snapshot.reports().get(name);
        return mark == null ? 0 : mark.seq();
    }

    /**
     * The record with a name's mark moved on; past the bound the oldest other mark goes, never one
     * of a run offer still read.
     */
    static WorkRun.Snapshot marked(
            final WorkRun.Snapshot snapshot, final String name, final long seq) {
        final long next = snapshot.generation() + 1;
        final Map<String, Mark> marks = new LinkedHashMap<>(snapshot.reports());
        marks.put(name, new Mark(seq, next));
        final Set<String> held = heldNames(snapshot);
        while (marks.size() > MOST_MARKS) {
            final java.util.Optional<String> oldest =
                    marks.entrySet().stream()
                            .filter(e -> !e.getKey().equals(name) && !held.contains(e.getKey()))
                            .min(Comparator.comparingLong(e -> e.getValue().order()))
                            .map(Map.Entry::getKey);
            if (oldest.isEmpty()) {
                break;
            }
            marks.remove(oldest.get());
        }
        return snapshot.withReading(next, snapshot.followed(), marks);
    }

    /** The task names of run offers still read, whose marks are never dropped. */
    private static Set<String> heldNames(final WorkRun.Snapshot snapshot) {
        final Set<String> held = new LinkedHashSet<>();
        for (final WorkRun.Run run : snapshot.runs().values()) {
            for (final WorkRun.Offer offer : run.offers()) {
                final Followed followed = snapshot.followed().get(offer.id());
                if (offer.containerId() != null && (followed == null || !followed.closed())) {
                    held.add(offer.containerId());
                }
            }
        }
        return held;
    }

    private static java.util.Optional<String> oldest(final Map<String, Followed> followed) {
        return followed.entrySet().stream()
                .filter(e -> !e.getValue().closed())
                .min(Comparator.comparingLong(e -> e.getValue().order()))
                .map(Map.Entry::getKey);
    }

    private static Instant instant(final String value) {
        if (!value.endsWith("Z")) {
            throw new IllegalArgumentException("a time is no UTC instant ending in Z");
        }
        try {
            return Instant.parse(value);
        } catch (final DateTimeParseException e) {
            throw new IllegalArgumentException("a time is no UTC instant ending in Z", e);
        }
    }
}

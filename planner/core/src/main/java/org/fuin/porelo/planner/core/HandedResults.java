package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/**
 * Checks the result a stage's task handed in and keeps one outcome per offer, so a repeated hand-in
 * answers the first outcome and never saves twice.
 */
public final class HandedResults {

    /** The refusal's name the window reads. */
    public static final String REFUSED_NAME = "ResultRefused";

    /** An offer whose result is saved and whose issues moved. */
    public static final String SAVED = "SAVED";

    /** An offer whose task left no result, or ended without handing one in. */
    public static final String MISSING = "MISSING";

    /** An offer whose result was refused, with its faults. */
    public static final String REFUSED = "REFUSED";

    /** An offer whose save began and has not ended. */
    public static final String PENDING = "PENDING";

    /** The most outcomes kept; a new one drops the oldest. */
    public static final int MOST_KEPT = 500;

    /** The most faults a refusal names. */
    public static final int MOST_FAULTS = 10;

    /** The most code points of a plan. */
    public static final int MOST_PLAN = 20_000;

    /** The most code points of one line: a size's why, a missing result's reason, a fault. */
    public static final int MOST_LINE = 300;

    /** The most code points of one item of a list. */
    public static final int MOST_ITEM = 500;

    /** The most items of a list. */
    public static final int MOST_ITEMS = 20;

    private static final String NOT_A_RESULT = "The result does not follow the result format.";

    private HandedResults() {}

    /**
     * What the record keeps of an offer's hand-in: no plan text, only what a replay answers.
     *
     * @param status {@value #SAVED}, {@value #MISSING}, {@value #REFUSED} or {@value #PENDING}
     * @param task the task the offer was bound to
     * @param issues the offer's issues
     * @param faults the refusal's faults, empty unless refused
     * @param reason why the result is missing, empty unless missing
     * @param order the record's generation when it was written, the oldest the lowest
     */
    public record Handed(
            String status,
            String task,
            List<String> issues,
            List<String> faults,
            String reason,
            long order) {

        /** Copies the lists. */
        public Handed {
            issues = List.copyOf(issues);
            faults = List.copyOf(faults);
        }
    }

    /**
     * The result file as the window handed it in; a member of the wrong type arrives as an empty
     * text or zero, which its check refuses.
     *
     * @param format the format's name
     * @param kind ANALYSIS or ESTIMATE
     * @param issues one entry per issue, null for an entry that is no object
     */
    public record File(
            @Nullable String format,
            @Nullable String kind,
            @Nullable List<@Nullable Entry> issues) {

        /**
         * Keeps the entries as handed in.
         *
         * @param format the format's name
         * @param kind ANALYSIS or ESTIMATE
         * @param issues one entry per issue
         */
        // Declared in full, since a compact constructor's parameters carry no nullness that the
        // app's NullAway reads from the compiled class.
        public File(
                final @Nullable String format,
                final @Nullable String kind,
                final @Nullable List<@Nullable Entry> issues) {
            this.format = format;
            this.kind = kind;
            this.issues =
                    issues == null
                            ? null
                            : java.util.Collections.unmodifiableList(new ArrayList<>(issues));
        }
    }

    /**
     * One issue's entry of the result file.
     *
     * @param id the issue's id
     * @param plan the plan, or null when absent
     * @param size the size
     * @param sizeWhy why it has that size
     * @param estimateMinutes the minutes, or null when absent
     * @param toBeChecked what is still to be checked, or null when absent
     * @param findings what the stage found, or null when absent
     */
    public record Entry(
            @Nullable String id,
            @Nullable String plan,
            @Nullable String size,
            @Nullable String sizeWhy,
            @Nullable Long estimateMinutes,
            @Nullable List<String> toBeChecked,
            @Nullable List<String> findings) {}

    /**
     * A hand-in: the result, or why it is missing, and the faults of its shape the reader found.
     *
     * @param result the result file, or null when the hand-in says it is missing
     * @param missing why the result is missing, or null when it carries one
     * @param shape the faults of its shape, each starting with the field it names
     */
    public record HandIn(@Nullable File result, @Nullable String missing, List<String> shape) {

        /**
         * Copies the faults.
         *
         * @param result the result file
         * @param missing why it is missing
         * @param shape the faults of its shape
         */
        public HandIn(
                final @Nullable File result,
                final @Nullable String missing,
                final List<String> shape) {
            this.result = result;
            this.missing = missing;
            this.shape = List.copyOf(shape);
        }
    }

    /**
     * What a hand-in answers.
     *
     * @param outcome {@value #SAVED} or {@value #MISSING}
     * @param board the board as it stands after the hand-in
     * @param issues the offer's issues
     */
    public record Answer(String outcome, Board board, List<String> issues) {

        /** Copies the issues. */
        public Answer {
            issues = List.copyOf(issues);
        }
    }

    /**
     * Checks an offer's form: a canonical lowercase UUID, as every offer of the record is.
     *
     * @param offer the offer
     * @return the offer
     * @throws WorkRefusal {@code ResultRefused} for any other text
     */
    public static String offer(final String offer) {
        try {
            if (UUID.fromString(offer).toString().equals(offer)) {
                return offer;
            }
        } catch (final IllegalArgumentException invalid) {
            // Refused below in fixed words.
        }
        throw refused("offer: The offer must be a lowercase UUID.");
    }

    /**
     * A refusal of one fault, never recorded, for a hand-in no offer can take.
     *
     * @param fault the fault, starting with its field
     * @return the refusal
     */
    static WorkRefusal refused(final String fault) {
        return WorkRefusal.resultRefused("The hand-in is refused.", List.of(fault));
    }

    /**
     * The refusal a recorded outcome answers.
     *
     * @param faults the recorded faults
     * @return the refusal
     */
    static WorkRefusal refused(final List<String> faults) {
        return WorkRefusal.resultRefused(NOT_A_RESULT, faults);
    }

    /**
     * Checks a hand-in against the rule's kind and the offer's issues, before anything is saved.
     *
     * @param kind what the offer's rule hands back
     * @param issues the offer's issues
     * @param handIn the hand-in
     * @return the faults, at most {@value #MOST_FAULTS}, each starting with the field it names;
     *     empty when the hand-in is sound
     */
    public static List<String> check(
            final WorkRule.Result kind, final List<String> issues, final HandIn handIn) {
        final List<String> faults = new ArrayList<>(handIn.shape());
        final File file = handIn.result();
        final String missing = handIn.missing();
        if (file == null || missing != null) {
            if (file != null || missing == null) {
                faults.add("body: The body must hold either a result or why it is missing.");
            } else {
                line(missing, "missing", "why it is missing", MOST_LINE, faults);
            }
            return bounded(faults);
        }
        if (!StageOrder.FORMAT.equals(file.format())) {
            faults.add("format: The format must be " + StageOrder.FORMAT + ".");
        }
        if (!kind.name().equals(file.kind())) {
            faults.add("kind: The kind must be " + kind.name() + " for this task.");
        }
        final List<@Nullable Entry> entries = file.issues();
        if (entries == null) {
            faults.add("issues: The issues must be a list of one object per issue.");
            return bounded(faults);
        }
        final Set<String> named = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            final String at = "issues[" + i + "]";
            final Entry entry = entries.get(i);
            if (entry == null) {
                faults.add(at + ": Each issue must be an object.");
                continue;
            }
            final String id = entry.id();
            if (id == null || !issues.contains(id)) {
                faults.add(at + ".id: The id is none of the order's issues.");
            } else if (!named.add(id)) {
                faults.add(at + ".id: The issue " + id + " is named twice.");
            }
            entry(kind, repaired(entry), at, faults);
        }
        for (final String id : issues) {
            if (!named.contains(id)) {
                faults.add("issues: The result names no entry for " + id + ".");
            }
        }
        if (faults.isEmpty()) {
            final String from = "Working";
            try {
                result(kind, from, "Done", file);
            } catch (final IllegalArgumentException unsound) {
                faults.add("issues: " + bound(String.valueOf(unsound.getMessage())));
            }
        }
        return bounded(faults);
    }

    private static void entry(
            final WorkRule.Result kind,
            final Entry entry,
            final String at,
            final List<String> faults) {
        final String plan = entry.plan();
        if (plan == null) {
            if (kind == WorkRule.Result.ANALYSIS) {
                faults.add(at + ".plan: An analysis needs a plan.");
            }
        } else {
            plan(plan, at + ".plan", faults);
        }
        if (entry.size() == null || !StageResult.SIZES.contains(entry.size())) {
            faults.add(
                    at
                            + ".size: The size must be one of "
                            + String.join(", ", StageResult.SIZES)
                            + ".");
        }
        final String why = entry.sizeWhy();
        if (why == null) {
            faults.add(at + ".sizeWhy: The size needs one line why.");
        } else {
            line(why, at + ".sizeWhy", "why it has its size", MOST_LINE, faults);
        }
        final Long minutes = entry.estimateMinutes();
        if (kind == WorkRule.Result.ANALYSIS && minutes != null) {
            faults.add(at + ".estimateMinutes: An analysis gives no estimate.");
        }
        if (kind == WorkRule.Result.ESTIMATE
                && (minutes == null || minutes < 1 || minutes > StageResult.MOST_MINUTES)) {
            faults.add(
                    at
                            + ".estimateMinutes: The estimate must be whole minutes between 1 and "
                            + StageResult.MOST_MINUTES
                            + ".");
        }
        items(entry.toBeChecked(), at + ".toBeChecked", faults);
        items(entry.findings(), at + ".findings", faults);
    }

    private static void items(
            final @Nullable List<String> items, final String at, final List<String> faults) {
        if (items == null) {
            return;
        }
        if (items.size() > MOST_ITEMS) {
            faults.add(at + ": The list holds more than " + MOST_ITEMS + " items.");
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            line(items.get(i), at + "[" + i + "]", "an item", MOST_ITEM, faults);
        }
    }

    private static void line(
            final String text,
            final String at,
            final String what,
            final int most,
            final List<String> faults) {
        final int length = text.codePointCount(0, text.length());
        if (text.isBlank() || length > most) {
            faults.add(
                    at
                            + ": "
                            + capital(what)
                            + " must be one line of 1 to "
                            + most
                            + " characters.");
        } else if (text.codePoints().anyMatch(c -> forbidden(c))) {
            faults.add(at + ": " + capital(what) + " holds a control or direction character.");
        }
    }

    private static void plan(final String plan, final String at, final List<String> faults) {
        final int length = plan.codePointCount(0, plan.length());
        if (plan.isBlank() || length > MOST_PLAN) {
            faults.add(at + ": The plan must be 1 to " + MOST_PLAN + " characters.");
        } else if (plan.codePoints().anyMatch(c -> c != '\n' && forbidden(c))) {
            faults.add(
                    at + ": The plan holds a control or direction character other than a newline.");
        }
    }

    private static String capital(final String what) {
        return Character.toUpperCase(what.charAt(0)) + what.substring(1);
    }

    /**
     * Whether a code point may stand nowhere in a result: a control character, a line or paragraph
     * separator, or a format character such as a direction mark or a zero-width one.
     *
     * @param c the code point
     * @return whether it is refused
     */
    static boolean forbidden(final int c) {
        return Character.isISOControl(c)
                || c == 0x2028
                || c == 0x2029
                || Character.getType(c) == Character.FORMAT;
    }

    private static List<String> bounded(final List<String> faults) {
        return List.copyOf(faults.subList(0, Math.min(faults.size(), MOST_FAULTS)));
    }

    /**
     * Bounds a text to one line of {@value #MOST_LINE} code points without control or direction
     * characters, for words that came from elsewhere.
     *
     * @param text the text
     * @return the bounded line
     */
    static String bound(final String text) {
        final StringBuilder line = new StringBuilder();
        text.codePoints()
                .limit(MOST_LINE)
                .forEach(c -> line.appendCodePoint(forbidden(c) ? ' ' : c));
        return line.toString().strip();
    }

    /**
     * Builds what the store saves from a checked result.
     *
     * @param kind what the rule hands back
     * @param from the working state the issues stand in
     * @param to the state they move to
     * @param file the checked result file
     * @return the stage's result
     * @throws IllegalArgumentException when the store could not save it as it stands
     */
    static StageResult result(
            final WorkRule.Result kind, final String from, final String to, final File file) {
        final List<StageResult.ForIssue> issues = new ArrayList<>();
        for (final Entry entry : java.util.Objects.requireNonNull(file.issues())) {
            final Entry e = repaired(java.util.Objects.requireNonNull(entry));
            final Long minutes = e.estimateMinutes();
            issues.add(
                    new StageResult.ForIssue(
                            java.util.Objects.requireNonNull(e.id()),
                            from,
                            to,
                            lowered(e.plan()),
                            java.util.Objects.requireNonNull(e.size()),
                            java.util.Objects.requireNonNull(e.sizeWhy()),
                            minutes == null ? null : Math.toIntExact(minutes),
                            e.toBeChecked() == null ? List.of() : e.toBeChecked(),
                            e.findings() == null ? List.of() : e.findings()));
        }
        return new StageResult(kind, issues);
    }

    /**
     * Lowers a plan's first- and second-level headings outside code blocks to third-level ones,
     * since the plan stands under the issue's own Plan section. Code blocks are told apart as the
     * store's check tells them apart; a fence is never repaired, so one left open is still refused.
     *
     * @param plan the plan as handed in, or null
     * @return the plan with its headings lowered, or null
     */
    static @Nullable String lowered(final @Nullable String plan) {
        if (plan == null) {
            return null;
        }
        final String[] lines = plan.replace("\r\n", "\n").split("\n", -1);
        boolean fenced = false;
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            if (fenced ? line.equals("```") : OPENING.matcher(line).matches()) {
                fenced = !fenced;
            } else if (!fenced) {
                final java.util.regex.Matcher heading = HEADING.matcher(line.stripLeading());
                if (heading.matches()) {
                    final String text = heading.group(1) == null ? "" : heading.group(1).strip();
                    lines[i] = text.isEmpty() ? "###" : "### " + text;
                }
            }
        }
        return String.join("\n", lines);
    }

    /**
     * An entry with its list items repaired where they can be: a line break becomes a space, and an
     * item over {@value #MOST_ITEM} code points is split at its sentence ends into items within
     * that bound. An item holding a sentence over the bound stays as it is, and a list grown past
     * {@value #MOST_ITEMS} items stays so, for the check to refuse.
     *
     * @param entry the entry as handed in
     * @return the entry with its items repaired
     */
    static Entry repaired(final Entry entry) {
        return new Entry(
                entry.id(),
                entry.plan(),
                entry.size(),
                entry.sizeWhy(),
                entry.estimateMinutes(),
                repaired(entry.toBeChecked()),
                repaired(entry.findings()));
    }

    private static @Nullable List<String> repaired(final @Nullable List<String> items) {
        if (items == null) {
            return null;
        }
        final List<String> repaired = new ArrayList<>();
        for (final String item : items) {
            final String line = LINE_BREAKS.matcher(item).replaceAll(" ");
            if (line.codePointCount(0, line.length()) <= MOST_ITEM) {
                repaired.add(line);
                continue;
            }
            final List<String> parts = split(line.strip());
            repaired.addAll(parts.isEmpty() ? List.of(line) : parts);
        }
        return repaired;
    }

    /** The item's sentences packed into parts within the bound, or none when one is over it. */
    private static List<String> split(final String item) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder part = new StringBuilder();
        for (final String sentence : SENTENCE_END.split(item)) {
            final int length = sentence.codePointCount(0, sentence.length());
            if (length > MOST_ITEM) {
                return List.of();
            }
            if (!part.isEmpty() && part.codePointCount(0, part.length()) + 1 + length > MOST_ITEM) {
                parts.add(part.toString());
                part.setLength(0);
            }
            if (!part.isEmpty()) {
                part.append(' ');
            }
            part.append(sentence);
        }
        if (!part.isEmpty()) {
            parts.add(part.toString());
        }
        return parts;
    }

    /** One or more line breaks of any kind in a row. */
    private static final java.util.regex.Pattern LINE_BREAKS =
            java.util.regex.Pattern.compile("\\R+");

    /** The space after a sentence's end. */
    private static final java.util.regex.Pattern SENTENCE_END =
            java.util.regex.Pattern.compile("(?<=[.!?])\\s+");

    /** A first- or second-level heading, as the store's check refuses it, and its text. */
    private static final java.util.regex.Pattern HEADING =
            java.util.regex.Pattern.compile("#{1,2}(\\s.*)?");

    /** A code block's opening line, as the store's check takes it. */
    private static final java.util.regex.Pattern OPENING =
            java.util.regex.Pattern.compile("```[A-Za-z0-9_+.-]*");

    /**
     * One line saying why an offer's issues did not move, for the person.
     *
     * @param handed what the record keeps of the offer, or null when nothing was handed in
     * @return the line
     */
    static String why(final @Nullable Handed handed) {
        if (handed == null) {
            return "its task ended without handing in a result";
        }
        return switch (handed.status()) {
            case MISSING -> "its task left no result: " + handed.reason();
            case REFUSED ->
                    "its result was refused: "
                            + (handed.faults().isEmpty() ? "" : handed.faults().getFirst());
            case PENDING -> "the save of its result did not end";
            default -> "its result is saved";
        };
    }

    /**
     * Checks a handed result as the record keeps it.
     *
     * @param offer the offer
     * @param handed what the record keeps of it
     * @throws IllegalArgumentException when a part fails its check
     */
    public static void validate(final String offer, final Handed handed) {
        if (!UUID.fromString(offer).toString().equals(offer)
                || handed.order() < 0
                || !Set.of(SAVED, MISSING, REFUSED, PENDING).contains(handed.status())
                || handed.issues().isEmpty()
                || handed.faults().size() > MOST_FAULTS
                || REFUSED.equals(handed.status()) == handed.faults().isEmpty()
                || handed.task().isBlank()
                || handed.task().codePointCount(0, handed.task().length()) > 128
                || handed.reason().codePointCount(0, handed.reason().length()) > MOST_LINE
                || handed.faults().stream()
                        .anyMatch(
                                f ->
                                        f.isBlank()
                                                || f.codePointCount(0, f.length())
                                                        > 2 * MOST_LINE)) {
            throw new IllegalArgumentException("a handed result fails its check");
        }
        // What the record answers or logs is held to what the planner itself would have written.
        if (Stream.concat(Stream.of(handed.reason()), handed.faults().stream())
                .anyMatch(text -> text.codePoints().anyMatch(HandedResults::forbidden))) {
            throw new IllegalArgumentException("a handed result holds a hidden character");
        }
        Ids.task(handed.task());
        handed.issues().forEach(Ids::issue);
    }

    /**
     * Records an offer's outcome, dropping the oldest outcome beyond {@value #MOST_KEPT}.
     *
     * @param snapshot the record
     * @param offer the offer
     * @param status the outcome's status
     * @param task the task the offer is bound to
     * @param issues the offer's issues
     * @param faults the refusal's faults, empty unless refused
     * @param reason why the result is missing, empty unless missing
     * @return the record with the outcome
     */
    static WorkRun.Snapshot with(
            final WorkRun.Snapshot snapshot,
            final String offer,
            final String status,
            final String task,
            final List<String> issues,
            final List<String> faults,
            final String reason) {
        final Map<String, Handed> results = new LinkedHashMap<>(snapshot.results());
        results.put(
                offer, new Handed(status, task, issues, faults, reason, snapshot.generation() + 1));
        while (results.size() > MOST_KEPT) {
            results.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().order()))
                    .map(Map.Entry::getKey)
                    .ifPresent(results::remove);
        }
        return new WorkRun.Snapshot(
                snapshot.generation() + 1, snapshot.runs(), snapshot.ideas(), results);
    }
}

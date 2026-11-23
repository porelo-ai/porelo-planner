package org.fuin.porelo.planner.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What one save writes to the store: each issue's new state and worker, and each story's close,
 * reopen and priority.
 *
 * @param issues the issues' new states, by id
 * @param stories the stories moved, by id: into a closed state to close them, into an open one to
 *     reopen them, after which they follow their issues again
 * @param workers the issues' new workers, by id
 * @param priorities the stories' new priorities, by id, each one of {@link Story#PRIORITIES}
 * @param issueReasons the person's reasons kept with the issues the save closes, by id; an issue
 *     standing closed already keeps one too, so a comment that failed is written when the same move
 *     is sent again
 * @param storyReasons the person's reasons kept with the stories the save shows closed, by id
 */
public record Writes(
        Map<String, String> issues,
        Map<String, String> stories,
        Map<String, Worker> workers,
        Map<String, String> priorities,
        Map<String, Reason> issueReasons,
        Map<String, Reason> storyReasons) {

    /**
     * The person's one sentence why an item is closed, kept with it.
     *
     * @param state the closed state the item is moved to
     * @param text the sentence, trimmed
     */
    public record Reason(String state, String text) {

        /** The most code points a reason holds. */
        public static final int MOST = 280;

        /**
         * Checks the text again where it is kept, whatever checked it before.
         *
         * @throws IllegalArgumentException for a blank state or a text {@link #refusal(String)}
         *     refuses
         */
        public Reason {
            if (state.isBlank()) {
                throw new IllegalArgumentException("A reason names the state it closes into.");
            }
            final String refused = refusal(text);
            if (refused != null) {
                throw new IllegalArgumentException(refused);
            }
        }

        /**
         * Answers why a text is no reason, or null when it is one: one trimmed, non-blank sentence
         * of at most {@link #MOST} code points, holding no character that hides or turns what a
         * reader sees.
         *
         * @param text the text, as it would be kept
         * @return the words of the refusal, or null
         */
        public static @Nullable String refusal(final String text) {
            if (text.isBlank() || !text.equals(text.strip())) {
                return "A reason is one trimmed sentence.";
            }
            if (text.codePointCount(0, text.length()) > MOST) {
                return "A reason holds at most " + MOST + " characters.";
            }
            if (text.codePoints().anyMatch(Reason::unshown)) {
                return "A reason holds no control, format, line or paragraph separator, private,"
                        + " unassigned or blank-looking character.";
            }
            return null;
        }

        /**
         * Whether a character is one a reason may not hold: a control, format, surrogate,
         * unassigned or private-use character, a line or paragraph separator, or one that shows as
         * blank without being a space.
         *
         * @param c the code point
         * @return whether it is refused
         */
        public static boolean unshown(final int c) {
            return switch (Character.getType(c)) {
                case Character.CONTROL,
                        Character.FORMAT,
                        Character.SURROGATE,
                        Character.UNASSIGNED,
                        Character.PRIVATE_USE,
                        Character.LINE_SEPARATOR,
                        Character.PARAGRAPH_SEPARATOR ->
                        true;
                default -> c == 0x2800 || c == 0x3164 || c == 0x115F;
            };
        }
    }

    /**
     * Checks the ids and the priorities and copies the maps and the list, keeping their order.
     *
     * @throws IllegalArgumentException for a priority none of {@link Story#PRIORITIES}, or a reason
     *     for an issue whose state this save does not write
     */
    public Writes {
        issues.keySet().forEach(Ids::issue);
        stories.keySet().forEach(Ids::story);
        issues = Collections.unmodifiableMap(new LinkedHashMap<>(issues));
        stories = Collections.unmodifiableMap(new LinkedHashMap<>(stories));
        workers.keySet().forEach(Ids::issue);
        workers = Collections.unmodifiableMap(new LinkedHashMap<>(workers));
        priorities.forEach(
                (id, priority) -> {
                    Ids.story(id);
                    if (!Story.PRIORITIES.contains(priority)) {
                        throw new IllegalArgumentException(
                                "The priority of " + id + " is none of " + Story.PRIORITIES + ".");
                    }
                });
        priorities = Collections.unmodifiableMap(new LinkedHashMap<>(priorities));
        issueReasons.keySet().forEach(Ids::issue);
        storyReasons.keySet().forEach(Ids::story);
        issueReasons = Collections.unmodifiableMap(new LinkedHashMap<>(issueReasons));
        storyReasons = Collections.unmodifiableMap(new LinkedHashMap<>(storyReasons));
    }

    /**
     * Writes that keep no reason.
     *
     * @param issues the issues' new states, by id
     * @param stories the stories moved, by id
     * @param workers the issues' new workers, by id
     * @param priorities the stories' new priorities, by id, each one of {@link Story#PRIORITIES}
     */
    public Writes(
            final Map<String, String> issues,
            final Map<String, String> stories,
            final Map<String, Worker> workers,
            final Map<String, String> priorities) {
        this(issues, stories, workers, priorities, Map.of(), Map.of());
    }

    /**
     * Answers whether the writes keep a reason.
     *
     * @return whether any item gets one
     */
    public boolean reasons() {
        return !issueReasons.isEmpty() || !storyReasons.isEmpty();
    }

    /**
     * Writes that set no priority.
     *
     * @param issues the issues' new states, by id
     * @param stories the stories moved, by id
     * @param workers the issues' new workers, by id
     */
    public Writes(
            final Map<String, String> issues,
            final Map<String, String> stories,
            final Map<String, Worker> workers) {
        this(issues, stories, workers, Map.of());
    }

    /**
     * Writes that set no worker.
     *
     * @param issues the issues' new states, by id
     * @param stories the stories moved, by id
     */
    public Writes(final Map<String, String> issues, final Map<String, String> stories) {
        this(issues, stories, Map.of());
    }

    /**
     * Answers whether the writes change nothing.
     *
     * @return whether there is nothing to write
     */
    public boolean none() {
        return issues.isEmpty()
                && stories.isEmpty()
                && workers.isEmpty()
                && priorities.isEmpty()
                && !reasons();
    }
}

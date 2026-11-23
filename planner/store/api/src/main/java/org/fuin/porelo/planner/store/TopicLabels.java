package org.fuin.porelo.planner.store;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The topic labels every repository of a project carries, the same for every project, with their
 * colors and descriptions; an item's labels on its tracker, the topic labels the planner sets and
 * any label of the person's own, which it shows and keeps but never sets; and the default labels
 * GitHub gives a new repository, which no repository of a project keeps since an issue's type and
 * the reason it was closed say what they said.
 */
public final class TopicLabels {

    /**
     * One topic label as every repository carries it.
     *
     * @param name its name
     * @param color its color, six lower-case hexadecimal digits
     * @param description its description
     */
    public record Label(String name, String color, String description) {}

    /** The topic labels, in their order. */
    public static final List<Label> ALL =
            List.of(
                    new Label("documentation", "0075ca", "Guides, references and decisions"),
                    new Label("security", "b60205", "A guard, a secret or a trust boundary"),
                    new Label("tests", "fbca04", "Tests and what runs them"),
                    new Label("build", "5319e7", "Building, packaging, releasing and CI"),
                    new Label("dependencies", "0366d6", "A dependency moved or pinned"),
                    new Label("performance", "d4c5f9", "Time, memory or size"),
                    new Label("usability", "c2e0c6", "What a person sees, reads or types"));

    /** The topic labels' names, in their order. */
    public static final List<String> NAMES = ALL.stream().map(Label::name).toList();

    /**
     * GitHub's default labels but documentation, which is a topic label, in GitHub's order; each is
     * deleted from a repository by its name.
     */
    public static final List<String> DEFAULTS =
            List.of(
                    "bug",
                    "duplicate",
                    "enhancement",
                    "good first issue",
                    "help wanted",
                    "invalid",
                    "question",
                    "wontfix");

    /** The most labels an item carries, GitHub's own limit. */
    public static final int MOST = 100;

    /** The most characters a label's name holds, GitHub's own limit. */
    public static final int MOST_NAME = 50;

    private static final Pattern HIDDEN =
            Pattern.compile(
                    "[\\x00-\\x1F\\x7F-\\x9F\\u00AD\\u034F\\u061C\\u180E\\u200B-\\u200F\\u202A-\\u202E"
                            + "\\u2060-\\u2064\\u2066-\\u206F\\uFEFF\\u2028\\u2029\\x{E0000}-\\x{E007F}]");

    private TopicLabels() {}

    /**
     * Answers whether a text is a label's name a tracker can hold: 1 to {@value #MOST_NAME}
     * characters without a control character, a character that hides or turns text - a format,
     * zero-width or bidi character, a soft hyphen, a combining grapheme joiner, the Mongolian vowel
     * separator or a tag character - or a line or paragraph separator.
     *
     * @param name the text
     * @return whether it is a well-formed label name
     */
    public static boolean wellFormed(final String name) {
        final int length = name.codePointCount(0, name.length());
        return length >= 1 && length <= MOST_NAME && !HIDDEN.matcher(name).find();
    }

    /**
     * Checks an item's labels as a store hands them on and copies them.
     *
     * @param item the item's id, which a refusal names
     * @param labels its labels, each once
     * @return the copy
     * @throws IllegalArgumentException for more than {@value #MOST} labels, a malformed one or one
     *     named twice
     */
    public static List<String> checked(final String item, final List<String> labels) {
        final List<String> copy = List.copyOf(labels);
        if (copy.size() > MOST) {
            throw new IllegalArgumentException(item + " carries more than " + MOST + " labels");
        }
        final Set<String> seen = new HashSet<>();
        for (final String label : copy) {
            if (!wellFormed(label) || !seen.add(label)) {
                throw new IllegalArgumentException(item + " carries a malformed or doubled label");
            }
        }
        return copy;
    }

    /**
     * Checks the topic labels a write sets and copies them in the order of {@link #NAMES}.
     *
     * @param labels the topic labels asked
     * @return them in the order of {@link #NAMES}
     * @throws IllegalArgumentException for a name outside {@link #NAMES} or one named twice
     */
    public static List<String> topic(final List<String> labels) {
        final @Nullable String refused = refused(labels);
        if (refused != null) {
            throw new IllegalArgumentException("A label is no topic label or named twice");
        }
        return NAMES.stream().filter(labels::contains).toList();
    }

    /**
     * Answers the first label of a write that is no topic label or is named twice, or null when
     * every one is a topic label named once.
     *
     * @param labels the labels asked
     * @return the first such label, or null
     */
    public static @Nullable String refused(final List<String> labels) {
        final Set<String> seen = new HashSet<>();
        for (final String label : labels) {
            if (!NAMES.contains(label) || !seen.add(label)) {
                return label;
            }
        }
        return null;
    }

    /**
     * The topic labels among an item's labels, by their names in the order of {@link #NAMES}; a
     * tracker matches a label's name in any case, so {@code Tests} is the topic label {@code
     * tests}.
     *
     * @param labels the item's labels
     * @return its topic labels
     */
    public static List<String> topicOf(final List<String> labels) {
        final Set<String> folded = folded(labels);
        return NAMES.stream().filter(folded::contains).toList();
    }

    /**
     * An item's labels after a write of its topic labels: every label it carries that is no topic
     * label in any case, in its order, then the topic labels asked, in the order of {@link #NAMES};
     * no name is given twice in any case, since the tracker takes them as one.
     *
     * @param carried the labels it carries
     * @param topic the topic labels asked
     * @return its labels after the write
     */
    public static List<String> after(final List<String> carried, final List<String> topic) {
        final List<String> after = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final String label : carried) {
            final String key = fold(label);
            if (!NAMES.contains(key) && seen.add(key)) {
                after.add(label);
            }
        }
        after.addAll(topicOf(topic));
        return List.copyOf(after);
    }

    /**
     * Answers whether two lists of an item's labels name the same labels as a tracker matches them:
     * in any case and any order.
     *
     * @param one the one list
     * @param other the other list
     * @return whether they name the same labels
     */
    public static boolean same(final List<String> one, final List<String> other) {
        return folded(one).equals(folded(other));
    }

    private static String fold(final String label) {
        return label.toLowerCase(Locale.ROOT);
    }

    private static Set<String> folded(final List<String> labels) {
        final Set<String> folded = new HashSet<>();
        labels.forEach(label -> folded.add(fold(label)));
        return folded;
    }

    /**
     * Finds a topic label by its name.
     *
     * @param name the name
     * @return the label, or empty when the name is none of the topic labels
     */
    public static Optional<Label> named(final String name) {
        return ALL.stream().filter(label -> label.name().equals(name)).findFirst();
    }
}

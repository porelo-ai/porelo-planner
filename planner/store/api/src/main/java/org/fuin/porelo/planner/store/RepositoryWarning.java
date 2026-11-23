package org.fuin.porelo.planner.store;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What is wrong in one of the project's repositories itself, rather than in a story or an issue:
 * for {@link #TOPIC_LABELS}, the topic labels it lacks or carries with another color or
 * description, and GitHub's defaults it still carries. Its own labels are no part of it. It holds
 * back no item and no work.
 *
 * @param repository the repository's name
 * @param kind what is wrong; {@link #TOPIC_LABELS} is the one kind
 * @param missing the topic labels it lacks, in the topic labels' order
 * @param defaults GitHub's defaults it still carries, in GitHub's order
 * @param differing the topic labels it carries with another color or description, in the topic
 *     labels' order
 * @param correction the {@link Correction#LABELS} the store offers, or null when it offers none
 */
public record RepositoryWarning(
        String repository,
        String kind,
        List<String> missing,
        List<String> defaults,
        List<String> differing,
        @Nullable Correction correction) {

    /** The kind of a repository whose labels are not the topic labels. */
    public static final String TOPIC_LABELS = "TopicLabels";

    /**
     * Checks the repository's name, the kind and each list against the topic labels and GitHub's
     * defaults, and copies the lists.
     */
    public RepositoryWarning {
        Ids.name(repository);
        if (!TOPIC_LABELS.equals(kind)) {
            throw new IllegalArgumentException("Not a repository's warning, " + kind.length());
        }
        missing = within(missing, TopicLabels.NAMES, "missing");
        defaults = within(defaults, TopicLabels.DEFAULTS, "defaults");
        differing = within(differing, TopicLabels.NAMES, "differing");
        if (missing.stream().anyMatch(differing::contains)) {
            throw new IllegalArgumentException("A topic label is both missing and differing");
        }
        if (missing.isEmpty() && defaults.isEmpty() && differing.isEmpty()) {
            throw new IllegalArgumentException("A repository's warning names nothing wrong");
        }
        if (correction != null && !Correction.LABELS.equals(correction.kind())) {
            throw new IllegalArgumentException("A repository's labels are set right by Labels");
        }
    }

    /**
     * The warning with the correction offered for it.
     *
     * @return the warning offering {@link Correction#labels()}
     */
    public RepositoryWarning offered() {
        return new RepositoryWarning(
                repository, kind, missing, defaults, differing, Correction.labels());
    }

    /**
     * The calls that set the repository's labels right, in the order they are made: each default
     * deleted, then each missing topic label created, then each differing one updated; its own
     * labels are never named.
     *
     * @return the calls
     */
    public List<Call> calls() {
        final List<Call> calls = new ArrayList<>();
        defaults.forEach(label -> calls.add(new Call(Call.Action.DELETE, label, null, null)));
        missing.forEach(label -> calls.add(written(Call.Action.CREATE, label)));
        differing.forEach(label -> calls.add(written(Call.Action.UPDATE, label)));
        return List.copyOf(calls);
    }

    private static Call written(final Call.Action action, final String name) {
        final TopicLabels.Label label = TopicLabels.named(name).orElseThrow();
        return new Call(action, name, label.color(), label.description());
    }

    private static List<String> within(
            final List<String> names, final List<String> allowed, final String what) {
        final List<String> copied = List.copyOf(names);
        if (!allowed.containsAll(copied) || new HashSet<>(copied).size() != copied.size()) {
            throw new IllegalArgumentException("A repository's " + what + " are no such labels");
        }
        return copied;
    }

    /**
     * One call a repository's correction makes on its labels.
     *
     * @param action what it does
     * @param label the label it names
     * @param color the color it writes, null for a delete
     * @param description the description it writes, null for a delete
     */
    public record Call(
            Action action, String label, @Nullable String color, @Nullable String description) {

        /** What a call does to a label. */
        public enum Action {
            /** A default deleted. */
            DELETE("Delete"),
            /** A missing topic label created. */
            CREATE("Create"),
            /** A topic label's color and description set to the topic labels'. */
            UPDATE("Update");

            private final String spelled;

            Action(final String spelled) {
                this.spelled = spelled;
            }

            /**
             * Answers the action as the contract spells it.
             *
             * @return Delete, Create or Update
             */
            public String spelled() {
                return spelled;
            }
        }

        /** Refuses a delete that writes anything and a write without its color and description. */
        public Call {
            Objects.requireNonNull(action, "action");
            if ((action == Action.DELETE) != (color == null && description == null)) {
                throw new IllegalArgumentException("A delete writes nothing, any other both");
            }
        }
    }
}

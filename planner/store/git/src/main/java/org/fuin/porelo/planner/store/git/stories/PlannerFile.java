package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.Set;

/**
 * The planner's own settings in the planning repository, the file {@code planner.yml} beside {@code
 * project.yml}: which tracker keeps the project's stories and issues. Porelo's {@code project.yml}
 * carries no setting of the planner.
 */
public final class PlannerFile {

    /** The file, at the root of the project's own repository. */
    public static final String FILE = "planner.yml";

    /** The keys the file may name. */
    private static final Set<String> KEYS = Set.of("tracker");

    private PlannerFile() {}

    /**
     * The tracker the file names under {@code tracker}, which keeps the stories and issues instead
     * of the Git store's files.
     *
     * @param kind the tracker's kind, such as {@code github}, or {@code local} for a directory on
     *     the person's computer
     * @param owner the owner the tracker's project belongs to; empty for a local tracker
     * @param project the tracker's project within the owner; empty for a local tracker
     */
    public record Tracker(String kind, String owner, String project) {

        /** The kind of a tracker that is a directory on the person's computer. */
        public static final String LOCAL = "local";

        private static final java.util.regex.Pattern KIND =
                java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,19}");
        private static final java.util.regex.Pattern REFERENCE =
                java.util.regex.Pattern.compile(
                        "([A-Za-z0-9][A-Za-z0-9-]{0,38})/([1-9][0-9]{0,8})");

        /** Checks the kind's and the project's form. */
        public Tracker {
            if (!KIND.matcher(kind).matches()) {
                throw new IllegalArgumentException(
                        FILE + " tracker: kind must be a lower-case name");
            }
            if (LOCAL.equals(kind)) {
                if (!owner.isEmpty() || !project.isEmpty()) {
                    throw new IllegalArgumentException(
                            FILE + " tracker: a local tracker names its kind alone");
                }
            } else if (!REFERENCE.matcher(owner + "/" + project).matches()) {
                throw new IllegalArgumentException(
                        FILE + " tracker: project must be <owner>/<number>, the number from 1");
            }
        }
    }

    /**
     * Reads the tracker the file names: a mapping of exactly {@code kind} and {@code project}, the
     * latter {@code <owner>/<number>}, or of {@code kind: local} alone. A key the file does not
     * know is refused by its name, never passed over.
     *
     * @param text the file's text
     * @return the tracker, or empty when the file names none or is empty or comments alone
     * @throws IllegalArgumentException when the text is no YAML mapping, names a key the file does
     *     not know, or its tracker has another shape
     */
    public static Optional<Tracker> tracker(final String text) {
        if (text.lines().allMatch(line -> line.isBlank() || line.strip().startsWith("#"))) {
            // An empty file, or one of comments alone, holds no setting.
            return Optional.empty();
        }
        final JsonNode settings = Project.yaml(text, FILE);
        settings.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!KEYS.contains(key)) {
                                throw new IllegalArgumentException(
                                        FILE + ": unknown key '" + Central.shown(key) + "'");
                            }
                        });
        final JsonNode tracker = settings.get("tracker");
        if (tracker == null) {
            return Optional.empty();
        }
        final String where = FILE + " tracker";
        if (tracker.isObject() && Tracker.LOCAL.equals(tracker.path("kind").asText())) {
            // A local tracker names no path, so no machine's place enters the planning repository.
            if (tracker.size() != 1) {
                throw new IllegalArgumentException(
                        where + ": a local tracker names its kind alone");
            }
            return Optional.of(new Tracker(Tracker.LOCAL, "", ""));
        }
        if (!tracker.isObject()
                || tracker.size() != 2
                || !tracker.has("kind")
                || !tracker.has("project")) {
            throw new IllegalArgumentException(where + ": expected a mapping of kind and project");
        }
        final String kind = Project.string(tracker, "kind", where);
        final String reference = Project.string(tracker, "project", where);
        final int slash = reference.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException(where + ": project must be <owner>/<number>");
        }
        return Optional.of(
                new Tracker(kind, reference.substring(0, slash), reference.substring(slash + 1)));
    }
}

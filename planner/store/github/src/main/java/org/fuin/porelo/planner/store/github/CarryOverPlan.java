package org.fuin.porelo.planner.store.github;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.Story;
import org.jspecify.annotations.Nullable;

/**
 * What a Markdown project's carry-over to GitHub Issues would write: one item per open story and
 * open issue, each with its old id as its alias in {@code Id}, its fields, its GitHub parent and
 * sub-issues, its dependencies, its body with its references rewritten, and its stage comments.
 * Finished and rejected issues and stories are not carried, and a reference to one of them is left
 * as plain text and named; an open issue whose file is gone leaves no plan.
 */
public final class CarryOverPlan {

    /**
     * What the Markdown store holds of an open issue beyond the board's issue.
     *
     * @param issue the issue
     * @param path the file it was read from in its repository, which its relative links start from
     * @param priority its priority, one of {@link Story#PRIORITIES}
     * @param rank its rank within its priority
     * @param covers the line of what it covers
     * @param below its text below its heading and head
     * @param stages its stages, oldest first, without a person's moves
     * @param moves how many of a person's moves were left out
     * @param estimate the minutes its last estimate gave, or null
     */
    public record IssueSource(
            Issue issue,
            String path,
            String priority,
            int rank,
            String covers,
            String below,
            List<StageEntry> stages,
            int moves,
            @Nullable Double estimate) {

        /** Copies the stages. */
        public IssueSource {
            stages = List.copyOf(stages);
        }
    }

    /**
     * Gives the tracker reference of a carried item. In a dry run GitHub's number is not known yet,
     * so the reference names the alias the item will be found by.
     */
    @FunctionalInterface
    public interface References {

        /** The reference by alias, {@code <repository>#<id>}, for a dry run. */
        References BY_ALIAS = (repository, id) -> repository + "#" + id;

        /**
         * Answers the reference of a carried item.
         *
         * @param repository its repository
         * @param id its old id, its alias
         * @return its reference
         */
        String reference(String repository, String id);
    }

    /**
     * One story or issue as it would be written.
     *
     * @param id its old id, written as its alias in {@code Id}
     * @param repository its repository
     * @param story whether it is a story
     * @param title its title
     * @param fields the project's field values, by field name
     * @param parent the reference of its GitHub parent, or null
     * @param subIssues the references of its sub-issues, in order
     * @param alsoNeeds a story's references of issues another story holds as sub-issues
     * @param blockedBy the references of the issues it is blocked by
     * @param body its body
     * @param comments its stage comments, oldest first
     * @param moves how many of a person's moves were left out of its comments
     * @param rewritten each reference of its text rewritten, {@code <id> -> <reference>}
     * @param leftAsText each id of its text left as plain text, since nothing is carried for it
     * @param notCarried the ids of its issues, parent or blockers that are not carried
     * @param problem why it cannot be written, or null when it can
     */
    public record Item(
            String id,
            String repository,
            boolean story,
            String title,
            Map<String, String> fields,
            @Nullable String parent,
            List<String> subIssues,
            List<String> alsoNeeds,
            List<String> blockedBy,
            String body,
            List<String> comments,
            int moves,
            List<String> rewritten,
            List<String> leftAsText,
            List<String> notCarried,
            @Nullable String problem) {

        /** Copies the map and the lists. */
        public Item {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            subIssues = List.copyOf(subIssues);
            alsoNeeds = List.copyOf(alsoNeeds);
            blockedBy = List.copyOf(blockedBy);
            comments = List.copyOf(comments);
            rewritten = List.copyOf(rewritten);
            leftAsText = List.copyOf(leftAsText);
            notCarried = List.copyOf(notCarried);
        }

        /**
         * Answers the dry run's line: never a body, a comment or any other text a person wrote but
         * the title.
         *
         * @param byAlias whether its references name aliases, as in a dry run
         * @return the line
         */
        public String line(final boolean byAlias) {
            final StringBuilder line = new StringBuilder();
            line.append(id)
                    .append("  ")
                    .append(repository)
                    .append("  ")
                    .append(story ? "story" : "issue")
                    .append("  ");
            final List<String> values = new ArrayList<>();
            fields.forEach((name, value) -> values.add(name + "=" + value));
            line.append(String.join("; ", values));
            line.append("  | parent: ").append(parent == null ? "-" : parent);
            if (story || !subIssues.isEmpty()) {
                line.append("  | sub-issues: ").append(listed(subIssues));
            }
            if (story) {
                line.append("  | also needs: ").append(listed(alsoNeeds));
            } else {
                line.append("  | blocked by: ").append(listed(blockedBy));
                line.append("  | stage comments: ")
                        .append(comments.size())
                        .append(", moves left out: ")
                        .append(moves);
            }
            line.append("  | not carried: ").append(listed(notCarried));
            line.append("  | references rewritten")
                    .append(byAlias ? " (by alias; GitHub's numbers come with the real run)" : "")
                    .append(": ")
                    .append(listed(rewritten));
            line.append("  | left as text: ").append(listed(leftAsText));
            line.append("  | \"").append(title).append('"');
            if (problem != null) {
                line.append("  | CANNOT BE CARRIED: ").append(problem);
            }
            return line.toString();
        }

        private static String listed(final List<String> values) {
            return values.isEmpty() ? "-" : String.join(", ", values);
        }
    }

    private final List<Item> items;

    private CarryOverPlan(final List<Item> items) {
        this.items = List.copyOf(items);
    }

    /**
     * Answers the plan's items: the stories first, then the issues, each in the board's order.
     *
     * @return the items
     */
    public List<Item> items() {
        return items;
    }

    /**
     * Answers whether every item can be written.
     *
     * @return whether no item has a problem
     */
    public boolean whole() {
        return items.stream().allMatch(item -> item.problem() == null);
    }

    /**
     * Answers the issues a carry-over takes: those open, whose file is there.
     *
     * @param board the Markdown project's board
     * @return the issues, in the board's order
     */
    public static List<Issue> carried(final Board board) {
        return board.issues().stream()
                .filter(issue -> !board.workflow().closed().contains(issue.state()))
                .filter(issue -> !issue.text().isEmpty())
                .toList();
    }

    /**
     * Plans the carry-over.
     *
     * @param board the Markdown project's board, read whole
     * @param sources what the store holds of each issue {@link #carried(Board)} answers, by id
     * @param texts what a person reads of each open story, by id, without its generated part
     * @param webs each repository's web address by its name, which a link to its index starts with
     * @param references gives the tracker reference of a carried item
     * @return the plan
     * @throws IllegalArgumentException when the board left something out, an open issue has no
     *     file, or a source or a story's text is missing
     */
    public static CarryOverPlan of(
            final Board board,
            final Map<String, IssueSource> sources,
            final Map<String, String> texts,
            final Map<String, String> webs,
            final References references) {
        if (!board.faults().isEmpty()) {
            throw new IllegalArgumentException(
                    "The board left " + board.faults().size() + " thing(s) out");
        }
        final Set<String> closed = Set.copyOf(board.workflow().closed());
        final List<String> unfiled =
                board.issues().stream()
                        .filter(issue -> !closed.contains(issue.state()))
                        .filter(issue -> issue.text().isEmpty())
                        .map(
                                issue ->
                                        issue.id()
                                                + " stands in "
                                                + issue.state()
                                                + " but has no file")
                        .toList();
        if (!unfiled.isEmpty()) {
            // An open issue only an index row names would be left out of the carry-over unseen.
            throw new IllegalArgumentException(String.join("; ", unfiled));
        }
        final String own = board.repositories().getFirst();
        final Map<String, Issue> issues = new LinkedHashMap<>();
        for (final Issue issue : carried(board)) {
            if (!sources.containsKey(issue.id())) {
                throw new IllegalArgumentException(issue.id() + " was not read");
            }
            issues.put(issue.id(), issue);
        }
        final List<Story> stories =
                board.stories().stream().filter(story -> !closed.contains(story.state())).toList();
        final Map<String, String> repositoryOf = new HashMap<>();
        stories.forEach(story -> repositoryOf.put(story.id(), own));
        issues.values().forEach(issue -> repositoryOf.put(issue.id(), issue.repository()));
        final ReferenceRewrite.Carried carried =
                (repository, id) ->
                        repository.equals(repositoryOf.get(id))
                                ? Optional.of(references.reference(repository, id))
                                : Optional.empty();

        // Each issue has one GitHub parent: its Porelo parent, else the first story that names it.
        final Map<String, String> parentOf = new HashMap<>();
        final Map<String, List<String>> subIssues = new HashMap<>();
        for (final Issue issue : issues.values()) {
            final String parent = issue.parent();
            if (parent != null && issues.containsKey(parent)) {
                parentOf.put(issue.id(), ref(references, issues.get(parent)));
                subIssues
                        .computeIfAbsent(parent, key -> new ArrayList<>())
                        .add(ref(references, issue));
            }
        }
        final Set<String> claimed = new HashSet<>();
        final List<Item> items = new ArrayList<>();
        final Map<String, Integer> ranks = new HashMap<>();
        for (final Story story : stories) {
            final List<String> subs = new ArrayList<>();
            final List<String> also = new ArrayList<>();
            final List<String> notCarried = new ArrayList<>();
            for (final String id : story.issues()) {
                final Issue issue = issues.get(id);
                if (issue == null) {
                    notCarried.add(id);
                } else if (issue.parent() == null && claimed.add(id)) {
                    subs.add(ref(references, issue));
                    parentOf.put(id, references.reference(own, story.id()));
                } else {
                    also.add(ref(references, issue));
                }
            }
            final int rank = ranks.merge(story.priority(), 1, Integer::sum);
            final String written = texts.get(story.id());
            if (written == null) {
                throw new IllegalArgumentException(story.id() + " was not read");
            }
            final ReferenceRewrite.Rewritten text =
                    ReferenceRewrite.rewrite(written, own, "stories", webs, carried);
            String body = text.text();
            final boolean ownSection =
                    bodyProblem(body) == null
                            && BodySections.read(body).section(IssueMapping.ALSO_NEEDS).isPresent();
            if (!also.isEmpty()) {
                final StringBuilder section =
                        new StringBuilder("\n\n## ").append(IssueMapping.ALSO_NEEDS).append("\n\n");
                also.forEach(reference -> section.append("- ").append(reference).append('\n'));
                body = body.strip() + section;
            }
            items.add(
                    new Item(
                            story.id(),
                            own,
                            true,
                            story.title(),
                            IssueMapping.fields(story, rank),
                            null,
                            subs,
                            also,
                            List.of(),
                            body,
                            List.of(),
                            0,
                            text.rewritten(),
                            text.leftAsText(),
                            notCarried,
                            ownSection
                                    // Read back, the first section of that name is taken for the
                                    // list of references.
                                    ? "its body already has a section " + IssueMapping.ALSO_NEEDS
                                    : bodyProblem(body)));
        }
        for (final Issue issue : issues.values()) {
            items.add(
                    issueItem(
                            issue,
                            sources.get(issue.id()),
                            issues,
                            parentOf,
                            subIssues,
                            webs,
                            carried,
                            references));
        }
        return new CarryOverPlan(items);
    }

    private static Item issueItem(
            final Issue issue,
            final @Nullable IssueSource source,
            final Map<String, Issue> issues,
            final Map<String, String> parentOf,
            final Map<String, List<String>> subIssues,
            final Map<String, String> webs,
            final ReferenceRewrite.Carried carried,
            final References references) {
        final IssueSource read = java.util.Objects.requireNonNull(source);
        final List<String> notCarried = new ArrayList<>();
        final String parent = issue.parent();
        String problem = null;
        if (parent != null && !issues.containsKey(parent)) {
            notCarried.add(parent);
            // Read back, a sub-issue's id names a parent that is not on the board.
            problem = "its parent " + parent + " is not carried";
        }
        final List<String> blockedBy = new ArrayList<>();
        for (final String blocker : issue.blockedBy()) {
            final Issue blocking = issues.get(blocker);
            if (blocking == null) {
                // A finished blocker blocks no more; one rejected never will.
                notCarried.add(blocker);
            } else {
                blockedBy.add(ref(references, blocking));
            }
        }
        final ReferenceRewrite.Rewritten text =
                ReferenceRewrite.rewrite(
                        read.covers().strip() + "\n\n" + read.below().strip() + "\n",
                        issue.repository(),
                        folder(read.path()),
                        webs,
                        carried);
        final Map<String, String> fields =
                new LinkedHashMap<>(IssueMapping.fields(issue, read.priority(), read.rank()));
        if (problem == null) {
            problem = bodyProblem(text.text());
        }
        final List<String> comments = new ArrayList<>();
        final List<CommentView> views = new ArrayList<>();
        for (final StageEntry stage : read.stages()) {
            try {
                final String comment = StageComment.write(stage);
                comments.add(comment);
                views.add(new CommentView("carried", comment, Instant.EPOCH));
            } catch (final IllegalArgumentException e) {
                problem = "a stage cannot be written as its comment: " + e.getMessage();
            }
        }
        final StageComment.Sums sums = StageComment.sums(views);
        if (problem == null && !sums.faults().isEmpty()) {
            // Read back, the sums would be named as a fault on every read of the board.
            problem = "its stage comments do not sum: " + sums.faults().getFirst();
        }
        if (read.estimate() != null) {
            fields.put(FieldNames.ESTIMATE, number(read.estimate()));
        }
        fields.put(FieldNames.MINUTES, number(sums.minutes()));
        fields.put(FieldNames.VM_MINUTES, number(sums.vmMinutes()));
        fields.put(FieldNames.TOKENS, Long.toString(sums.tokens()));
        fields.put(FieldNames.STAGES, Integer.toString(sums.stages()));
        return new Item(
                issue.id(),
                issue.repository(),
                false,
                issue.title(),
                fields,
                parentOf.get(issue.id()),
                subIssues.getOrDefault(issue.id(), List.of()),
                List.of(),
                blockedBy,
                text.text(),
                comments,
                read.moves(),
                text.rewritten(),
                text.leftAsText(),
                notCarried,
                problem);
    }

    /** The directory a file stands in, within its repository. */
    private static String folder(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String ref(final References references, final @Nullable Issue issue) {
        final Issue known = java.util.Objects.requireNonNull(issue);
        return references.reference(known.repository(), known.id());
    }

    private static @Nullable String bodyProblem(final String body) {
        return body.length() > BodySections.LONGEST
                ? "its body is longer than the " + BodySections.LONGEST + " characters GitHub takes"
                : null;
    }

    private static String number(final double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}

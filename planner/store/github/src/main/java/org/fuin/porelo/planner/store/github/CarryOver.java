package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TokenSource;
import org.jspecify.annotations.Nullable;

/**
 * Carries a Markdown project's open stories and issues over to a GitHub project, in two passes. The
 * first makes each item as a GitHub issue with a body whose references are not yet known, adds it
 * to the project and writes its fields and sums; the map of old id to new reference is then whole,
 * and the second adds the sub-issues and dependencies, writes each body anew from the Markdown text
 * with every carried reference rewritten, and adds the stage comments, oldest first.
 *
 * <p>Every step is recorded in the run's own map before and after it is taken, so a run again goes
 * on from where it stopped and makes nothing twice: an item about to be made is recorded as pending
 * first and, on a run again, looked for on GitHub by its title and first body before it is made.
 * Nothing on GitHub names an old id.
 */
public final class CarryOver {

    /** The reference's number in the worst case GitHub can give, for the length of a body. */
    static final String LONGEST_NUMBER = "999999999";

    /** What stands for a reference in the first pass's body, as long as the longest number. */
    static final String NOT_YET = "(carried)";

    /** How far before its recorded start a pending item is looked for, against a clock's skew. */
    static final Duration SKEW = Duration.ofMinutes(10);

    /** Keeps the map, as it grows, where a run again finds it. */
    @FunctionalInterface
    public interface Journal {

        /**
         * Saves the map whole.
         *
         * @param map the map
         */
        void save(CarryOverMap map);
    }

    /**
     * Why a run stopped before it was whole; the map holds how far it came.
     *
     * <p>Its words name items by their old id and repository or their reference, never a title or a
     * body.
     */
    public static final class Stopped extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stopped(final String message) {
            super(message, null, false, false);
        }
    }

    /**
     * What a run did.
     *
     * @param plan the plan the second pass wrote, its references GitHub's
     * @param references each item's tracker reference, by old id
     * @param failures one sentence per item left as it was, none when the run is whole
     */
    public record Outcome(
            CarryOverPlan plan, Map<String, String> references, List<String> failures) {

        /** Copies the map and the list. */
        public Outcome {
            references = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(references));
            failures = List.copyOf(failures);
        }

        /**
         * Answers whether every item was carried whole.
         *
         * @return whether no item was left
         */
        public boolean whole() {
            return failures.isEmpty();
        }
    }

    private final ProjectQueries queries;

    private final ProjectWrites writes;

    private final CarryOverCalls calls;

    private final TokenSource tokens;

    private final InstantSource clock;

    /**
     * Makes the run.
     *
     * @param queries the project's reads
     * @param writes the calls a save writes with
     * @param calls the calls only a carry-over makes
     * @param tokens the person's token
     * @param clock tells when an item's making begins
     */
    public CarryOver(
            final ProjectQueries queries,
            final ProjectWrites writes,
            final CarryOverCalls calls,
            final TokenSource tokens,
            final InstantSource clock) {
        this.queries = queries;
        this.writes = writes;
        this.calls = calls;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * Runs the carry-over, or goes on with one that stopped.
     *
     * @param config the project, its workflow and its repositories, the own one first
     * @param planner plans the carry-over with the references given, the same items each time
     * @param map the run's map, empty for a first run
     * @param journal keeps the map after each step
     * @return what was carried, and each item left as it was
     * @throws Stopped when the project is not fit, holds items this run did not make, or a step
     *     failed; the map holds every step taken
     */
    public Outcome run(
            final ProjectConfig config,
            final Function<CarryOverPlan.References, CarryOverPlan> planner,
            final CarryOverMap map,
            final Journal journal) {
        final String token = tokens.token(config.forge());
        try {
            return run(config, planner, map, journal, token);
        } catch (final Stopped e) {
            throw e;
        } catch (final RuntimeException e) {
            throw new Stopped(
                    "A call to GitHub failed: "
                            + Scrubbed.text(
                                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                                    List.of(token)));
        }
    }

    private Outcome run(
            final ProjectConfig config,
            final Function<CarryOverPlan.References, CarryOverPlan> planner,
            final CarryOverMap map,
            final Journal journal,
            final String token) {
        final CarryOverPlan longest =
                planner.apply((repository, id) -> repository + "#" + LONGEST_NUMBER);
        final CarryOverPlan first = planner.apply((repository, id) -> repository + "#" + NOT_YET);
        whole(longest);
        whole(first);
        final Set<String> planned = new HashSet<>();
        first.items().forEach(item -> planned.add(item.id()));
        for (final String id : map.entries().keySet()) {
            if (!planned.contains(id)) {
                throw new Stopped(
                        "The map holds "
                                + id
                                + ", which the project no longer carries; nothing was written");
            }
        }

        Fields fields = fields(config, token);
        final List<String> unfit = fields.unfit(config);
        if (!unfit.isEmpty()) {
            throw new Stopped(
                    "The GitHub project is not fit, so nothing was written: "
                            + String.join("; ", unfit));
        }
        checkItems(config, map, token);
        final ProjectConfig withId =
                new ProjectConfig(
                        config.forge(),
                        config.project(),
                        config.commit(),
                        config.workflow(),
                        config.work(),
                        config.repositories(),
                        config.fieldIds(),
                        config.optionIds(),
                        fields.projectId());
        new StatusOptionsWriter(queries, writes, origin -> token, AccessRefusals.DEFAULT)
                .apply(withId);
        fields = fields(config, token);
        final Map<String, String> statuses =
                fields.options().getOrDefault(FieldNames.STATUS, Map.of());
        if (!statuses.keySet().containsAll(config.workflow().states())) {
            throw new Stopped("The project's Status options are not the workflow's states");
        }

        for (final CarryOverPlan.Item item : first.items()) {
            firstPass(config, item, fields, map, journal, token);
        }

        final CarryOverPlan second =
                planner.apply(
                        (repository, id) -> {
                            final CarryOverMap.Entry entry = map.entry(id);
                            if (entry == null || !entry.repository().equals(repository)) {
                                throw new Stopped(id + " of " + repository + " is not in the map");
                            }
                            return entry.reference();
                        });
        whole(second);
        final Map<String, CarryOverMap.Entry> byReference = new HashMap<>();
        map.entries().values().forEach(entry -> byReference.put(entry.reference(), entry));
        final Map<String, String> firstBodies = new HashMap<>();
        first.items().forEach(item -> firstBodies.put(item.id(), item.text()));
        final List<String> failures = new ArrayList<>();
        final Map<String, String> references = new LinkedHashMap<>();
        for (final CarryOverPlan.Item item : second.items()) {
            final CarryOverMap.Entry entry = Objects.requireNonNull(map.entry(item.id()));
            references.put(item.id(), entry.reference());
            secondPass(
                            config,
                            item,
                            Objects.requireNonNull(firstBodies.get(item.id())),
                            byReference,
                            map,
                            journal,
                            token)
                    .ifPresent(failures::add);
        }
        return new Outcome(second, references, failures);
    }

    /** Refuses a plan with an item that cannot be written. */
    private static void whole(final CarryOverPlan plan) {
        final List<String> cannot = new ArrayList<>();
        for (final CarryOverPlan.Item item : plan.items()) {
            if (item.problem() != null) {
                cannot.add(item.id() + " of " + item.repository() + ": " + item.problem());
            }
        }
        if (!cannot.isEmpty()) {
            throw new Stopped(
                    "The plan is not whole, so nothing more was written: "
                            + String.join("; ", cannot));
        }
    }

    /**
     * Refuses a project holding an item this run did not make: without a map, any item; with one,
     * any whose issue the map does not hold.
     */
    private void checkItems(
            final ProjectConfig config, final CarryOverMap map, final String token) {
        final Set<String> made = new HashSet<>();
        map.entries().values().forEach(entry -> made.add(entry.nodeId()));
        final List<GitHubGraphQl.ProjectItem> items = new ArrayList<>();
        @Nullable String after = null;
        for (int page = 1; ; page++) {
            final GitHubGraphQl.ProjectItems answer =
                    queries.projectItems(
                            token, config.project().owner(), config.project().number(), after);
            items.addAll(answer.items());
            after = next(answer.pageInfo(), after, page);
            if (after == null) {
                break;
            }
        }
        if (map.entries().isEmpty() && !items.isEmpty()) {
            throw new Stopped(
                    "The GitHub project already holds "
                            + items.size()
                            + " item(s) and there is no map of an earlier run, so nothing is"
                            + " carried twice; nothing was written");
        }
        for (final GitHubGraphQl.ProjectItem item : items) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            if (issue == null || !made.contains(issue.id())) {
                throw new Stopped(
                        (issue == null
                                        ? "A project item without an issue"
                                        : "Item " + issue.repository() + "#" + issue.number())
                                + " stands on the GitHub project but was not made by this run;"
                                + " nothing more was written");
            }
        }
    }

    private void firstPass(
            final ProjectConfig config,
            final CarryOverPlan.Item item,
            final Fields fields,
            final CarryOverMap map,
            final Journal journal,
            final String token) {
        final String owner = config.project().owner();
        final String id = item.id();
        CarryOverMap.Entry entry = map.entry(id);
        if (entry == null || entry.progress() == CarryOverMap.Progress.PENDING) {
            final List<String> labels =
                    item.story() ? List.of(IssueMapping.STORY_LABEL) : List.of();
            final ProjectWrites.Created made;
            if (entry == null) {
                entry = CarryOverMap.Entry.pending(item.repository(), clock.instant());
                map.put(id, entry);
                journal.save(map);
                made =
                        writes.createIssue(
                                token, owner, item.repository(), item.title(), item.text(), labels);
            } else {
                made = madeBefore(item, entry, map, token, owner, labels);
            }
            if (item.story() && !made.labels().contains(IssueMapping.STORY_LABEL)) {
                // Read back without its label, the story would be taken for an issue.
                throw new Stopped(
                        id
                                + " of "
                                + item.repository()
                                + " was made as "
                                + item.repository()
                                + "#"
                                + made.number()
                                + " without the label "
                                + IssueMapping.STORY_LABEL
                                + "; give it the label and run again");
            }
            entry =
                    new CarryOverMap.Entry(
                            item.repository(),
                            entry.since(),
                            CarryOverMap.Progress.CREATED,
                            made.number(),
                            made.nodeId(),
                            made.databaseId(),
                            "");
            map.put(id, entry);
            journal.save(map);
        }
        if (entry.progress() == CarryOverMap.Progress.CREATED) {
            final String itemId = projectItem(token, fields.projectId(), entry);
            entry =
                    new CarryOverMap.Entry(
                            entry.repository(),
                            entry.since(),
                            CarryOverMap.Progress.ON_PROJECT,
                            entry.number(),
                            entry.nodeId(),
                            entry.databaseId(),
                            itemId);
            map.put(id, entry);
            journal.save(map);
        }
        if (entry.progress() == CarryOverMap.Progress.ON_PROJECT) {
            final List<GitHubGraphQl.FieldValue> values =
                    fields.values(entry.itemId(), item.fields(), entry.reference());
            for (int from = 0; from < values.size(); from += GitHubGraphQl.MAX_BATCH) {
                final Batch<String> batch =
                        writes.setFieldValues(
                                token,
                                fields.projectId(),
                                values.subList(
                                        from,
                                        Math.min(values.size(), from + GitHubGraphQl.MAX_BATCH)));
                if (!batch.complete()) {
                    throw new Stopped(
                            "GitHub did not take every field value of "
                                    + entry.reference()
                                    + "; run again");
                }
            }
            map.put(id, entry.at(CarryOverMap.Progress.FIELDS_SET));
            journal.save(map);
        }
    }

    /**
     * Finds the issue a run that stopped may have made for a pending item: one made since its
     * start, with its title and first body, and not already another item's. Made again only when
     * there is none.
     */
    private ProjectWrites.Created madeBefore(
            final CarryOverPlan.Item item,
            final CarryOverMap.Entry entry,
            final CarryOverMap map,
            final String token,
            final String owner,
            final List<String> labels) {
        final Instant since = entry.since().minus(SKEW);
        // Two items may share a title and a first body; an issue the map holds is already taken.
        final Set<String> taken = new HashSet<>();
        map.entries().values().stream()
                .map(CarryOverMap.Entry::nodeId)
                .filter(nodeId -> !nodeId.isEmpty())
                .forEach(taken::add);
        final List<ProjectWrites.Created> found = new ArrayList<>();
        for (int page = 1; ; page++) {
            if (page > CarryOverCalls.MOST_PAGES) {
                throw new Stopped(
                        item.repository() + " has more issues made since the run than are read");
            }
            final ProjectWrites.FoundPage issues =
                    writes.issuesCreatedSince(token, owner, item.repository(), since, page);
            issues.issues().stream()
                    .filter(made -> made.title().equals(item.title()))
                    .filter(made -> made.body().equals(item.text()))
                    .filter(made -> !taken.contains(made.issue().nodeId()))
                    .forEach(made -> found.add(made.issue()));
            if (!issues.more()) {
                break;
            }
        }
        if (found.size() > 1) {
            throw new Stopped(
                    item.id()
                            + " of "
                            + item.repository()
                            + " may have been made more than once: "
                            + String.join(
                                    ", ",
                                    found.stream()
                                            .map(made -> item.repository() + "#" + made.number())
                                            .toList())
                            + "; close all but one by hand and run again");
        }
        if (found.size() == 1) {
            return found.getFirst();
        }
        return writes.createIssue(
                token, owner, item.repository(), item.title(), item.text(), labels);
    }

    /** Adds an issue to the project; answers its item. */
    private String projectItem(
            final String token, final String projectId, final CarryOverMap.Entry entry) {
        final Batch<String> added =
                writes.addProjectItems(token, projectId, List.of(entry.nodeId()));
        if (added.outcomes().size() != 1 || !added.outcomes().getFirst().saved()) {
            throw new Stopped(
                    "GitHub did not add " + entry.reference() + " to the project; run again");
        }
        final String itemId = added.outcomes().getFirst().value();
        if (itemId == null || itemId.isBlank()) {
            throw new Stopped(
                    "GitHub added " + entry.reference() + " to the project without its item");
        }
        return itemId;
    }

    /** Writes an item's second pass; answers why it was left, or empty when it was written. */
    private Optional<String> secondPass(
            final ProjectConfig config,
            final CarryOverPlan.Item item,
            final String firstBody,
            final Map<String, CarryOverMap.Entry> byReference,
            final CarryOverMap map,
            final Journal journal,
            final String token) {
        final CarryOverMap.Entry entry = Objects.requireNonNull(map.entry(item.id()));
        if (entry.progress() == CarryOverMap.Progress.PASS2_DONE) {
            return Optional.empty();
        }
        final String owner = config.project().owner();
        final String repository = entry.repository();
        final int number = entry.number();
        final String body = calls.body(token, owner, repository, number);
        if (!body.equals(item.body()) && !body.equals(firstBody)) {
            return Optional.of(
                    item.id()
                            + " ("
                            + entry.reference()
                            + "): its body was changed on GitHub after the first pass, so its"
                            + " second pass was left; its references, sub-issues, dependencies"
                            + " and stage comments are not written");
        }
        final List<Long> subs = calls.subIssues(token, owner, repository, number);
        for (final String reference : item.subIssues()) {
            final long sub = known(byReference, reference).databaseId();
            if (!subs.contains(sub)) {
                writes.addSubIssue(token, owner, repository, number, sub);
            }
        }
        final List<Long> blockers = calls.blockedBy(token, owner, repository, number);
        for (final String reference : item.blockedBy()) {
            final long blocker = known(byReference, reference).databaseId();
            if (!blockers.contains(blocker)) {
                calls.addBlockedBy(token, owner, repository, number, blocker);
            }
        }
        if (!body.equals(item.body())) {
            writes.updateIssue(token, owner, repository, number, item.body(), null);
        }
        final Set<String> standing = new HashSet<>();
        for (int page = 1; ; page++) {
            if (page > CarryOverCalls.MOST_PAGES) {
                throw new Stopped(entry.reference() + " has more comments than are read");
            }
            final ProjectWrites.CommentsPage comments =
                    writes.issueComments(token, owner, repository, number, page);
            comments.bodies().forEach(comment -> standing.add(key(comment).orElse(comment)));
            if (!comments.more()) {
                break;
            }
        }
        for (final String comment : item.comments()) {
            if (standing.add(key(comment).orElse(comment))) {
                writes.createIssueComment(token, owner, repository, number, comment);
            }
        }
        map.put(item.id(), entry.at(CarryOverMap.Progress.PASS2_DONE));
        journal.save(map);
        return Optional.empty();
    }

    private static CarryOverMap.Entry known(
            final Map<String, CarryOverMap.Entry> byReference, final String reference) {
        final CarryOverMap.Entry entry = byReference.get(reference);
        if (entry == null) {
            throw new Stopped(reference + " is not in the map");
        }
        return entry;
    }

    /** A stage comment's key, which names it once however often it stands. */
    private static Optional<String> key(final String comment) {
        return StageComment.read(new CommentView("", comment, Instant.EPOCH))
                .filter(StageComment.Stage.class::isInstance)
                .map(read -> "key:" + ((StageComment.Stage) read).entry().key());
    }

    /**
     * The project's node id and fields.
     *
     * @param projectId the project's node id
     * @param kinds each field's data type, by name
     * @param ids each field's id, by name
     * @param options each single-select field's option ids, by field and option name
     */
    private record Fields(
            String projectId,
            Map<String, String> kinds,
            Map<String, String> ids,
            Map<String, Map<String, String>> options) {

        /** What the project lacks of the fields and options the planner writes. */
        List<String> unfit(final ProjectConfig config) {
            final List<String> unfit = new ArrayList<>();
            FieldNames.ALL.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            field -> {
                                final String kind = kinds.get(field.getKey());
                                if (kind == null) {
                                    unfit.add("it has no field " + field.getKey());
                                } else if (!kind.equals(field.getValue().name())) {
                                    unfit.add(
                                            "its field "
                                                    + field.getKey()
                                                    + " is not of the kind used");
                                }
                            });
            final Map<String, List<String>> wanted = new LinkedHashMap<>();
            wanted.put(FieldNames.PRIORITY, Story.PRIORITIES);
            wanted.put(FieldNames.SIZE, IssueMapping.SIZES);
            wanted.put(FieldNames.WORKER, List.of("Agent", "Manual"));
            wanted.put(
                    FieldNames.RISK,
                    Arrays.stream(Risk.values()).map(Risk::spelled).sorted().toList());
            wanted.forEach(
                    (field, names) -> {
                        if (FieldNames.Kind.SINGLE_SELECT.name().equals(kinds.get(field))) {
                            final Map<String, String> held = options.getOrDefault(field, Map.of());
                            names.stream()
                                    .filter(name -> !held.containsKey(name))
                                    .forEach(
                                            name ->
                                                    unfit.add(
                                                            "its field "
                                                                    + field
                                                                    + " has no option "
                                                                    + name));
                        }
                    });
            return unfit;
        }

        /** The values an item's fields are written as. */
        List<GitHubGraphQl.FieldValue> values(
                final String itemId, final Map<String, String> fields, final String where) {
            final List<GitHubGraphQl.FieldValue> values = new ArrayList<>();
            fields.forEach(
                    (name, value) -> {
                        final String fieldId = ids.get(name);
                        final FieldNames.Kind kind = FieldNames.ALL.get(name);
                        if (fieldId == null || kind == null) {
                            throw new Stopped(where + " has a value of no field the project has");
                        }
                        values.add(
                                switch (kind) {
                                    case SINGLE_SELECT -> {
                                        final String optionId =
                                                options.getOrDefault(name, Map.of()).get(value);
                                        if (optionId == null) {
                                            throw new Stopped(
                                                    where
                                                            + " has a value of "
                                                            + name
                                                            + " the project has no option for");
                                        }
                                        yield GitHubGraphQl.FieldValue.option(
                                                itemId, fieldId, optionId);
                                    }
                                    case NUMBER ->
                                            GitHubGraphQl.FieldValue.number(
                                                    itemId, fieldId, Double.parseDouble(value));
                                });
                    });
            return values;
        }
    }

    private Fields fields(final ProjectConfig config, final String token) {
        final Map<String, String> kinds = new LinkedHashMap<>();
        final Map<String, String> ids = new LinkedHashMap<>();
        final Map<String, Map<String, String>> options = new LinkedHashMap<>();
        String projectId = "";
        @Nullable String after = null;
        for (int page = 1; ; page++) {
            final GitHubGraphQl.ProjectFields answer =
                    queries.projectFields(
                            token, config.project().owner(), config.project().number(), after);
            projectId = answer.id();
            for (final GitHubGraphQl.ProjectField field : answer.fields()) {
                kinds.put(field.name(), field.dataType());
                ids.put(field.name(), field.id());
                final Map<String, String> byName = new LinkedHashMap<>();
                field.options().forEach(option -> byName.putIfAbsent(option.name(), option.id()));
                options.put(field.name(), byName);
            }
            after = next(answer.pageInfo(), after, page);
            if (after == null) {
                break;
            }
        }
        if (projectId == null || projectId.isBlank()) {
            throw new Stopped("GitHub answered the project without its id");
        }
        return new Fields(projectId, kinds, ids, options);
    }

    private static @Nullable String next(
            final GitHubGraphQl.PageInfo info, final @Nullable String cursor, final int page) {
        if (!Boolean.TRUE.equals(info.hasNextPage())) {
            return null;
        }
        final String end = info.endCursor();
        if (page >= ItemsBoardReader.MOST_PAGES
                || end == null
                || end.isEmpty()
                || end.equals(cursor)) {
            throw new StoreException.Unreadable(
                    "carry-over", "the GitHub project could not be read whole");
        }
        return end;
    }
}

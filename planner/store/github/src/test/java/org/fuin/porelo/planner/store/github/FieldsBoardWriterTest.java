package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GitHubGraphQl.BoardIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueOnBoard;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueRef;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueRefs;
import org.fuin.porelo.forge.github.GitHubGraphQl.ItemValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.PageInfo;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.forge.github.model.IssueFieldValue;
import org.fuin.porelo.forge.github.model.IssuesCreateRequestIssueFieldValuesInner;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequestIssueFieldValuesInner;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FieldsBoardWriterTest {

    private static final String TOKEN = "github_pat_writer_test_0123456789";

    private static final String OWNER = "example-org";

    private static final Revision SHOWN = new Revision("shown");

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Analysis", "Backlog", "Finished", "Rejected"),
                    List.of(
                            new Workflow.Phase(
                                    "All",
                                    List.of(
                                            "Idea",
                                            "Analysis",
                                            "Backlog",
                                            "Finished",
                                            "Rejected"))),
                    List.of("Finished", "Rejected"));

    private static final Map<String, String> FIELD_IDS = new LinkedHashMap<>();

    private static final Map<String, Map<String, String>> OPTION_IDS = new LinkedHashMap<>();

    static {
        for (final String field : FieldNames.ALL.keySet()) {
            FIELD_IDS.put(field, "F_" + field.replace(' ', '_'));
        }
        OPTION_IDS.put(FieldNames.STATUS, options(WORKFLOW.states()));
        OPTION_IDS.put(FieldNames.WORKER, options(List.of("Agent", "Manual")));
        OPTION_IDS.put(FieldNames.SIZE, options(IssueMapping.SIZES));
        OPTION_IDS.put(FieldNames.PRIORITY, options(List.of("High", "Medium")));
    }

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef(OWNER, 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    org.fuin.porelo.planner.store.ProjectRepository.named(
                            List.of("example-project", "example-lib", "example-tool")),
                    FIELD_IDS,
                    OPTION_IDS,
                    "PVT_7");

    private static final String BODY =
            "Covers the thing.\n\n## Plan\n\n- old step\n\n## Acceptance\n\n- it  works  \r\n";

    private static Map<String, String> options(final List<String> names) {
        final Map<String, String> ids = new LinkedHashMap<>();
        names.forEach(name -> ids.put(name, "O_" + name));
        return ids;
    }

    private static ItemView item(
            final String id,
            final String repository,
            final int number,
            final boolean open,
            final String body,
            final Map<String, String> extra) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.putAll(extra);
        return new ItemView(
                "PVTI_" + id,
                "I_" + id,
                repository,
                number,
                "Title of " + id,
                body,
                open,
                null,
                fields,
                List.of(),
                null,
                List.of(),
                List.of(),
                URI.create("https://github.com/" + OWNER + "/" + repository + "/issues/" + number),
                Instant.EPOCH,
                false);
    }

    /**
     * The id a test's item was made with, which its item id carries; an item of an issue the fake
     * GitHub made is named by its tracker reference.
     */
    private static String idOf(final ItemView item) {
        final String id = item.itemId().substring("PVTI_".length());
        return id.startsWith("I_") ? item.repository() + "#" + item.number() : id;
    }

    private static Issue issue(final ItemView item) {
        return new Issue(
                idOf(item),
                item.repository(),
                item.title(),
                // As the board reads it: a closed issue in the closed state its reason gives, an
                // open one without a Status in the workflow's first state.
                item.open()
                        ? item.fields().getOrDefault(FieldNames.STATUS, WORKFLOW.states().get(0))
                        : IssueMapping.closedState(item.stateReason(), WORKFLOW),
                null,
                List.of(),
                null,
                item.link(),
                item.body());
    }

    private static Snapshot snapshot(final ItemView... items) {
        final Map<String, ItemView> byId = new LinkedHashMap<>();
        final List<Issue> issues = new ArrayList<>();
        final List<Story> stories = new ArrayList<>();
        for (final ItemView item : items) {
            if (IssueMapping.isStory(item)) {
                stories.add(
                        new Story(
                                idOf(item),
                                item.title(),
                                "Idea",
                                "High",
                                null,
                                List.of(),
                                "",
                                item.link()));
            }
            byId.put(idOf(item), item);
            // A story is no issue of the board, and here the board leaves out an issue whose
            // Status is outside the workflow; the writer reads their items alone.
            final String status = item.fields().get(FieldNames.STATUS);
            if (!IssueMapping.isStory(item)
                    && (status == null || WORKFLOW.states().contains(status))) {
                issues.add(issue(item));
            }
        }
        return new Snapshot(
                new Board(
                        SHOWN,
                        WORKFLOW,
                        stories,
                        issues,
                        new Work(Map.of(), List.of()),
                        CONFIG.repositories()),
                byId,
                CONFIG);
    }

    /** An issue as GitHub's read by id gives it, with the field values the item holds. */
    private static @Nullable String upper(final @Nullable String reason) {
        return reason == null ? null : reason.toUpperCase(java.util.Locale.ROOT);
    }

    private static IssueOnBoard onBoard(final ItemView item) {
        final List<ItemValue> values = new ArrayList<>();
        item.fields()
                .forEach(
                        (field, value) -> {
                            if (FieldNames.PRIORITY.equals(field)) {
                                return;
                            }
                            final String fieldId = FIELD_IDS.get(field);
                            final Map<String, String> options = OPTION_IDS.get(field);
                            if (options != null) {
                                values.add(
                                        new ItemValue(
                                                fieldId, options.get(value), value, null, null));
                            } else if (FieldNames.ALL.get(field) == FieldNames.Kind.NUMBER) {
                                values.add(
                                        new ItemValue(
                                                fieldId, null, null, null, Double.valueOf(value)));
                            } else {
                                values.add(new ItemValue(fieldId, null, null, value, null));
                            }
                        });
        final IssueRefs none = new IssueRefs(List.<IssueRef>of(), new PageInfo(false, null), 0);
        return new IssueOnBoard(
                new BoardIssue(
                        item.issueId(),
                        5000L + item.number(),
                        item.number(),
                        item.title(),
                        item.body(),
                        item.open() ? "OPEN" : "CLOSED",
                        // GitHub's GraphQL names the reason in capitals.
                        upper(item.stateReason()),
                        item.updatedAt(),
                        OWNER + "/" + item.repository(),
                        item.labels(),
                        item.parentIssueId(),
                        new IssueRefs(
                                item.subIssueIds().stream()
                                        .map(id -> new IssueRef(id, 0L, 0, ""))
                                        .toList(),
                                new PageInfo(false, null),
                                item.subIssueIds().size()),
                        none,
                        new GitHubGraphQl.PageInfo(false, null),
                        item.issueType(),
                        item.closedAt(),
                        item.fields().containsKey(FieldNames.PRIORITY)
                                ? List.of(
                                        new GitHubGraphQl.IssueFieldOption(
                                                FieldNames.PRIORITY,
                                                item.fields().get(FieldNames.PRIORITY)))
                                : List.of(),
                        0),
                item.itemId(),
                values,
                new GitHubGraphQl.PageInfo(false, null),
                0);
    }

    /** GitHub's side of a write: what it holds, what it answers, and every call made. */
    private static final class GitHub implements ProjectWrites {
        private final Map<String, IssueOnBoard> held = new LinkedHashMap<>();
        private final Deque<Function<List<FieldValue>, Batch<String>>> batches = new ArrayDeque<>();
        private final List<List<FieldValue>> mutations = new ArrayList<>();
        private final List<String> updates = new ArrayList<>();
        private final Map<Integer, String> bodies = new LinkedHashMap<>();
        private final List<String> comments = new ArrayList<>();

        /** The comments written, by the issue's number, which a later read of them finds. */
        private final Map<Integer, List<String>> commented = new LinkedHashMap<>();

        /** The comment written that fails, counted from 1, and how many were tried; 0 for none. */
        private int failingComment;

        private int commentsTried;
        private final List<String> standing = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private final Map<String, List<Found>> made = new LinkedHashMap<>();
        private final Deque<String> creating = new ArrayDeque<>();
        private final List<String> added = new ArrayList<>();
        private final List<String> subIssues = new ArrayList<>();

        /** The failures the next calls that link a sub-issue end in, one each. */
        private final Deque<RuntimeException> linkFailures = new ArrayDeque<>();

        /** How many calls that link a sub-issue succeed before the failures are taken. */
        private int linksBeforeFailing;

        /** When each issue was made, by its node id. */
        private final Map<String, Instant> madeAt = new LinkedHashMap<>();

        /** The failures the next calls that add items to the project end in, one each. */
        private final Deque<RuntimeException> addFailures = new ArrayDeque<>();

        /** The field values GitHub took, by item id and field id. */
        private final Map<String, Map<String, FieldValue>> values = new LinkedHashMap<>();

        /**
         * The pages of comments before the one that holds {@code standing}, each with one comment
         * of its own.
         */
        private int pagesBefore;

        /** The text of each comment on the pages before, or null for one of each page's own. */
        private @Nullable String earlierBody;

        /**
         * The comments GitHub answers each page with in turn, over and over, another page always to
         * follow; empty for an issue's real pages.
         */
        private final List<Comment> cycle = new ArrayList<>();

        /** What happens on GitHub while the comments are read, such as a person's edit. */
        private Runnable whileCommentsAreRead = () -> {};

        private @Nullable RuntimeException updateFailure;

        /** The waits of the writes, in order; a test waits nothing. */
        private final List<java.time.Duration> waits = new ArrayList<>();

        /** The writer's time, moved on by each wait as a real wait moves it. */
        private Instant now = NOW;

        /** Whether the update's failure happens only once, as a passing one of GitHub's. */
        private boolean failsOnce;

        GitHub holding(final ItemView... items) {
            for (final ItemView item : items) {
                held.put(item.issueId(), onBoard(item));
            }
            return this;
        }

        /** Makes an issue in a repository as GitHub does, numbered across the repositories. */
        Created make(
                final String repository,
                final String title,
                final String body,
                final @Nullable String type) {
            return makeAt(repository, title, body, type, NOW);
        }

        /** Makes an issue at a moment, numbered across the repositories. */
        Created makeAt(
                final String repository,
                final String title,
                final String body,
                final @Nullable String type,
                final Instant at) {
            final int number = 100 + made.values().stream().mapToInt(List::size).sum();
            final Created created =
                    new Created("I_" + repository + "_" + number, 9000L + number, number, type);
            made.computeIfAbsent(repository, r -> new ArrayList<>())
                    // GitHub keeps a title without the spaces around it.
                    .add(new Found(created, title.strip(), body));
            madeAt.put(created.nodeId(), at);
            return created;
        }

        /** How the next calls that make an issue are answered, one word each. */
        GitHub creating(final String... answers) {
            creating.addAll(List.of(answers));
            return this;
        }

        GitHub answering(final Function<List<FieldValue>, Batch<String>> answer) {
            batches.add(answer);
            return this;
        }

        FieldsBoardWriter writer() {
            return writer(this);
        }

        FieldsBoardWriter writer(final ProjectWrites writes) {
            return writer(writes, LimitFactors.DEFAULT);
        }

        FieldsBoardWriter writer(final ProjectWrites writes, final LimitFactors limits) {
            return new FieldsBoardWriter(
                    writes,
                    origin -> TOKEN,
                    new ForgeAccessRefusals(URI.create("https://github.com")),
                    () -> now,
                    wait -> {
                        waits.add(wait);
                        now = now.plus(wait);
                    },
                    limits);
        }

        @Override
        public GitHubGraphQl.IssueBoard issueBoard(
                final String token, final List<String> issueIds, final String projectId) {
            calls.add("issueBoard");
            assertThat(token).as("the token of the read").isEqualTo(TOKEN);
            assertThat(projectId).as("the project read").isEqualTo("PVT_7");
            return new GitHubGraphQl.IssueBoard(
                    issueIds.stream().map(held::get).toList(),
                    new GraphQlCost(1, 4999, Instant.EPOCH));
        }

        @Override
        public Batch<String> addProjectItems(
                final String token, final String projectId, final List<String> issueIds) {
            calls.add("addProjectItems");
            final RuntimeException failure = addFailures.poll();
            if (failure != null) {
                throw failure;
            }
            added.addAll(issueIds);
            return new Batch<>(
                    issueIds.stream().map(id -> Batch.Outcome.of("PVTI_" + id)).toList(),
                    null,
                    GitHubExceptions.WITHOUT_A_WORD);
        }

        @Override
        public Batch<String> setFieldValues(
                final String token, final String projectId, final List<FieldValue> values) {
            calls.add("setFieldValues");
            mutations.add(List.copyOf(values));
            final Function<List<FieldValue>, Batch<String>> answer = batches.poll();
            final Batch<String> batch = answer != null ? answer.apply(values) : saved(values);
            for (int at = 0; at < values.size() && at < batch.outcomes().size(); at++) {
                if (batch.outcomes().get(at).saved()) {
                    final FieldValue value = values.get(at);
                    this.values
                            .computeIfAbsent(value.itemId(), id -> new LinkedHashMap<>())
                            .put(value.fieldId(), value);
                }
            }
            return batch;
        }

        @Override
        public Created createIssue(
                final String token,
                final String owner,
                final String repository,
                final String title,
                final String body,
                final @Nullable String type,
                final List<IssuesCreateRequestIssueFieldValuesInner> issueFieldValues,
                final List<String> labels) {
            calls.add("createIssue");
            sentLabels.add(labels);
            assertThat(token).as("the issue made with the person's token").isEqualTo(TOKEN);
            assertThat(owner).as("the owner of the repository").isEqualTo(OWNER);
            final String answer = creating.isEmpty() ? "answer" : creating.poll();
            if (answer.equals("refused")) {
                throw GitHubExceptions.refused(422, "Validation failed for " + TOKEN);
            }
            if (answer.equals("lost")) {
                throw GitHubExceptions.unanswered("timed out");
            }
            if (answer.startsWith("twice")) {
                // A call GitHub took twice, as a proxy that sends it again does.
                make(repository, title, body, type);
            }
            final Created created =
                    make(repository, title, body, answer.startsWith("untyped") ? null : type);
            final List<IssueFieldValue> fields =
                    answer.equals("unprioritized")
                            ? List.of()
                            : issueFieldValues.stream()
                                    .map(
                                            value ->
                                                    OrganizationFields.priority(
                                                            (String) value.value()))
                                    .toList();
            organizationValues.put(created.number(), fields);
            final Created answered =
                    new Created(
                            created.nodeId(),
                            created.databaseId(),
                            created.number(),
                            created.type(),
                            fields,
                            // An account without the right to label has them dropped in silence.
                            answer.equals("unlabelled") ? List.of() : labels);
            final List<Found> issues = Objects.requireNonNull(made.get(repository));
            issues.set(issues.size() - 1, new Found(answered, title.strip(), body));
            if (answer.endsWith("unanswered")) {
                throw GitHubExceptions.unanswered("timed out");
            }
            if (answer.equals("failed")) {
                throw GitHubExceptions.refused(502, "bad gateway");
            }
            return answered;
        }

        @Override
        public void addSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {
            calls.add("addSubIssue");
            final RuntimeException failure = linksBeforeFailing-- > 0 ? null : linkFailures.poll();
            if (failure != null) {
                throw failure;
            }
            subIssues.add(repository + "#" + number + " < " + subIssueId);
        }

        @Override
        public FoundPage issuesCreatedSince(
                final String token,
                final String owner,
                final String repository,
                final Instant since,
                final int page) {
            calls.add("issuesCreatedSince");
            assertThat(since)
                    .as("the look starts before the call was sent")
                    .isBeforeOrEqualTo(NOW.minusSeconds(60));
            // GitHub lists only the issues made at or after the moment asked for.
            final List<Found> mine =
                    made.getOrDefault(repository, List.of()).stream()
                            .filter(f -> !madeAt.get(f.issue().nodeId()).isBefore(since))
                            .toList();
            final int from = (page - 1) * 2;
            return new FoundPage(
                    mine.subList(Math.min(from, mine.size()), Math.min(from + 2, mine.size())),
                    from + 2 < mine.size());
        }

        @Override
        public void removeSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {
            throw new AssertionError("a save changed a link");
        }

        @Override
        public void reprioritizeSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId,
                final @Nullable Long afterId,
                final @Nullable Long beforeId) {
            throw new AssertionError("a save changed a link");
        }

        @Override
        public void addBlockedBy(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long blockerId) {
            throw new AssertionError("a save changed a link");
        }

        @Override
        public void removeBlockedBy(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long blockerId) {
            throw new AssertionError("a save changed a link");
        }

        @Override
        public List<String> updateIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final @Nullable String title,
                final @Nullable String body,
                final @Nullable Opening opening,
                final @Nullable List<IssuesUpdateRequestIssueFieldValuesInner> issueFieldValues,
                final @Nullable List<String> labels) {
            assertThat(title).as("only an edit of the text sends a title").isNull();
            assertThat(labels).as("only an edit of the text sends labels").isNull();
            if (issueFieldValues != null) {
                priorityUpdates.add(issueFieldValues);
                organizationValues.put(
                        number,
                        issueFieldValues.stream()
                                .filter(value -> value.fieldId().equals(11L))
                                .map(value -> OrganizationFields.priority((String) value.value()))
                                .toList());
            }
            calls.add("updateIssue");
            final RuntimeException failure = updateFailure;
            if (failure != null) {
                if (failsOnce) {
                    updateFailure = null;
                }
                throw failure;
            }
            updates.add(repository + "#" + number + " " + opening);
            if (body != null) {
                bodies.put(number, body);
            }
            return List.of();
        }

        private final List<List<String>> sentLabels = new ArrayList<>();

        private final Map<Integer, List<IssueFieldValue>> organizationValues =
                new LinkedHashMap<>();
        private final List<List<IssuesUpdateRequestIssueFieldValuesInner>> priorityUpdates =
                new ArrayList<>();

        @Override
        public List<IssueField> issueFields(final String token, final String owner) {
            return OrganizationFields.fields();
        }

        @Override
        public List<IssueFieldValue> issueFieldValues(
                final String token, final String owner, final String repository, final int number) {
            if (organizationValues.containsKey(number)) {
                return organizationValues.get(number);
            }
            return held.values().stream()
                    .filter(value -> value.issue().number() == number)
                    .flatMap(value -> value.issue().fieldOptions().stream())
                    .filter(value -> FieldNames.PRIORITY.equals(value.field()))
                    .map(value -> OrganizationFields.priority(value.option()))
                    .toList();
        }

        @Override
        public CommentsPage issueComments(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final int page) {
            calls.add("issueComments");
            whileCommentsAreRead.run();
            if (calls.stream().filter("issueComments"::equals).count() > 100) {
                throw new AssertionError("an issue's comments read without end");
            }
            if (!cycle.isEmpty()) {
                return new CommentsPage(List.of(cycle.get((page - 1) % cycle.size())), true);
            }
            if (page <= pagesBefore) {
                final String body = earlierBody;
                return new CommentsPage(
                        List.of(
                                new Comment(
                                        page, body != null ? body : "A comment on page " + page)),
                        true);
            }
            final List<Comment> found = new ArrayList<>();
            long id = 10_000;
            for (final String body : standing) {
                found.add(new Comment(id++, body));
            }
            for (final String body : commented.getOrDefault(number, List.of())) {
                found.add(new Comment(id++, body));
            }
            return new CommentsPage(page == pagesBefore + 1 ? found : List.of(), false);
        }

        @Override
        public void createIssueComment(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final String body) {
            calls.add("createIssueComment");
            assertThat(token).as("the comment written with the person's token").isEqualTo(TOKEN);
            if (++commentsTried == failingComment) {
                throw GitHubExceptions.refused(502, "bad gateway");
            }
            comments.add(body);
            commented.computeIfAbsent(number, key -> new ArrayList<>()).add(body);
        }
    }

    private static Batch<String> saved(final List<FieldValue> values) {
        return new Batch<>(
                values.stream().map(v -> Batch.Outcome.of(v.itemId())).toList(),
                null,
                GitHubExceptions.WITHOUT_A_WORD);
    }

    private static Map<String, String> ordered(final String... pairs) {
        final Map<String, String> map = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            map.put(pairs[at], pairs[at + 1]);
        }
        return map;
    }

    private static Map<String, String> state(final String status) {
        return Map.of(FieldNames.STATUS, status);
    }

    @Test
    void highestIsWrittenAsUrgentAndPreservesOtherIssueFieldsWithoutProjectPriority() {
        // Harm: replacing issue field values loses other fields or writes priority where nobody
        // reads it.
        final ItemView one =
                item("PL1", "example-project", 1, true, BODY, Map.of(FieldNames.PRIORITY, "High"));
        final GitHub github = new GitHub().holding(one);
        final IssueFieldValue text =
                new IssueFieldValue(
                        22L,
                        "Context",
                        "IF_22",
                        IssueFieldValue.DataTypeEnum.TEXT,
                        "keep this",
                        null,
                        null);
        final IssueFieldValue select =
                new IssueFieldValue(
                        23L,
                        "Team",
                        "IF_23",
                        IssueFieldValue.DataTypeEnum.SINGLE_SELECT,
                        900L,
                        new org.fuin.porelo.forge.github.model.IssueFieldValueSingleSelectOption(
                                900L, "Platform", "blue"),
                        null);
        github.organizationValues.put(
                1, List.of(OrganizationFields.priority("High"), text, select));

        github.writer()
                .setFields(
                        snapshot(one),
                        SHOWN,
                        "PL1",
                        new org.fuin.porelo.planner.store.Fields(
                                Set.of(org.fuin.porelo.planner.store.Fields.Field.PRIORITY),
                                null,
                                null,
                                "Highest",
                                null));

        assertThat(github.priorityUpdates)
                .as("one replacement of all issue field values")
                .containsExactly(
                        List.of(
                                new IssuesUpdateRequestIssueFieldValuesInner(
                                        22L, "keep this", null, null, null),
                                new IssuesUpdateRequestIssueFieldValuesInner(
                                        23L, "Platform", null, null, null),
                                new IssuesUpdateRequestIssueFieldValuesInner(
                                        11L, "Urgent", null, null, null)));
        assertThat(github.mutations).as("no project Priority mutation").isEmpty();
    }

    @Test
    void anUnchangedTranslatedPrioritySendsNothingAndLowestIsRefusedBeforeAnyCall() {
        // Harm: an unnecessary replacement loses fields, or Lowest saves a priority GitHub cannot
        // hold.
        final ItemView one =
                item(
                        "PL1",
                        "example-project",
                        1,
                        true,
                        BODY,
                        Map.of(FieldNames.PRIORITY, "Urgent"));
        final GitHub github = new GitHub().holding(one);
        assertThat(
                        github.writer()
                                .setFields(
                                        snapshot(one),
                                        SHOWN,
                                        "PL1",
                                        new org.fuin.porelo.planner.store.Fields(
                                                Set.of(
                                                        org.fuin.porelo.planner.store.Fields.Field
                                                                .PRIORITY),
                                                null,
                                                null,
                                                "Highest",
                                                null)))
                .as("the translated value already stands")
                .isFalse();
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .setFields(
                                                snapshot(one),
                                                SHOWN,
                                                "PL1",
                                                new org.fuin.porelo.planner.store.Fields(
                                                        Set.of(
                                                                org.fuin.porelo.planner.store.Fields
                                                                        .Field.PRIORITY),
                                                        null,
                                                        null,
                                                        "Lowest",
                                                        null)))
                .as("Lowest has no organization option")
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(github.calls).as("no call for either request").isEmpty();
    }

    @Test
    void aStorysMissingOrUnknownPriorityIsSetRightInTheIssueFieldByASave() {
        // Harm: the correction of a story's priority saved as written while GitHub keeps none,
        // or one it does not know, so the warning never goes.
        final ItemView none = storyItem("ST1", 2);
        final ItemView unknown =
                item("ST2", "example-project", 3, true, "", Map.of(FieldNames.PRIORITY, "Someday"))
                        .typed(IssueMapping.STORY_TYPE);
        final GitHub github = new GitHub().holding(none, unknown);

        github.writer()
                .save(
                        snapshot(none, unknown),
                        SHOWN,
                        new Writes(
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                ordered("ST1", "Highest", "ST2", "Low")));

        assertThat(github.organizationValues)
                .as("each story's Priority now one GitHub holds")
                .containsEntry(2, List.of(OrganizationFields.priority("Urgent")))
                .containsEntry(3, List.of(OrganizationFields.priority("Low")));
    }

    @Test
    void anIssueFieldThatCannotBePreservedRefusesTheWholeSave() {
        // Harm: replacing the priority discards an issue field the store cannot send back.
        final ItemView one =
                item("PL1", "example-project", 1, true, BODY, Map.of(FieldNames.PRIORITY, "High"));
        final GitHub github = new GitHub().holding(one);
        github.organizationValues.put(
                1,
                List.of(
                        OrganizationFields.priority("High"),
                        new IssueFieldValue(
                                22L,
                                "Teams",
                                "IF_22",
                                IssueFieldValue.DataTypeEnum.MULTI_SELECT,
                                List.of(1L),
                                null,
                                List.of())));
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .setFields(
                                                snapshot(one),
                                                SHOWN,
                                                "PL1",
                                                new org.fuin.porelo.planner.store.Fields(
                                                        Set.of(
                                                                org.fuin.porelo.planner.store.Fields
                                                                        .Field.PRIORITY,
                                                                org.fuin.porelo.planner.store.Fields
                                                                        .Field.SIZE),
                                                        "M",
                                                        null,
                                                        "Highest",
                                                        null)))
                .as("unsupported preservation refuses before a write")
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(github.calls).as("only the preflight board read").containsExactly("issueBoard");
        assertThat(github.priorityUpdates).as("no REST field replacement").isEmpty();
        assertThat(github.mutations).as("no project field write").isEmpty();
    }

    @Test
    void aNewHighestIssueFollowsTheUrgentIssuesRank() {
        // Harm: a new urgent issue is ranked ahead of urgent work the person already ordered.
        final ItemView urgent =
                item(
                        "PL1",
                        "example-lib",
                        1,
                        true,
                        BODY,
                        Map.of(FieldNames.PRIORITY, "Urgent", FieldNames.RANK, "8"));
        final GitHub github = new GitHub().holding(urgent);
        github.writer()
                .createIssue(
                        snapshot(urgent),
                        SHOWN,
                        new NewIssue(
                                "example-lib",
                                "Urgent work",
                                "It holds.",
                                "S",
                                Worker.AGENT,
                                null,
                                "Highest",
                                null));
        assertThat(github.organizationValues.get(100))
                .as("the new issue's organization Priority")
                .containsExactly(OrganizationFields.priority("Urgent"));
        assertThat(github.values.get("PVTI_I_example-lib_100"))
                .as("the rank follows translated Highest")
                .containsEntry("F_Rank", FieldValue.number("PVTI_I_example-lib_100", "F_Rank", 9));
    }

    @Test
    void aNewIssuesTopicLabelsAreSentWithItAndLabelsGitHubDroppedAreNotKeptOnceItIsPlaced() {
        // Harm: an account without the right to label sees labels saved that GitHub left out, or
        // the issue made without them is never placed on the board.
        final NewIssue labelled =
                new NewIssue(
                        "example-lib",
                        "A new thing",
                        "It holds.",
                        "S",
                        Worker.AGENT,
                        null,
                        null,
                        null,
                        null,
                        List.of("usability", "tests"));
        final GitHub github = new GitHub();

        github.writer().createIssue(snapshot(), SHOWN, labelled);

        assertThat(github.sentLabels)
                .as("the topic labels, sent with the issue")
                .containsExactly(List.of("tests", "usability"));

        final GitHub dropping = new GitHub().creating("unlabelled");
        assertThatThrownBy(() -> dropping.writer().createIssue(snapshot(), SHOWN, labelled))
                .isInstanceOfSatisfying(
                        StoreException.LabelsNotKept.class,
                        refused -> {
                            assertThat(refused.subject()).isEqualTo("example-lib");
                            assertThat(held(dropping))
                                    .as("the issue made, named")
                                    .containsExactly(refused.item());
                        });
        assertThat(dropping.added).as("the issue made is placed all the same").hasSize(1);
    }

    @Test
    void aCreateWithoutItsRequestedPriorityIsFinishedWithoutCreatingItAgain() {
        // Harm: GitHub dropping a create's Priority is accepted, or retry makes a duplicate issue.
        final GitHub github = new GitHub().creating("unprioritized");
        final NewIssue issue = newIssue("example-lib", "A new thing");
        assertThatThrownBy(() -> github.writer().createIssue(snapshot(), SHOWN, issue))
                .as("the issue exists but its priority was dropped")
                .isInstanceOf(StoreException.PartlySaved.class);
        assertThat(github.added).as("the incomplete create has not been placed").isEmpty();

        github.writer().createIssue(snapshot(), SHOWN, issue);

        assertThat(held(github)).as("the retry takes the one existing issue").hasSize(1);
        assertThat(github.priorityUpdates)
                .as("the missing Medium priority is completed")
                .containsExactly(
                        List.of(
                                new IssuesUpdateRequestIssueFieldValuesInner(
                                        11L, "Medium", null, null, null)));
    }

    @Test
    void aMoveOfTwoIssuesInTwoRepositoriesIsOneMutation() {
        // Harm: a move written call by call, slow and torn apart by a failure in its middle.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Idea"));
        final ItemView two = item("LB2", "example-lib", 2, true, "", state("Idea"));
        final GitHub github = new GitHub().holding(one, two);

        github.writer()
                .save(
                        snapshot(one, two),
                        SHOWN,
                        new Writes(Map.of("PL1", "Backlog", "LB2", "Backlog"), Map.of()));

        assertThat(github.mutations).as("the mutations sent").hasSize(1);
        assertThat(github.mutations.get(0))
                .as("the values of the one mutation")
                .containsExactlyInAnyOrder(
                        FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog"),
                        FieldValue.option("PVTI_LB2", "F_Status", "O_Backlog"));
    }

    @Test
    void anIssueWithoutAStatusIsNotGivenOneByASaveBesideIt() {
        // Harm: a save of other items writes the state the board shows for an issue that states
        // none, so a person's empty Status turns into one they never chose. A guard of behavior
        // the writer already had: it never wrote a field of an item it was not given.
        final ItemView moved = item("PL1", "example-project", 1, true, "", state("Idea"));
        final ItemView unstated = item("LB3", "example-lib", 3, true, "", Map.of());
        final GitHub github = new GitHub().holding(moved, unstated);
        final Snapshot snapshot = snapshot(moved, unstated);
        assertThat(snapshot.board().issue("LB3").orElseThrow().state())
                .as("shown in the first state")
                .isEqualTo("Idea");

        github.writer().save(snapshot, SHOWN, new Writes(Map.of("PL1", "Backlog"), Map.of()));

        assertThat(github.mutations)
                .as("only the moved issue's Status")
                .containsExactly(List.of(FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog")));
    }

    @Test
    void aRevisionOtherThanTheBoardsIsRefusedWithoutWriting() {
        // Harm: a move planned on an old board overwrites what the board kept has since read.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Idea"));
        final GitHub github = new GitHub().holding(one);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(one),
                                                new Revision("older"),
                                                new Writes(Map.of("PL1", "Backlog"), Map.of())))
                .as("a save against a revision the board no longer holds")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(github.mutations).as("the mutations sent").isEmpty();
    }

    @Test
    void aFieldAPersonChangedOnGitHubMeanwhileIsRefusedNamingTheItemWithoutWriting() {
        // Harm: a person's move on GitHub's board, which leaves the issue's change time as it
        // was, silently overwritten by an older move from the window.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Idea"));
        final ItemView moved = item("PL1", "example-project", 1, true, "", state("Analysis"));
        final GitHub github = new GitHub().holding(moved);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(one),
                                                SHOWN,
                                                new Writes(Map.of("PL1", "Backlog"), Map.of())))
                .as("a save over an item whose Status a person changed")
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale -> assertThat(stale.changed()).as("the item named").isEqualTo("PL1"));
        assertThat(github.mutations).as("the mutations sent").isEmpty();
    }

    @Test
    void aBodyAPersonChangedOnGitHubMeanwhileIsRefusedWithoutWriting() {
        // Harm: a person's words in the issue's body replaced by a stage's older copy of it.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final ItemView edited =
                item(
                        "PL1",
                        "example-project",
                        1,
                        true,
                        BODY + "A person's line.\n",
                        state("Analysis"));
        final GitHub github = new GitHub().holding(edited);

        assertThatThrownBy(() -> github.writer().saveResult(snapshot(one), analysis(), null))
                .as("a result over a body a person changed")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(github.calls).as("the calls made").containsExactly("issueBoard");
    }

    @Test
    void theSecondMutationFailingIsPartlySavedNamingTheRepositoriesSaved() {
        // Harm: a save of which GitHub took only a part answered as refused, so the window
        // makes it again over what was written, or as saved, hiding what is missing.
        final List<ItemView> items = new ArrayList<>();
        final Map<String, String> moves = new LinkedHashMap<>();
        for (int n = 1; n <= GitHubGraphQl.MAX_BATCH + 1; n++) {
            items.add(item("PL" + n, "example-project", n, true, "", state("Idea")));
            moves.put("PL" + n, "Backlog");
        }
        final ItemView lib = item("LB1", "example-lib", 900, true, "", state("Idea"));
        items.add(lib);
        moves.put("LB1", "Backlog");
        final GitHub github =
                new GitHub()
                        .holding(items.toArray(ItemView[]::new))
                        .answering(FieldsBoardWriterTest::saved)
                        // Asked again after GitHub's failure, which holds every time.
                        .answering(
                                values -> {
                                    throw GitHubExceptions.refused(502, "bad gateway " + TOKEN);
                                })
                        .answering(
                                values -> {
                                    throw GitHubExceptions.refused(502, "bad gateway " + TOKEN);
                                })
                        .answering(
                                values -> {
                                    throw GitHubExceptions.refused(502, "bad gateway " + TOKEN);
                                });

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(items.toArray(ItemView[]::new)),
                                                SHOWN,
                                                new Writes(moves, Map.of())))
                .as("a save whose second mutation fails")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly -> {
                            assertThat(partly.pushed())
                                    .as("the repositories GitHub saved a part of")
                                    .containsExactly("example-project");
                            assertThat(partly.getMessage())
                                    .as("the failure's words")
                                    .doesNotContain(TOKEN);
                        });
        assertThat(github.mutations)
                .as("the mutations sent: the second asked three times")
                .hasSize(1 + WritePace.TRIES);
    }

    @Test
    void aPartGitHubRefusedInsideOneMutationIsPartlySaved() {
        // Harm: GitHub saving the parts around a refused one while the window is told nothing
        // was saved.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Idea"));
        final ItemView two = item("LB2", "example-lib", 2, true, "", state("Idea"));
        final GitHub github =
                new GitHub()
                        .holding(one, two)
                        .answering(
                                values ->
                                        new Batch<>(
                                                List.of(
                                                        Batch.Outcome.of(values.get(0).itemId()),
                                                        Batch.Outcome.<String>refused(
                                                                "refused for " + TOKEN)),
                                                null,
                                                GitHubExceptions.WITHOUT_A_WORD));

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(one, two),
                                                SHOWN,
                                                new Writes(
                                                        ordered("PL1", "Backlog", "LB2", "Backlog"),
                                                        Map.of())))
                .as("a mutation whose second part GitHub refused")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly -> {
                            assertThat(partly.pushed())
                                    .as("the repository saved")
                                    .containsExactly("example-project");
                            assertThat(partly.subject())
                                    .as("the one refused")
                                    .isEqualTo("example-lib");
                            assertThat(partly.getMessage()).as("its words").doesNotContain(TOKEN);
                        });
    }

    @Test
    void aMoveToAClosedStateClosesTheIssueAndAMoveBackReopensIt() {
        // Harm: an issue Finished on the board left open on GitHub, or one moved back left
        // closed, so GitHub's own lists disagree with the board.
        final ItemView open = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final ItemView closed = item("PL2", "example-project", 2, false, "", state("Finished"));
        final GitHub github = new GitHub().holding(open, closed);

        github.writer()
                .save(
                        snapshot(open, closed),
                        SHOWN,
                        new Writes(ordered("PL1", "Rejected", "PL2", "Backlog"), Map.of()));

        assertThat(github.updates)
                .as("the open states written")
                .containsExactly(
                        "example-project#1 Opening[open=false, notPlanned=true]",
                        "example-project#2 Opening[open=true, notPlanned=false]");
    }

    @Test
    void aMoveToAClosedStateIsTheCloseWithItsReasonAloneInOneCall() {
        // Harm: a close written as a Status and a close, two writes a cut-off leaves half done,
        // so the board and GitHub's own lists disagree.
        final ItemView open = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final GitHub github = new GitHub().holding(open);

        github.writer()
                .save(snapshot(open), SHOWN, new Writes(ordered("PL1", "Rejected"), Map.of()));

        assertThat(github.mutations).as("no field written").isEmpty();
        assertThat(github.updates)
                .as("the one close, as not planned")
                .containsExactly("example-project#1 Opening[open=false, notPlanned=true]");
    }

    @Test
    void aClosedIssueMovedToAnOpenStateIsReopenedWithItsStatus() {
        // Harm: an issue moved back to work that stays closed on GitHub, so the board reads it
        // closed again and the person's move is lost.
        final ItemView closed = item("PL1", "example-project", 1, false, "", state("Backlog"));
        final ItemView unstated = item("PL2", "example-project", 2, false, "", Map.of());
        final GitHub github = new GitHub().holding(closed, unstated);

        github.writer()
                .save(
                        snapshot(closed, unstated),
                        SHOWN,
                        new Writes(ordered("PL1", "Idea", "PL2", "Backlog"), Map.of()));

        assertThat(github.mutations)
                .as("both Status values written")
                .containsExactly(
                        List.of(
                                FieldValue.option("PVTI_PL1", "F_Status", "O_Idea"),
                                FieldValue.option("PVTI_PL2", "F_Status", "O_Backlog")));
        assertThat(github.updates)
                .as("both reopened")
                .containsExactly(
                        "example-project#1 Opening[open=true, notPlanned=false]",
                        "example-project#2 Opening[open=true, notPlanned=false]");
    }

    @Test
    void aClosedStoryWithoutAStatusWhoseIssuesAreFinishedIsReopenedWhenMovedBackToWork() {
        // Harm: a story a person takes up again, closed on GitHub and shown Finished by its
        // issues, left closed while it is in work, because its empty Status was read as the
        // workflow's first state.
        final ItemView open = storyItem("ST1", 10, List.of("I_PL1"));
        final ItemView closed =
                new ItemView(
                        open.itemId(),
                        open.issueId(),
                        open.repository(),
                        open.number(),
                        open.title(),
                        open.body(),
                        false,
                        "completed",
                        open.fields(),
                        open.labels(),
                        open.parentIssueId(),
                        open.subIssueIds(),
                        open.blockedByIds(),
                        open.link(),
                        open.updatedAt(),
                        open.archived());
        final ItemView done = item("PL1", "example-project", 1, false, "", state("Finished"));
        final GitHub github = new GitHub().holding(closed, done);
        final Snapshot current =
                new Snapshot(
                        new Board(
                                SHOWN,
                                WORKFLOW,
                                List.of(
                                        new Story(
                                                "ST1",
                                                closed.title(),
                                                "Finished",
                                                "High",
                                                null,
                                                List.of("PL1"),
                                                "",
                                                closed.link())),
                                List.of(issue(done)),
                                new Work(Map.of(), List.of()),
                                CONFIG.repositories()),
                        Map.of("ST1", closed, "PL1", done),
                        CONFIG);

        github.writer().save(current, SHOWN, new Writes(Map.of(), Map.of("ST1", "Backlog")));

        assertThat(github.updates)
                .as("reopened, as it is moved out of the state it was shown in")
                .containsExactly("example-project#10 Opening[open=true, notPlanned=false]");
    }

    @Test
    void aStageResultOnAnIssueGitHubClosedIsRefusedAndLeavesItClosed() {
        // Harm: a run's stage result on an issue a merge closed while it was in work reopens
        // the issue nobody asked to reopen.
        final ItemView closed = item("PL1", "example-project", 1, false, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(closed);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(closed),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .as("it stands closed, not in the state the stage completes")
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(github.mutations).as("nothing written").isEmpty();
        assertThat(github.updates).as("never reopened").isEmpty();
    }

    @Test
    void aStoryFinishedOverRejectedIssuesOnlyOrNoneIsSavedWhole() {
        // Harm: a story's Finished that the preview passed failing in the store afterwards, so
        // the person's confirmed move is never saved.
        final ItemView none = storyItem("ST1", 10, List.of());
        final ItemView rejected = storyItem("ST2", 11, List.of("I_PL1", "I_PL2"));
        final ItemView first = item("PL1", "example-project", 1, false, "", state("Rejected"));
        final ItemView second = item("PL2", "example-project", 2, false, "", state("Rejected"));
        final GitHub github = new GitHub().holding(none, rejected, first, second);

        github.writer()
                .save(
                        snapshot(none, rejected, first, second),
                        SHOWN,
                        new Writes(Map.of(), ordered("ST1", "Finished", "ST2", "Finished")));

        assertThat(github.mutations).as("no Status written").isEmpty();
        assertThat(github.updates)
                .as("each story closed as done")
                .containsExactlyInAnyOrder(
                        "example-project#10 Opening[open=false, notPlanned=false]",
                        "example-project#11 Opening[open=false, notPlanned=false]");
    }

    /** A story's item, open, of the issue type Story, with the sub-issues given by node id. */
    private static ItemView storyItem(
            final String id, final int number, final List<String> subIssueIds) {
        return new ItemView(
                        "PVTI_" + id,
                        "I_" + id,
                        "example-project",
                        number,
                        "A story",
                        "",
                        true,
                        null,
                        Map.of(),
                        List.of(),
                        null,
                        subIssueIds,
                        List.of(),
                        URI.create(
                                "https://github.com/"
                                        + OWNER
                                        + "/example-project/issues/"
                                        + number),
                        Instant.EPOCH,
                        false)
                .typed(IssueMapping.STORY_TYPE);
    }

    @Test
    void aCloseThatFailsOnceOnGitHubsSideIsAskedAgainBeforeItIsRefused() {
        // Harm: one passing failure of GitHub's answers PartlySaved, and the person's move of a
        // story with many issues stops halfway.
        final ItemView open = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final GitHub github = new GitHub().holding(open);
        github.updateFailure = GitHubExceptions.refused(502, "bad gateway");
        github.failsOnce = true;

        github.writer()
                .save(snapshot(open), SHOWN, new Writes(ordered("PL1", "Finished"), Map.of()));

        assertThat(github.updates)
                .as("the issue closed")
                .containsExactly("example-project#1 Opening[open=false, notPlanned=false]");
        assertThat(github.calls.stream().filter("updateIssue"::equals).count())
                .as("asks to close it")
                .isEqualTo(2);
    }

    @Test
    void aWriteIsTriedAsOftenAsTheRetriesFactorSaysAfterTheLastBackoffOnceTheListEnds() {
        // Harm: a retries factor above 1.0 ending a save in an internal error at its third try,
        // since the backoff names waits for two tries only, or a slow network given no more tries.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Idea"));
        final GitHub github = new GitHub().holding(one);
        for (int failing = 0; failing < 4; failing++) {
            github.answering(
                    values -> {
                        throw GitHubExceptions.refused(502, "bad gateway");
                    });
        }

        github.writer(github, LimitFactors.parse("retries=2.0"))
                .save(snapshot(one), SHOWN, new Writes(ordered("PL1", "Backlog"), Map.of()));

        assertThat(github.mutations)
                .as("the mutation asked five times: the fifth of six tries answered")
                .hasSize(5);
        assertThat(github.waits)
                .as("each backoff twice as long, the last one standing for the later tries")
                .containsExactly(
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10));
    }

    @Test
    void aCloseGitHubRefusedAsRateLimitedIsAskedAgainAfterTheWaitItAskedFor() {
        // Harm: a story's Finished stopped by GitHub's limit on writes in a burst, answered as
        // partly saved though GitHub only asked for a wait.
        final ItemView open = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final GitHub github = new GitHub().holding(open);
        github.updateFailure =
                GitHubExceptions.rateLimited(403, "secondary rate limit", Duration.ofSeconds(3));
        github.failsOnce = true;

        github.writer()
                .save(snapshot(open), SHOWN, new Writes(ordered("PL1", "Finished"), Map.of()));

        assertThat(github.updates).as("the issue closed").hasSize(1);
        assertThat(github.waits).as("the waits").containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void theSixthWriteOfTwoSavesMadeAtOnceWaitsItsSecond() {
        // Harm: saves made side by side each starting a burst of five writes of its own, which
        // draws GitHub's limit on writes in a burst.
        final List<ItemView> items = new ArrayList<>();
        for (int n = 1; n <= 6; n++) {
            items.add(item("PL" + n, "example-project", n, true, "", state("Backlog")));
        }
        final GitHub github = new GitHub().holding(items.toArray(ItemView[]::new));
        final FieldsBoardWriter writer = github.writer();
        final Snapshot board = snapshot(items.toArray(ItemView[]::new));

        writer.save(
                board,
                SHOWN,
                new Writes(
                        ordered("PL1", "Finished", "PL2", "Finished", "PL3", "Finished"),
                        Map.of()));
        writer.save(
                board,
                SHOWN,
                new Writes(
                        ordered("PL4", "Finished", "PL5", "Finished", "PL6", "Finished"),
                        Map.of()));

        assertThat(github.updates).as("the issues closed by both saves").hasSize(6);
        assertThat(github.waits)
                .as("the waits: the sixth write of both saves together waits its spacing")
                .containsExactly(WritePace.SPACING);
    }

    @Test
    void aNewIssueMadeRightAfterAMoveTakesItsTurnsInTheSameBurst() {
        // Harm: an issue made beside a move starting a burst of writes of its own, which draws
        // GitHub's limit on writes in a burst.
        final List<ItemView> items = new ArrayList<>();
        final Map<String, String> moves = new LinkedHashMap<>();
        for (int n = 1; n <= WritePace.BURST; n++) {
            items.add(item("PL" + n, "example-project", n, true, "", state("Backlog")));
            moves.put("PL" + n, "Finished");
        }
        final GitHub github = new GitHub().holding(items.toArray(ItemView[]::new));
        final FieldsBoardWriter writer = github.writer();
        final Snapshot board = snapshot(items.toArray(ItemView[]::new));

        writer.save(board, SHOWN, new Writes(moves, Map.of()));
        writer.createIssue(board, SHOWN, newIssue("example-lib", "A new thing"));

        assertThat(github.updates).as("the issues the move closed").hasSize(WritePace.BURST);
        assertThat(github.waits)
                .as(
                        "the new issue's writes, each a spacing after the last once the move spent the burst")
                .isNotEmpty()
                .containsOnly(WritePace.SPACING);
    }

    @Test
    void aWaitGitHubAskedOfOneSaveHoldsTheNextSave() {
        // Harm: another save writing at once while GitHub asked the token to wait, so it is
        // refused as rate limited too and the window is told the move was not saved.
        final ItemView first = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final ItemView second = item("PL2", "example-project", 2, true, "", state("Backlog"));
        final GitHub github = new GitHub().holding(first, second);
        final FieldsBoardWriter writer = github.writer();
        final Snapshot board = snapshot(first, second);
        final Duration asked = Duration.ofSeconds(3);
        github.updateFailure = GitHubExceptions.rateLimited(403, "secondary rate limit", asked);

        assertThatThrownBy(
                        () ->
                                writer.save(
                                        board,
                                        SHOWN,
                                        new Writes(ordered("PL1", "Finished"), Map.of())))
                .as("a save GitHub refuses as rate limited at every try")
                .isInstanceOf(StoreException.class);
        final List<Duration> before = List.copyOf(github.waits);
        github.updateFailure = null;
        writer.save(board, SHOWN, new Writes(ordered("PL2", "Finished"), Map.of()));

        assertThat(before)
                .as("the first save's waits between its tries")
                .containsExactly(asked, asked);
        assertThat(github.waits.subList(before.size(), github.waits.size()))
                .as("the next save's first write waits out the wait GitHub asked for")
                .containsExactly(asked);
        assertThat(github.updates).as("the second save's close").hasSize(1);
    }

    @Test
    void theWritesOfALargeMoveStandASecondApartAfterTheFirstFew() {
        // Harm: a story of many issues moved in one burst of writes, which GitHub's limit on
        // writes in a burst refuses midway.
        final List<ItemView> items = new ArrayList<>();
        final Map<String, String> moves = new LinkedHashMap<>();
        for (int n = 1; n <= 8; n++) {
            items.add(item("PL" + n, "example-project", n, true, "", state("Backlog")));
            moves.put("PL" + n, "Finished");
        }
        final GitHub github = new GitHub().holding(items.toArray(ItemView[]::new));

        github.writer()
                .save(snapshot(items.toArray(ItemView[]::new)), SHOWN, new Writes(moves, Map.of()));

        assertThat(github.updates).as("the issues closed").hasSize(8);
        // Eight closes and no values: the first five go at once, the other three wait.
        assertThat(github.waits)
                .as("the waits between writes")
                .containsExactly(WritePace.SPACING, WritePace.SPACING, WritePace.SPACING);
    }

    @Test
    void aCloseGitHubRefusesIsNotAskedAgain() {
        // Harm: a refusal asked again, which only repeats it and delays the person's answer.
        final ItemView open = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final GitHub github = new GitHub().holding(open);
        github.updateFailure = GitHubExceptions.refused(422, "Validation Failed");

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(open),
                                                SHOWN,
                                                new Writes(ordered("PL1", "Finished"), Map.of())))
                .as("a close GitHub refused")
                .isInstanceOf(StoreException.class);
        assertThat(github.calls.stream().filter("updateIssue"::equals).count())
                .as("asks to close it")
                .isEqualTo(1);
    }

    @Test
    void aFieldOrOptionTheProjectLacksIsRefusedBeforeAnythingIsWritten() {
        // Harm: a write that finds an option missing goes on with a part, or sets the field's
        // options anew, which clears every item's value of that field.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Idea"));
        final GitHub github = new GitHub().holding(one);
        final Map<String, Map<String, String>> fewer = new LinkedHashMap<>(OPTION_IDS);
        fewer.put(FieldNames.WORKER, Map.of("Agent", "O_Agent"));
        final ProjectConfig lacking =
                new ProjectConfig(
                        CONFIG.forge(),
                        CONFIG.project(),
                        CONFIG.commit(),
                        WORKFLOW,
                        CONFIG.work(),
                        CONFIG.repositories(),
                        FIELD_IDS,
                        fewer,
                        "PVT_7");
        final Snapshot current = snapshot(one);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                new Snapshot(
                                                        current.board(), current.items(), lacking),
                                                SHOWN,
                                                new Writes(
                                                        Map.of("PL1", "Backlog"),
                                                        Map.of(),
                                                        Map.of(
                                                                "PL1",
                                                                org.fuin.porelo.planner.store.Worker
                                                                        .MANUAL))))
                .as("a worker the project's field has no option for")
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(github.calls).as("the calls made").isEmpty();
    }

    @Test
    void aStageResultNamingThePlanLeavesAcceptanceByteForByteAndAddsOneStageComment() {
        // Harm: a stage's result erases or reflows what a person wrote in a section the stage
        // does not name, or its course is not kept on the issue.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        final String written = github.bodies.get(1);
        assertThat(written).as("the plan written").contains("- new step");
        assertThat(written.substring(written.indexOf("## Acceptance")))
                .as("the section the stage does not name")
                .isEqualTo(BODY.substring(BODY.indexOf("## Acceptance")));
        assertThat(github.comments).as("the stage comments written").hasSize(1);
        assertThat(github.comments.get(0))
                .as("the comment's marker")
                .startsWith(StageComment.MARKER);
    }

    @Test
    void aStageResultReadsItsStageCommentsBeforeTheReadItIsJudgedBy() {
        // Harm: an edit made directly in GitHub while the result's comments are read lands after
        // the version check and is written over.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        final Set<String> writes =
                Set.of(
                        "updateIssue",
                        "setFieldValues",
                        "createIssueComment",
                        "addProjectItems",
                        "addSubIssue",
                        "createIssue");
        int first = 0;
        while (first < github.calls.size() && !writes.contains(github.calls.get(first))) {
            first++;
        }
        final List<String> before = github.calls.subList(0, first);
        assertThat(before)
                .as("GitHub's calls before the first write: the targets' read is the last")
                .contains("issueComments")
                .endsWith("issueBoard");
    }

    @Test
    void aStageResultForABodyWhoseSectionsAreUnclearSavesEverythingButTheBody() {
        // Harm: a person's sections deleted by a write into a body the planner cannot split, or
        // the stage's state, fields and comment lost along with the body.
        final String unclear =
                "Covers.\n\n## Plan\n\n```\nleft open\n\n## Acceptance\n\n- kept\n\n"
                        + "```java\ncode\n```\n";
        final ItemView one = item("PL1", "example-project", 1, true, unclear, state("Analysis"));
        final GitHub github = new GitHub().holding(one);

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        assertThat(github.bodies).as("the bodies written").isEmpty();
        assertThat(github.mutations)
                .as("the state and size saved")
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains(
                        FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog"),
                        FieldValue.option("PVTI_PL1", "F_Size", "O_S"),
                        FieldValue.number("PVTI_PL1", "F_Stages", 1));
        assertThat(github.comments).as("the stage comment written").hasSize(1);
    }

    @Test
    void aStageResultAddsMinutesToTheFieldsWithoutKeepingItsFiguresInTheComment() throws Exception {
        // Harm: a stage's figures kept twice, in the comment and the fields, where they can differ.
        final Map<String, String> fields = new LinkedHashMap<>(state("Analysis"));
        fields.put(FieldNames.MINUTES, "10");
        fields.put(FieldNames.VM_MINUTES, "2");
        final ItemView one = item("PL1", "example-project", 1, true, BODY, fields);
        final GitHub github = new GitHub().holding(one);
        github.standing.add(
                """
                <!-- porelo:stage -->
                implement: done
                ```json
                {"key":"task-1:1","stage":"implement","result":"done","actor":"agent",
                 "model":"a-model","effort":"high","wall_min":50,"vm_min":20,"tokens":{},
                 "base":{"wall_min":5,"vm_min":1,"tokens":0,"stages":0}}
                ```
                """);

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        assertThat(github.values.get("PVTI_PL1"))
                .as("the stage's minutes added to the project fields")
                .containsEntry("F_Minutes", FieldValue.number("PVTI_PL1", "F_Minutes", 14.5))
                .containsEntry("F_VM_minutes", FieldValue.number("PVTI_PL1", "F_VM_minutes", 3.5));
        assertThat(github.comments).as("one stage comment").hasSize(1);
        final String comment = github.comments.get(0);
        final JsonNode written =
                new ObjectMapper()
                        .readTree(
                                comment.substring(
                                        comment.indexOf("```json\n") + "```json\n".length(),
                                        comment.lastIndexOf("```")));
        assertThat(written.has("wall_min") || written.has("vm_min") || written.has("tokens"))
                .as("the comment carries no stage figures")
                .isFalse();
        for (final Map.Entry<String, Double> field :
                Map.of("wall_min", 10.0, "vm_min", 2.0, "tokens", 0.0, "stages", 0.0).entrySet()) {
            final JsonNode base = written.path("base").path(field.getKey());
            assertThat(base.isNumber()).as("the numeric base for %s", field.getKey()).isTrue();
            assertThat(base.asDouble())
                    .as("%s before the stage was added", field.getKey())
                    .isEqualTo(field.getValue());
        }
        assertThat(written.path("key").asText())
                .as("the stage key for duplicate detection")
                .isEqualTo("task-1:2");
        assertThat(written.path("stage").asText()).as("the stage").isEqualTo("implement");
        assertThat(written.path("result").asText()).as("the stage result").isEqualTo("done");

        final IssueMapping.Mapped mapped =
                IssueMapping.read(
                        List.of(asGitHubHolds(one, github)),
                        CONFIG,
                        states -> states.isEmpty() ? null : states.get(0));
        assertThat(mapped.issues())
                .as("the board reads Minutes from the field despite differing comment figures")
                .extracting(Issue::minutes, Issue::warnings)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(14.5, List.of()));
        assertThat(mapped.faults()).as("no fault over comment figures").isEmpty();
    }

    @Test
    void aStageAddsItsFiguresToTheFieldsAsReadJustBeforeWithItsState() {
        // Harm: a stage's minutes lost, or a value a person set replaced instead of added to.
        final Map<String, String> fields = new LinkedHashMap<>(state("Analysis"));
        fields.put(FieldNames.MINUTES, "10");
        fields.put(FieldNames.STAGES, "2");
        final ItemView one = item("PL1", "example-project", 1, true, BODY, fields);
        final GitHub github = new GitHub().holding(one);

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        assertThat(github.mutations).as("the mutations sent").hasSize(1);
        assertThat(github.mutations.get(0))
                .as("the state and the sums in one mutation")
                .contains(
                        FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog"),
                        FieldValue.number("PVTI_PL1", "F_Minutes", 14.5),
                        FieldValue.number("PVTI_PL1", "F_VM_minutes", 1.5),
                        FieldValue.number("PVTI_PL1", "F_Tokens", 300),
                        FieldValue.number("PVTI_PL1", "F_Stages", 3));
    }

    @Test
    void aStageOverASummedFieldOutOfRangeOrWithoutANumberIsRefusedNamingFieldAndIssue() {
        // Harm: a stage's figures added to a value the board cannot read and shows as none, or
        // summed past what the board reads, so a person's entry is written over with a sum
        // nobody can trust.
        final List<Map.Entry<String, String>> bad =
                List.of(
                        Map.entry(FieldNames.MINUTES, "-5"),
                        Map.entry(FieldNames.MINUTES, "1e9"),
                        Map.entry(FieldNames.MINUTES, "9999999"),
                        Map.entry(FieldNames.VM_MINUTES, "-1"),
                        Map.entry(FieldNames.VM_MINUTES, "9999999"),
                        Map.entry(FieldNames.STAGES, "-1"),
                        Map.entry(FieldNames.STAGES, "1.5"),
                        Map.entry(FieldNames.TOKENS, "1.5"),
                        Map.entry(FieldNames.TOKENS, "1e300"),
                        Map.entry(FieldNames.TOKENS, "NaN"));
        for (final Map.Entry<String, String> value : bad) {
            final String field = value.getKey();
            final Map<String, String> fields = new LinkedHashMap<>(state("Analysis"));
            fields.put(field, value.getValue());
            final ItemView one = item("PL1", "example-project", 1, true, BODY, fields);
            final GitHub github = new GitHub().holding(one);

            assertThatThrownBy(
                            () ->
                                    github.writer()
                                            .saveResult(
                                                    snapshot(one),
                                                    analysis(),
                                                    stage("task-1:2", "implement")))
                    .as("a %s of %s", field, value.getValue())
                    .isInstanceOfSatisfying(
                            StoreException.NotSavable.class,
                            refusal ->
                                    assertThat(refusal.subject())
                                            .as("the issue named")
                                            .isEqualTo("PL1"))
                    .hasMessageContaining(field);
            assertThat(github.mutations).as("nothing written over %s", field).isEmpty();
            assertThat(github.comments).as("no comment for %s", field).isEmpty();
        }
    }

    @Test
    void aStageWhoseCommentStandsIsNotRefusedOverABadFieldItWouldNotWrite() {
        // Harm: a result handed in again after a save cut off before its sums refused whole
        // because a person set one field by hand since, though the save leaves that field as it
        // is, so the other fields never get the stage.
        final Map<String, String> fields = new LinkedHashMap<>(state("Analysis"));
        fields.put(FieldNames.MINUTES, "-5");
        final ItemView one = item("PL1", "example-project", 1, true, BODY, fields);
        final GitHub github = new GitHub().holding(one);
        github.standing.add(
                StageComment.write(
                        stage("task-1:2", "implement"), new StageComment.Base(10, 0, 0, 0)));

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        assertThat(github.comments).as("no stage comment written").isEmpty();
        assertThat(github.values.get("PVTI_PL1"))
                .as(
                        "the fields still at the comment's base take the stage, the one set by hand not")
                .doesNotContainKey("F_Minutes")
                .containsEntry("F_VM_minutes", FieldValue.number("PVTI_PL1", "F_VM_minutes", 1.5))
                .containsEntry("F_Tokens", FieldValue.number("PVTI_PL1", "F_Tokens", 300))
                .containsEntry("F_Stages", FieldValue.number("PVTI_PL1", "F_Stages", 1));
    }

    @Test
    void aStageWhoseCommentStandsIsRefusedOverASumPastItsBoundItWouldStillWrite() {
        // Harm: a result handed in again writing a sum past the bound the board reads, to a
        // field still waiting for the stage, which the board then counts as none.
        final Map<String, String> fields = new LinkedHashMap<>(state("Analysis"));
        fields.put(FieldNames.MINUTES, "9999999");
        final ItemView one = item("PL1", "example-project", 1, true, BODY, fields);
        final GitHub github = new GitHub().holding(one);
        github.standing.add(
                StageComment.write(
                        stage("task-1:2", "implement"), new StageComment.Base(9_999_999, 0, 0, 0)));

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining(FieldNames.MINUTES);
        assertThat(github.mutations).as("nothing written").isEmpty();
        assertThat(github.comments).as("no stage comment written").isEmpty();
    }

    @Test
    void aStageWhoseCommentStandsAlreadyIsNeitherWrittenNorAddedAgain() {
        // Harm: a stage handed in twice counted twice in the issue's sums and shown twice.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.standing.add(StageComment.write(stage("task-1:2", "implement"), null));

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        assertThat(github.comments).as("the stage comments written").isEmpty();
        assertThat(github.mutations.get(0))
                .as("the values written")
                .extracting(FieldValue::fieldId)
                .doesNotContain("F_Minutes", "F_VM_minutes", "F_Tokens", "F_Stages");
    }

    /** The item {@code one} with the number fields GitHub holds for it now. */
    private static ItemView asGitHubHolds(final ItemView one, final GitHub github) {
        final Map<String, String> fields = new LinkedHashMap<>(one.fields());
        final Map<String, FieldValue> taken = github.values.getOrDefault(one.itemId(), Map.of());
        final List<IssueFieldValue> organization = github.organizationValues.get(one.number());
        if (organization != null && IssuePriority.held(organization) != null) {
            fields.put(FieldNames.PRIORITY, IssuePriority.held(organization));
        }
        for (final Map.Entry<String, String> name :
                Map.of(
                                "F_Minutes", FieldNames.MINUTES,
                                "F_VM_minutes", FieldNames.VM_MINUTES,
                                "F_Tokens", FieldNames.TOKENS,
                                "F_Stages", FieldNames.STAGES)
                        .entrySet()) {
            final FieldValue value = taken.get(name.getKey());
            if (value != null && value.number() != null) {
                // As the board reads a number: a whole one without its fraction.
                final double n = value.number();
                fields.put(
                        name.getValue(),
                        n == Math.rint(n) ? Long.toString((long) n) : Double.toString(n));
            }
        }
        for (final Map.Entry<String, String> name :
                Map.of("F_Status", FieldNames.STATUS, "F_Size", FieldNames.SIZE).entrySet()) {
            final FieldValue option = taken.get(name.getKey());
            if (option != null && option.optionId() != null) {
                fields.put(name.getValue(), option.optionId().substring("O_".length()));
            }
        }
        return new ItemView(
                one.itemId(),
                one.issueId(),
                one.repository(),
                one.number(),
                one.title(),
                one.body(),
                one.open(),
                one.stateReason(),
                fields,
                one.labels(),
                one.parentIssueId(),
                one.subIssueIds(),
                one.blockedByIds(),
                one.link(),
                one.updatedAt(),
                one.archived());
    }

    @Test
    void aStageCommentGitHubRefusesLeavesTheFieldsAsTheyWereAndTheResultHandedInAgainAddsOnce() {
        // Harm: a stage comment GitHub refuses leaves sums written without it, so the result
        // handed in again is refused for the state it already moved to, or adds its figures twice.
        final Map<String, String> fields = new LinkedHashMap<>(state("Analysis"));
        fields.put(FieldNames.MINUTES, "10");
        fields.put(FieldNames.STAGES, "2");
        final ItemView one = item("PL1", "example-project", 1, true, BODY, fields);
        final GitHub github = new GitHub().holding(one);
        github.failingComment = 1;
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .isInstanceOf(StoreException.class);
        assertThat(
                        github.calls.subList(
                                github.calls.indexOf("createIssueComment"), github.calls.size()))
                .as("no value written after the refused comment")
                .doesNotContain("setFieldValues");
        assertThat(github.values).as("no field written").isEmpty();
        final ItemView now = asGitHubHolds(one, github);
        github.holding(now);

        github.writer().saveResult(snapshot(now), analysis(), stage("task-1:2", "implement"));

        assertThat(github.values.get("PVTI_PL1"))
                .as("the sums as GitHub holds them after both tries")
                .containsEntry("F_Minutes", FieldValue.number("PVTI_PL1", "F_Minutes", 14.5))
                .containsEntry("F_VM_minutes", FieldValue.number("PVTI_PL1", "F_VM_minutes", 1.5))
                .containsEntry("F_Tokens", FieldValue.number("PVTI_PL1", "F_Tokens", 300))
                .containsEntry("F_Stages", FieldValue.number("PVTI_PL1", "F_Stages", 3));
        assertThat(github.comments).as("the stage comment written once").hasSize(1);
    }

    @Test
    void aStageHandedInAgainAfterGitHubTookItsSumsButNotItsStateAddsThemOnce() {
        // Harm: a save GitHub took in part, the sums but not the state, counts the stage twice
        // when the same result is handed in again, and the fields keep the double for good.
        final Map<String, String> fields = new LinkedHashMap<>(state("Analysis"));
        fields.put(FieldNames.MINUTES, "10");
        fields.put(FieldNames.STAGES, "2");
        final ItemView one = item("PL1", "example-project", 1, true, BODY, fields);
        final GitHub github =
                new GitHub()
                        .holding(one)
                        .answering(
                                values ->
                                        new Batch<>(
                                                values.stream()
                                                        .map(
                                                                value ->
                                                                        value.fieldId()
                                                                                        .equals(
                                                                                                "F_Status")
                                                                                ? Batch.Outcome
                                                                                        .<String>
                                                                                                refused(
                                                                                                        "refused")
                                                                                : Batch.Outcome.of(
                                                                                        value
                                                                                                .itemId()))
                                                        .toList(),
                                                null,
                                                GitHubExceptions.WITHOUT_A_WORD));
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .isInstanceOf(StoreException.PartlySaved.class);
        final ItemView now = asGitHubHolds(one, github);
        github.holding(now);

        github.writer().saveResult(snapshot(now), analysis(), stage("task-1:2", "implement"));

        assertThat(github.values.get("PVTI_PL1"))
                .as("the sums added once, the state written on the second try")
                .containsEntry("F_Minutes", FieldValue.number("PVTI_PL1", "F_Minutes", 14.5))
                .containsEntry("F_VM_minutes", FieldValue.number("PVTI_PL1", "F_VM_minutes", 1.5))
                .containsEntry("F_Tokens", FieldValue.number("PVTI_PL1", "F_Tokens", 300))
                .containsEntry("F_Stages", FieldValue.number("PVTI_PL1", "F_Stages", 3))
                .containsEntry("F_Status", FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog"));
        assertThat(github.comments).as("the stage comment written once").hasSize(1);
    }

    /** Answers a batch by saving only the values of the fields named. */
    private static Batch<String> savingOnly(
            final List<FieldValue> values, final Set<String> fieldIds) {
        return new Batch<>(
                values.stream()
                        .map(
                                value ->
                                        fieldIds.contains(value.fieldId())
                                                ? Batch.Outcome.of(value.itemId())
                                                : Batch.Outcome.<String>refused("refused"))
                        .toList(),
                null,
                GitHubExceptions.WITHOUT_A_WORD);
    }

    @Test
    void aStageHandedInAgainAfterGitHubTookOneSumAloneAddsEachSumOnce() {
        // Harm: a save GitHub took for one summed field only is judged as a whole when handed in
        // again, so that field gets the stage twice or the others never get it.
        final Map<String, String> fields = new LinkedHashMap<>(state("Analysis"));
        fields.put(FieldNames.MINUTES, "10");
        fields.put(FieldNames.STAGES, "2");
        final ItemView one = item("PL1", "example-project", 1, true, BODY, fields);
        final GitHub github =
                new GitHub()
                        .holding(one)
                        .answering(values -> savingOnly(values, Set.of("F_Minutes")));
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .isInstanceOf(StoreException.PartlySaved.class);
        final ItemView now = asGitHubHolds(one, github);
        github.holding(now);

        github.writer().saveResult(snapshot(now), analysis(), stage("task-1:2", "implement"));

        assertThat(github.values.get("PVTI_PL1"))
                .as("each sum added once")
                .containsEntry("F_Minutes", FieldValue.number("PVTI_PL1", "F_Minutes", 14.5))
                .containsEntry("F_VM_minutes", FieldValue.number("PVTI_PL1", "F_VM_minutes", 1.5))
                .containsEntry("F_Tokens", FieldValue.number("PVTI_PL1", "F_Tokens", 300))
                .containsEntry("F_Stages", FieldValue.number("PVTI_PL1", "F_Stages", 3));
    }

    @Test
    void aSumAPersonEditedBetweenTheTwoTriesIsLeftAsTheySetItAndTheOthersAreAdded() {
        // Harm: a value a person set by hand between a cut-off save and its hand-in again is
        // overwritten, or the stage is added to it on top of what they chose.
        final Map<String, String> fields = new LinkedHashMap<>(state("Analysis"));
        fields.put(FieldNames.MINUTES, "10");
        fields.put(FieldNames.STAGES, "2");
        final ItemView one = item("PL1", "example-project", 1, true, BODY, fields);
        final GitHub github =
                new GitHub().holding(one).answering(values -> savingOnly(values, Set.of()));
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .isInstanceOf(StoreException.class);
        final Map<String, String> edited = new LinkedHashMap<>(fields);
        edited.put(FieldNames.MINUTES, "12");
        final ItemView now = item("PL1", "example-project", 1, true, BODY, edited);
        github.holding(now);

        github.writer().saveResult(snapshot(now), analysis(), stage("task-1:2", "implement"));

        assertThat(github.mutations.get(github.mutations.size() - 1))
                .as("the person's Minutes not written")
                .extracting(FieldValue::fieldId)
                .doesNotContain("F_Minutes");
        assertThat(github.values.get("PVTI_PL1"))
                .as("the other three sums added")
                .containsEntry("F_VM_minutes", FieldValue.number("PVTI_PL1", "F_VM_minutes", 1.5))
                .containsEntry("F_Tokens", FieldValue.number("PVTI_PL1", "F_Tokens", 300))
                .containsEntry("F_Stages", FieldValue.number("PVTI_PL1", "F_Stages", 3));
    }

    @Test
    void aStageHandedInAgainAfterItsCommentStoodButNoFieldWasWrittenAddsItsFiguresThen() {
        // Harm: a stage whose comment was written but whose fields were refused is never counted,
        // as the comment found on the second try is taken for the figures added.
        final Map<String, String> fields = new LinkedHashMap<>(state("Analysis"));
        fields.put(FieldNames.MINUTES, "10");
        fields.put(FieldNames.STAGES, "2");
        final ItemView one = item("PL1", "example-project", 1, true, BODY, fields);
        final GitHub github =
                new GitHub()
                        .holding(one)
                        .answering(
                                values ->
                                        new Batch<>(
                                                values.stream()
                                                        .map(
                                                                value ->
                                                                        Batch.Outcome
                                                                                .<String>refused(
                                                                                        "refused"))
                                                        .toList(),
                                                null,
                                                GitHubExceptions.WITHOUT_A_WORD));
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .isInstanceOf(StoreException.class);
        assertThat(github.comments).as("the comment written before the fields").hasSize(1);
        final ItemView now = asGitHubHolds(one, github);
        github.holding(now);

        github.writer().saveResult(snapshot(now), analysis(), stage("task-1:2", "implement"));

        assertThat(github.values.get("PVTI_PL1"))
                .as("the sums added on the second try")
                .containsEntry("F_Minutes", FieldValue.number("PVTI_PL1", "F_Minutes", 14.5))
                .containsEntry("F_Stages", FieldValue.number("PVTI_PL1", "F_Stages", 3));
        assertThat(github.comments).as("no second comment").hasSize(1);
    }

    private static final Writes.Reason BUILT = new Writes.Reason("Finished", "Built as asked.");

    /** A story's item, as the board reads one by its issue type. */
    private static ItemView storyItem(final String id, final int number) {
        return item(id, "example-project", number, true, "", Map.of())
                .typed(IssueMapping.STORY_TYPE);
    }

    private static Writes closing(
            final Map<String, String> issues,
            final Map<String, Writes.Reason> issueReasons,
            final Map<String, Writes.Reason> storyReasons) {
        return new Writes(issues, Map.of(), Map.of(), Map.of(), issueReasons, storyReasons);
    }

    @Test
    void aReasonIsKeptAsAMarkedCommentOnEachItemAfterTheItemIsClosed() {
        // Harm: the person's sentence lost, or written to an item GitHub still shows open.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final ItemView story = storyItem("ST1", 2);
        final GitHub github = new GitHub().holding(one, story);

        github.writer()
                .save(
                        snapshot(one, story),
                        SHOWN,
                        closing(
                                Map.of("PL1", "Finished"),
                                Map.of("PL1", BUILT),
                                Map.of("ST1", BUILT)));

        assertThat(github.commented)
                .as("one comment on the issue and one on the story")
                .containsOnlyKeys(1, 2);
        for (final List<String> written : github.commented.values()) {
            assertThat(written)
                    .singleElement()
                    .asString()
                    .matches(
                            "<!-- porelo:closing Finished [0-9a-f]{12} -->\n"
                                    + "Finished: ` Built as asked. `");
        }
        assertThat(github.calls.indexOf("createIssueComment"))
                .as("after the close")
                .isGreaterThan(github.calls.lastIndexOf("updateIssue"));
    }

    @Test
    void aMoveWithoutAReasonWritesNoCommentAndReadsNone() {
        // Harm: an empty or stray comment on every close.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final GitHub github = new GitHub().holding(one);

        github.writer().save(snapshot(one), SHOWN, new Writes(Map.of("PL1", "Finished"), Map.of()));

        assertThat(github.calls).doesNotContain("createIssueComment", "issueComments");
    }

    @Test
    void aCommentThatFailsAfterTheCloseIsPartlySavedAndTheSameMoveAgainWritesWhatIsMissingOnce() {
        // Harm: a close answered as refused while it stands, or a reason never written, or
        // written twice, after a comment GitHub refused.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final ItemView story = storyItem("ST1", 2);
        final GitHub github = new GitHub().holding(one, story);
        // The issue's comment goes first and stands; the story's fails.
        github.failingComment = 2;

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(one, story),
                                                SHOWN,
                                                closing(
                                                        Map.of("PL1", "Finished"),
                                                        Map.of("PL1", BUILT),
                                                        Map.of("ST1", BUILT))))
                .isInstanceOf(StoreException.PartlySaved.class);
        assertThat(github.commented).as("the issue's comment stands").containsOnlyKeys(1);

        // The board now shows the issue closed, so the same story move again keeps the story's.
        final ItemView closed = item("PL1", "example-project", 1, false, "", state("Finished"));
        github.holding(closed);
        for (int again = 0; again < 2; again++) {
            github.writer()
                    .save(
                            snapshot(closed, story),
                            SHOWN,
                            closing(Map.of(), Map.of(), Map.of("ST1", BUILT)));
        }
        assertThat(github.commented.get(2)).as("written once, not twice").hasSize(1);
        assertThat(github.commented.get(1)).as("the issue's not again").hasSize(1);
    }

    @Test
    void aReasonForAnotherClosedStateIsNotTakenForTheOneStanding() {
        // Harm: a rejection's sentence skipped since a Finished one stands from before.
        final ItemView story = storyItem("ST1", 2);
        final GitHub github = new GitHub().holding(story);
        github.standing.add(ClosingComment.write(BUILT));

        github.writer()
                .save(
                        snapshot(story),
                        SHOWN,
                        closing(
                                Map.of(),
                                Map.of(),
                                Map.of("ST1", new Writes.Reason("Rejected", "Not wanted."))));

        assertThat(github.comments)
                .singleElement()
                .asString()
                .matches(
                        "<!-- porelo:closing Rejected [0-9a-f]{12} -->\nRejected: ` Not wanted. `");
    }

    @Test
    void aNewReasonAfterAReopenIsWrittenThoughOneForTheSameStateStands() {
        // Harm: closed with one reason, reopened and closed with another, the second dropped.
        final ItemView story = storyItem("ST1", 2);
        final GitHub github = new GitHub().holding(story);
        github.standing.add(ClosingComment.write(BUILT));

        github.writer()
                .save(
                        snapshot(story),
                        SHOWN,
                        closing(
                                Map.of(),
                                Map.of(),
                                Map.of("ST1", new Writes.Reason("Finished", "Built anew."))));

        assertThat(github.comments).singleElement().asString().endsWith("` Built anew. `");
    }

    @Test
    void anIssuesCommentThatFailedAfterTheCloseIsWrittenOnceWhenTheMoveIsSentAgain() {
        // Harm: the issue a story's move closed never gets its reason after the comment failed.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final ItemView story = storyItem("ST1", 2);
        final GitHub github = new GitHub().holding(one, story);
        github.failingComment = 1;

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(one, story),
                                                SHOWN,
                                                closing(
                                                        Map.of("PL1", "Finished"),
                                                        Map.of("PL1", BUILT),
                                                        Map.of("ST1", BUILT))))
                .isInstanceOf(StoreException.PartlySaved.class);
        assertThat(github.commented).as("nothing commented").isEmpty();

        // The issue stands closed now; the same move again hands its reason on for it.
        final ItemView closed = item("PL1", "example-project", 1, false, "", state("Finished"));
        github.holding(closed);
        for (int again = 0; again < 2; again++) {
            github.writer()
                    .save(
                            snapshot(closed, story),
                            SHOWN,
                            closing(Map.of(), Map.of("PL1", BUILT), Map.of("ST1", BUILT)));
        }
        assertThat(github.commented.get(1)).as("the issue's, written once").hasSize(1);
        assertThat(github.commented.get(2)).as("the story's, written once").hasSize(1);
    }

    @Test
    void aStageResultForAnIssueNoLongerInTheStateItCompletesIsRefusedBeforeWriting() {
        // Harm: a task's result moves an issue a person moved elsewhere while the task ran.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Backlog"));
        final GitHub github = new GitHub().holding(one);

        assertThatThrownBy(() -> github.writer().saveResult(snapshot(one), analysis(), null))
                .as("a result for an issue a person moved")
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(github.calls).as("the calls made").isEmpty();
    }

    /** The issue as GitHub holds it now, after a person changed it. */
    private static IssueOnBoard changed(final ItemView item, final UnaryOperator<BoardIssue> edit) {
        final IssueOnBoard read = onBoard(item);
        return new IssueOnBoard(
                edit.apply(read.issue()),
                read.itemId(),
                read.values(),
                new GitHubGraphQl.PageInfo(false, null),
                0);
    }

    private static BoardIssue issueWith(
            final BoardIssue issue,
            final String state,
            final @Nullable String parent,
            final List<String> labels,
            final IssueRefs subIssues,
            final IssueRefs blockedBy) {
        return new BoardIssue(
                issue.id(),
                issue.databaseId(),
                issue.number(),
                issue.title(),
                issue.body(),
                state,
                issue.stateReason(),
                issue.updatedAt(),
                issue.repository(),
                labels,
                parent,
                subIssues,
                blockedBy,
                new GitHubGraphQl.PageInfo(false, null),
                issue.issueType(),
                null,
                issue.fieldOptions(),
                0);
    }

    private static IssueRefs refs(final boolean more, final String... ids) {
        final List<IssueRef> nodes = new ArrayList<>();
        for (final String id : ids) {
            nodes.add(new IssueRef(id, 1L, 1, OWNER + "/example-project"));
        }
        // A list that goes on counts at least one more than this page lists.
        return new IssueRefs(nodes, new PageInfo(more, null), nodes.size() + (more ? 1 : 0));
    }

    private static ItemView closedAs(final ItemView item, final String reason) {
        return new ItemView(
                item.itemId(),
                item.issueId(),
                item.repository(),
                item.number(),
                item.title(),
                item.body(),
                false,
                reason,
                item.fields(),
                item.labels(),
                item.parentIssueId(),
                item.subIssueIds(),
                item.blockedByIds(),
                item.link(),
                item.updatedAt(),
                item.archived());
    }

    /**
     * Moves PL1, which the board read as {@code item}, while GitHub holds {@code now} for it, and
     * checks that the move is refused naming PL1 with nothing written.
     */
    private static void refusedAsChanged(
            final ItemView item, final @Nullable IssueOnBoard now, final String change) {
        final GitHub github = new GitHub();
        if (now != null) {
            github.held.put(item.issueId(), now);
        }

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(item),
                                                SHOWN,
                                                new Writes(Map.of("PL1", "Backlog"), Map.of())))
                .as("a save over an item whose " + change)
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale -> assertThat(stale.changed()).as("the item named").isEqualTo("PL1"));
        assertThat(github.mutations).as("the mutations sent").isEmpty();
        assertThat(github.updates).as("the issues updated").isEmpty();
    }

    private static ItemView idea() {
        return item("PL1", "example-project", 1, true, "", state("Idea"));
    }

    @Test
    void anItemWhoseSubIssuesAPersonChangedMeanwhileIsRefused() {
        // Harm: a story moved to Finished on the board as read while a person added an open
        // sub-issue to it on GitHub.
        final ItemView item = idea();
        refusedAsChanged(
                item,
                changed(
                        item,
                        issue ->
                                issueWith(
                                        issue,
                                        issue.state(),
                                        issue.parentId(),
                                        issue.labels(),
                                        refs(false, "I_new"),
                                        issue.blockedBy())),
                "sub-issues a person changed");
    }

    @Test
    void anItemWhoseLabelsAPersonChangedMeanwhileIsRefused() {
        // Harm: an item a person made a story or an issue by its label written as what it was.
        final ItemView item = idea();
        refusedAsChanged(
                item,
                changed(
                        item,
                        issue ->
                                issueWith(
                                        issue,
                                        issue.state(),
                                        issue.parentId(),
                                        List.of("story"),
                                        issue.subIssues(),
                                        issue.blockedBy())),
                "labels a person changed");
    }

    @Test
    void anItemWithMoreBlockersThanOnePageCarriesIsRefused() {
        // Harm: a blocker a person added beyond the page read missed, as the page shows the same
        // first blockers.
        final ItemView item = idea();
        refusedAsChanged(
                item,
                changed(
                        item,
                        issue ->
                                issueWith(
                                        issue,
                                        issue.state(),
                                        issue.parentId(),
                                        issue.labels(),
                                        issue.subIssues(),
                                        refs(true))),
                "blockers run past one page");
    }

    @Test
    void anItemWhoseParentAPersonChangedMeanwhileIsRefused() {
        // Harm: an issue a person put under another story moved as part of the story it left.
        final ItemView item = idea();
        refusedAsChanged(
                item,
                changed(
                        item,
                        issue ->
                                issueWith(
                                        issue,
                                        issue.state(),
                                        "I_ST9",
                                        issue.labels(),
                                        issue.subIssues(),
                                        issue.blockedBy())),
                "parent a person changed");
    }

    @Test
    void anItemWhoseBlockersAPersonChangedMeanwhileIsRefused() {
        // Harm: an issue a person marked blocked moved on as if nothing blocked it.
        final ItemView item = idea();
        refusedAsChanged(
                item,
                changed(
                        item,
                        issue ->
                                issueWith(
                                        issue,
                                        issue.state(),
                                        issue.parentId(),
                                        issue.labels(),
                                        issue.subIssues(),
                                        refs(false, "I_PL9"))),
                "blockers a person changed");
    }

    @Test
    void anItemAPersonClosedMeanwhileIsRefused() {
        // Harm: an issue a person closed on GitHub reopened by an older move from the window.
        final ItemView item = idea();
        refusedAsChanged(
                item,
                changed(
                        item,
                        issue ->
                                issueWith(
                                        issue,
                                        "CLOSED",
                                        issue.parentId(),
                                        issue.labels(),
                                        issue.subIssues(),
                                        issue.blockedBy())),
                "open state a person changed");
    }

    @Test
    void anItemGitHubNoLongerKnowsIsRefused() {
        // Harm: a move written for an issue a person deleted, or the write failing half done.
        refusedAsChanged(idea(), null, "issue GitHub no longer knows");
    }

    @Test
    void anItemAPersonRemovedFromTheProjectAndAddedAgainIsRefused() {
        // Harm: values written to an item id the project no longer holds, so the save fails half
        // done.
        final ItemView item = idea();
        final IssueOnBoard read = onBoard(item);
        refusedAsChanged(
                item,
                new IssueOnBoard(
                        read.issue(),
                        "PVTI_again",
                        read.values(),
                        new GitHubGraphQl.PageInfo(false, null),
                        0),
                "item a person removed and added again");
    }

    @Test
    void aPersonsEditWhileTheIssuesCommentsAreReadIsRefusedWithoutWriting() {
        // Harm: a person's edit made while the many pages of an issue's comments are read
        // overwritten by the stage's result.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final ItemView edited =
                item(
                        "PL1",
                        "example-project",
                        1,
                        true,
                        BODY + "A person's line.\n",
                        state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.whileCommentsAreRead = () -> github.holding(edited);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .as("a result over a body a person changed while the comments were read")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(github.mutations).as("the mutations sent").isEmpty();
        assertThat(github.updates).as("the issues updated").isEmpty();
        assertThat(github.comments).as("the comments written").isEmpty();
    }

    @Test
    void aBodyGitHubRefusesLeavesTheStateAndTheSumsUnwritten() {
        // Harm: an issue moved on with its sums added while the result's sections are missing,
        // so the result can never be handed in again.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.updateFailure = GitHubExceptions.refused(502, "bad gateway");

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .as("a result whose body GitHub refused")
                .isInstanceOf(StoreException.class)
                .isNotInstanceOf(StoreException.PartlySaved.class);
        assertThat(github.mutations).as("the mutations sent").isEmpty();
        assertThat(github.comments).as("the comments written").isEmpty();
    }

    @Test
    void aMoveBetweenTwoClosedStatesSetsTheReasonGitHubClosedTheIssueFor() {
        // Harm: an issue Rejected on the board listed by GitHub as completed, or a Finished one
        // as not planned.
        final ItemView finished =
                closedAs(
                        item("PL1", "example-project", 1, false, "", state("Finished")),
                        "completed");
        final ItemView rejected =
                closedAs(
                        item("PL2", "example-project", 2, false, "", state("Rejected")),
                        "not_planned");
        final GitHub github = new GitHub().holding(finished, rejected);

        github.writer()
                .save(
                        snapshot(finished, rejected),
                        SHOWN,
                        new Writes(ordered("PL1", "Rejected", "PL2", "Finished"), Map.of()));

        assertThat(github.updates)
                .as("the reasons written")
                .containsExactly(
                        "example-project#1 Opening[open=false, notPlanned=true]",
                        "example-project#2 Opening[open=false, notPlanned=false]");
    }

    @Test
    void aFirstCallGitHubTookWithoutAnsweringIsPartlySaved() {
        // Harm: a move GitHub may have saved answered as refused before writing, so the window
        // shows the board kept and makes the move again over it.
        final ItemView one = idea();
        final GitHub github =
                new GitHub()
                        .holding(one)
                        // Asked again after GitHub's failure, which holds every time.
                        .answering(
                                values -> {
                                    throw GitHubExceptions.unanswered("timed out");
                                })
                        .answering(
                                values -> {
                                    throw GitHubExceptions.unanswered("timed out");
                                })
                        .answering(
                                values -> {
                                    throw GitHubExceptions.unanswered("timed out");
                                });

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(one),
                                                SHOWN,
                                                new Writes(Map.of("PL1", "Backlog"), Map.of())))
                .as("a first call GitHub took without answering")
                .isInstanceOf(StoreException.PartlySaved.class);
    }

    @Test
    void aStageCommentOnALaterPageIsFoundAndNeitherWrittenNorAddedAgain() {
        // Harm: a stage handed in twice on an issue with many comments counted and shown twice.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.pagesBefore = 1;
        github.standing.add(StageComment.write(stage("task-1:2", "implement"), null));

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        assertThat(github.comments).as("the stage comments written").isEmpty();
        assertThat(github.mutations.get(0))
                .as("the values written")
                .extracting(FieldValue::fieldId)
                .doesNotContain("F_Minutes", "F_VM_minutes", "F_Tokens", "F_Stages");
    }

    @Test
    void aStageCommentBehindManyPagesIsFoundAndNeitherWrittenNorAddedAgain() {
        // Harm: a stage handed in again on an issue with many comments refused, or counted and
        // shown twice, because its comment stands past a count of pages.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.pagesBefore = 20;
        github.standing.add(StageComment.write(stage("task-1:2", "implement"), null));

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        assertThat(github.calls)
                .as("the pages of comments read, the stage's on the last")
                .filteredOn("issueComments"::equals)
                .hasSize(21);
        assertThat(github.comments).as("the stage comments written").isEmpty();
        assertThat(github.mutations.get(0))
                .as("the values written")
                .extracting(FieldValue::fieldId)
                .doesNotContain("F_Minutes", "F_VM_minutes", "F_Tokens", "F_Stages");
    }

    @Test
    void anIssueWhoseCommentsGitHubAnswersWithTheSamePageAgainIsRefusedWithoutWriting() {
        // Harm: an issue's comments read without end on a GitHub that answers the same page
        // again, the save never answered.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.cycle.add(new ProjectWrites.Comment(1, "The same comment"));

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .as("a result for an issue whose comments GitHub answers with the same page")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining(
                        "GitHub answered a page of the issue's comments that holds none not read"
                                + " before, so it is not known whether its stage stands");
        assertThat(github.calls)
                .as("the pages of comments read")
                .filteredOn("issueComments"::equals)
                .hasSize(2);
        assertThat(github.mutations).as("the mutations sent").isEmpty();
        assertThat(github.updates).as("the issues updated").isEmpty();
    }

    @Test
    void anIssueWhoseCommentsGitHubAnswersInACycleIsRefusedWithoutWriting() {
        // Harm: a save that never answers while an issue's comments are read without end, on a
        // GitHub that answers two pages in turn.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.cycle.add(new ProjectWrites.Comment(1, "A first comment"));
        github.cycle.add(new ProjectWrites.Comment(2, "A second comment"));

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .as("a result for an issue whose comments GitHub answers in a cycle")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("holds none not read before");
        assertThat(github.calls)
                .as("the pages of comments read, the first given again the last")
                .filteredOn("issueComments"::equals)
                .hasSize(3);
        assertThat(github.mutations).as("the mutations sent").isEmpty();
        assertThat(github.updates).as("the issues updated").isEmpty();
    }

    @Test
    void aStageCommentAfterPagesOfAlikeCommentsIsFound() {
        // Harm: a stage handed in again on an issue whose earlier comments all read "+1" refused,
        // as if GitHub had answered one page again.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.pagesBefore = 2;
        github.earlierBody = "+1";
        github.standing.add(StageComment.write(stage("task-1:2", "implement"), null));

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        assertThat(github.comments).as("the stage comments written").isEmpty();
        assertThat(github.calls)
                .as("the pages of comments read, the stage's on the last")
                .filteredOn("issueComments"::equals)
                .hasSize(3);
    }

    @Test
    void aResultForTwoIssuesWritesNoStageCommentAndAddsNoFigures() {
        // Harm: one stage's figures added to each of several issues, or its comment shown on one
        // of them as if the stage were its alone.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final ItemView two = item("PL2", "example-project", 2, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one, two);
        final StageResult.ForIssue first = analysis().issues().get(0);
        final StageResult both =
                new StageResult(
                        WorkRule.Result.ANALYSIS,
                        List.of(
                                first,
                                new StageResult.ForIssue(
                                        "PL2",
                                        "Analysis",
                                        "Backlog",
                                        "- new step",
                                        "S",
                                        "small",
                                        null,
                                        List.of(),
                                        List.of())));

        github.writer().saveResult(snapshot(one, two), both, stage("task-1:2", "implement"));

        assertThat(github.comments).as("the stage comments written").isEmpty();
        assertThat(github.mutations)
                .as("the values written")
                .flatExtracting(values -> values)
                .extracting(FieldValue::fieldId)
                .doesNotContain("F_Minutes", "F_VM_minutes", "F_Tokens", "F_Stages");
    }

    @Test
    void aReadinessCheckMovesEachIssueAloneAndKeepsWhyOneIsNotReadyInOneCommentOnce() {
        // Harm: a readiness check writes a size or a body it never gave, loses why an issue went
        // back, or adds the reason again each time the same save is sent.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final ItemView two = item("PL2", "example-project", 2, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one, two);
        final StageResult checked =
                new StageResult(
                        WorkRule.Result.READINESS,
                        List.of(),
                        List.of(
                                new StageResult.Verdict("PL1", "Analysis", "Backlog", null),
                                new StageResult.Verdict(
                                        "PL2",
                                        "Analysis",
                                        "Idea",
                                        "Not ready: it lacks a plan. None.")));

        for (int again = 0; again < 2; again++) {
            github.writer().saveResult(snapshot(one, two), checked, null);
        }

        assertThat(github.mutations)
                .as("the states written, nothing else")
                .flatExtracting(values -> values)
                .extracting(FieldValue::fieldId)
                .containsOnly("F_Status");
        assertThat(github.mutations)
                .as("each issue's own state: the ready one in the backlog, the other back")
                .flatExtracting(values -> values)
                .containsOnly(
                        FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog"),
                        FieldValue.option("PVTI_PL2", "F_Status", "O_Idea"));
        assertThat(github.bodies).as("no body written").isEmpty();
        assertThat(github.commented.get(1)).as("the ready issue's comments").isNull();
        assertThat(github.commented.get(2))
                .as("the reason, written once, under a marker of its own")
                .singleElement()
                .asString()
                .matches(
                        "<!-- porelo:readiness Idea [0-9a-f]{12} -->\n"
                                + "Idea: ` Not ready: it lacks a plan. None. `");
    }

    @Test
    void aReadinessCheckOfOneIssueWritesItsStageCommentAndItsState() {
        // Harm: a readiness check of one issue loses the stage its task spent, so the issue's
        // record shows no check, or its minutes counted in sums that count an agent's work only.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        final StageResult checked =
                new StageResult(
                        WorkRule.Result.READINESS,
                        List.of(),
                        List.of(new StageResult.Verdict("PL1", "Analysis", "Backlog", null)));

        github.writer().saveResult(snapshot(one), checked, stage("task-1:4", "check"));

        assertThat(github.comments).as("the stage comment written").hasSize(1);
        assertThat(github.mutations)
                .as("the state written, no figures")
                .flatExtracting(values -> values)
                .containsExactly(FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog"));
        assertThat(github.bodies).as("no body written").isEmpty();
    }

    @Test
    void aStageOfAKindNotMeasuredIsCommentedButAddsNoFigures() {
        // Harm: a pause's minutes and tokens added to the issue's sums, which count an agent's
        // work only.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:3", "wait"));

        assertThat(github.comments).as("the stage comments written").hasSize(1);
        assertThat(github.mutations.get(0))
                .as("the values written")
                .extracting(FieldValue::fieldId)
                .doesNotContain("F_Minutes", "F_VM_minutes", "F_Tokens", "F_Stages");
    }

    private static final String WRITTEN = "**What must be true.** It holds.\n";

    private static NewIssue newIssue(final String repository, final String title) {
        return new NewIssue(repository, title, "It holds.", "S");
    }

    private static NewIdea idea(final NewIssue... issues) {
        return new NewIdea("A story", "A gain", "A test", "High", List.of(issues));
    }

    /** The repositories' issues GitHub holds, each as its reference. */
    private static List<String> held(final GitHub github) {
        final List<String> held = new ArrayList<>();
        github.made.forEach(
                (repository, issues) ->
                        issues.forEach(f -> held.add(repository + "#" + f.issue().number())));
        return held;
    }

    @ParameterizedTest
    @ValueSource(strings = {"65536:", "65500:task-1"})
    void whatMustBeTrueBeyondABodyGitHubTakesIsRefusedInItsOwnWordsBeforeAnyCall(
            final String given) {
        // Harm: a new issue too long for GitHub refused as broken Markdown, or as an unreachable
        // store, so the person cannot tell what to shorten.
        final String[] parts = given.split(":", -1);
        final String task = parts[1].isEmpty() ? null : parts[1];
        final ItemView last =
                item(
                        "LB2",
                        "example-lib",
                        2,
                        true,
                        "",
                        ordered(FieldNames.STATUS, "Idea", FieldNames.RANK, "4"));
        final GitHub github = new GitHub().holding(last);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIssue(
                                                snapshot(last),
                                                SHOWN,
                                                new NewIssue(
                                                        "example-lib",
                                                        "A new thing",
                                                        "w".repeat(Integer.parseInt(parts[0])),
                                                        "S",
                                                        Worker.MANUAL,
                                                        task)))
                .as("what must be true of %s characters, task %s", parts[0], task)
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("longer than the 65536 characters a GitHub issue's body")
                .satisfies(
                        refused ->
                                assertThat(((StoreException.NotSavable) refused).field())
                                        .as("the field the window marks, so the refusal is final")
                                        .isEqualTo("mustBeTrue"));
        assertThat(github.made).as("no issue made").isEmpty();
    }

    @Test
    void anIssueIsMadeOnTheProjectWithItsFieldsAndAnsweredByItsReference() {
        // Harm: an issue the person added that never reaches the board, stands there without its
        // state, size, worker or rank, or is answered by an id nothing finds again.
        final ItemView last =
                item(
                        "LB2",
                        "example-lib",
                        2,
                        true,
                        "",
                        ordered(FieldNames.STATUS, "Idea", FieldNames.RANK, "4"));
        final ItemView high =
                item(
                        "LB3",
                        "example-lib",
                        3,
                        true,
                        "",
                        ordered(
                                FieldNames.STATUS,
                                "Idea",
                                FieldNames.PRIORITY,
                                "High",
                                FieldNames.RANK,
                                "30"));
        final GitHub github = new GitHub().holding(last, high);

        final String reference =
                github.writer()
                        .createIssue(
                                snapshot(last, high),
                                SHOWN,
                                new NewIssue(
                                        "example-lib",
                                        "A new thing",
                                        "It holds.",
                                        "S",
                                        Worker.MANUAL,
                                        "task-1"));

        assertThat(reference).as("the answer, its tracker reference").isEqualTo("example-lib#100");
        assertThat(github.calls)
                .as("the calls made: the look for an earlier try's issue, then the issue placed")
                .containsExactly(
                        "issuesCreatedSince", "createIssue", "addProjectItems", "setFieldValues");
        final ProjectWrites.Found issue = github.made.get("example-lib").get(0);
        assertThat(issue.title()).as("its title").isEqualTo("A new thing");
        assertThat(issue.body())
                .as("its body, what must be true and the task that proposed it")
                .isEqualTo(WRITTEN + "\nProposed by Porelo task `task-1`.\n");
        assertThat(github.added)
                .as("the issue added to the project")
                .containsExactly("I_example-lib_100");
        assertThat(github.mutations.get(0))
                .as("its field values, after the last rank of its priority and with no Id")
                .containsExactly(
                        FieldValue.option("PVTI_I_example-lib_100", "F_Status", "O_Idea"),
                        FieldValue.option("PVTI_I_example-lib_100", "F_Size", "O_S"),
                        FieldValue.option("PVTI_I_example-lib_100", "F_Worker", "O_Manual"),
                        FieldValue.number("PVTI_I_example-lib_100", "F_Rank", 5));
    }

    @Test
    void anIdeasStoryIsLabeledAndItsIssuesAreItsSubIssuesInTheIdeasOrder() {
        // Harm: a story the board cannot tell from an issue, or a story whose issues stand
        // apart from it or in another order than the person gave.
        final GitHub github = new GitHub();

        final BoardWriter.IdeaIds ids =
                github.writer()
                        .createIdea(
                                snapshot(),
                                SHOWN,
                                idea(
                                        newIssue("example-lib", "First"),
                                        newIssue("example-project", "Second")));

        assertThat(ids)
                .as("the story's and the issues' references")
                .isEqualTo(
                        new BoardWriter.IdeaIds(
                                "example-project#100",
                                List.of("example-lib#101", "example-project#102")));
        final ProjectWrites.Found story = github.made.get("example-project").get(0);
        assertThat(story.issue().type())
                .as("the story's issue type, and no label")
                .isEqualTo(IssueMapping.STORY_TYPE);
        assertThat(story.body())
                .as("the story's body")
                .isEqualTo("**What a user gains.** A gain\n\n**The test.** A test\n");
        assertThat(github.subIssues)
                .as("the issues made the story's sub-issues, in the idea's order")
                .containsExactly("example-project#100 < 9101", "example-project#100 < 9102");
        assertThat(github.added)
                .as("every item added to the project")
                .containsExactly(
                        "I_example-project_100", "I_example-lib_101", "I_example-project_102");
        assertThat(github.mutations)
                .as(
                        "the story follows its issues' state; each issue starts in Idea; each"
                                + " item's values written before the next item is made")
                .containsExactly(
                        List.of(FieldValue.number("PVTI_I_example-project_100", "F_Rank", 1)),
                        List.of(
                                FieldValue.option("PVTI_I_example-lib_101", "F_Status", "O_Idea"),
                                FieldValue.option("PVTI_I_example-lib_101", "F_Size", "O_S"),
                                FieldValue.option("PVTI_I_example-lib_101", "F_Worker", "O_Agent"),
                                FieldValue.number("PVTI_I_example-lib_101", "F_Rank", 1)),
                        List.of(
                                FieldValue.option(
                                        "PVTI_I_example-project_102", "F_Status", "O_Idea"),
                                FieldValue.option("PVTI_I_example-project_102", "F_Size", "O_S"),
                                FieldValue.option(
                                        "PVTI_I_example-project_102", "F_Worker", "O_Agent"),
                                FieldValue.number("PVTI_I_example-project_102", "F_Rank", 2)));
        assertThat(github.calls)
                .as("each item made, added, hung under the story and given its values in turn")
                .containsExactly(
                        "issuesCreatedSince",
                        "createIssue",
                        "addProjectItems",
                        "setFieldValues",
                        "issuesCreatedSince",
                        "createIssue",
                        "addProjectItems",
                        "addSubIssue",
                        "setFieldValues",
                        "createIssue",
                        "addProjectItems",
                        "addSubIssue",
                        "setFieldValues");
    }

    @Test
    void anIssueOrIdeaAgainstAnotherRevisionIsRefusedWithNothingMade() {
        // Harm: an issue added from a board the person no longer sees, made on GitHub anyway.
        final GitHub github = new GitHub();
        final Revision older = new Revision("older");

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIssue(
                                                snapshot(), older, newIssue("example-lib", "T")))
                .as("an issue against a revision the board no longer holds")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIdea(
                                                snapshot(),
                                                older,
                                                idea(newIssue("example-lib", "T"))))
                .as("an idea against a revision the board no longer holds")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(github.calls).as("the calls made").isEmpty();
    }

    @Test
    void anIssueGitHubMadeWithoutAnsweringIsFoundAndNotMadeTwice() {
        // Harm: a call that timed out after GitHub made the issue sent again, so the person
        // finds the issue twice.
        final GitHub github = new GitHub().creating("unanswered");
        github.make("example-lib", "A new thing", "**What must be true.** Another.\n", null);

        final String reference =
                github.writer()
                        .createIssue(snapshot(), SHOWN, newIssue("example-lib", "A new thing"));

        assertThat(held(github))
                .as("the issues GitHub holds: the person's other one and the new one, once")
                .containsExactly("example-lib#100", "example-lib#101");
        assertThat(reference)
                .as("the issue found taken as the new one")
                .isEqualTo("example-lib#101");
        assertThat(github.added)
                .as("the issue found added to the project")
                .containsExactly("I_example-lib_101");
    }

    @Test
    void anIssueWhoseTitleEndsInASpaceIsFoundAgainAfterAnUnansweredCall() {
        // Harm: a title GitHub kept without its trailing space never matched, so the issue it
        // made without answering is made a second time.
        final GitHub github = new GitHub().creating("unanswered");

        github.writer().createIssue(snapshot(), SHOWN, newIssue("example-lib", "A new thing "));

        assertThat(held(github)).as("the issues GitHub holds").containsExactly("example-lib#100");
    }

    @Test
    void anIssueGitHubDidNotMakeWithoutAnsweringIsMadeOnce() {
        // Harm: a lost call taken as made, so the window is told of an issue GitHub never made.
        final GitHub github = new GitHub().creating("lost");

        final String reference =
                github.writer()
                        .createIssue(snapshot(), SHOWN, newIssue("example-lib", "A new thing"));

        assertThat(held(github)).as("the issues GitHub holds").containsExactly("example-lib#100");
        assertThat(reference).as("the issue made once more").isEqualTo("example-lib#100");
        assertThat(github.calls)
                .as("the calls made")
                .containsExactly(
                        "issuesCreatedSince",
                        "createIssue",
                        "issuesCreatedSince",
                        "createIssue",
                        "addProjectItems",
                        "setFieldValues");
    }

    @Test
    void twoIssuesFoundAfterAnUnansweredCallStopTheWriteNamingBoth() {
        // Harm: one of two issues alike taken at a guess, the other left beside it unnamed.
        final GitHub github = new GitHub().creating("twice-unanswered");
        // One alike on each of two pages: the other issue and the first alike fill the first.
        github.make("example-lib", "Something else", WRITTEN, null);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIssue(
                                                snapshot(),
                                                SHOWN,
                                                newIssue("example-lib", "A new thing")))
                .as("a write that finds two issues alike")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly -> {
                            assertThat(partly.pushed())
                                    .as("the repository that holds them")
                                    .containsExactly("example-lib");
                            assertThat(partly.getMessage())
                                    .as("both named")
                                    .contains("example-lib#101, example-lib#102");
                        });
        assertThat(github.added).as("nothing added to the project").isEmpty();
    }

    @Test
    void aStoryWhoseIssueTypeGitHubDroppedStopsTheWriteNamingIt() {
        // Harm: issues made under a story GitHub holds as a plain issue, which the board shows
        // as no story.
        final GitHub github = new GitHub().creating("untyped");

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIdea(
                                                snapshot(),
                                                SHOWN,
                                                idea(newIssue("example-lib", "First"))))
                .as("an idea whose story GitHub made without its issue type")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.getMessage())
                                        .as("the story named")
                                        .contains("example-project#100"));
        assertThat(github.calls)
                .as("the calls made")
                .containsExactly("issuesCreatedSince", "createIssue");
    }

    @Test
    void anIdeaThatStopsAfterAPartIsPartlySavedNamingWhatStands() {
        // Harm: an idea of which GitHub holds a part answered as refused, so the person adds it
        // again beside that part, or with words that carry the token or the person's text.
        final GitHub github = new GitHub().creating("answer", "answer", "refused");

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIdea(
                                                snapshot(),
                                                SHOWN,
                                                idea(
                                                        newIssue("example-lib", "First"),
                                                        newIssue("example-project", "Second"))))
                .as("an idea whose second issue GitHub refused")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly -> {
                            assertThat(partly.pushed())
                                    .as("the repositories a part was made in")
                                    .containsExactly("example-project", "example-lib");
                            assertThat(partly.subject())
                                    .as("the repository refused")
                                    .isEqualTo("example-project");
                            assertThat(partly.getMessage())
                                    .as("what GitHub holds")
                                    .contains("example-project#100, example-lib#101")
                                    .doesNotContain(TOKEN)
                                    .doesNotContain("It holds")
                                    .doesNotContain("A gain");
                        });
    }

    @Test
    void anIssueAlikeThatStoodOnTheBoardBeforeIsNotTakenAsTheOneMadeWithoutAnswer() {
        // Harm: the person's earlier, finished issue of the same words taken as the new one, so
        // the new one GitHub made stands beside it off the project.
        final GitHub github = new GitHub().creating("unanswered");
        github.make("example-lib", "A new thing", WRITTEN, null);
        final ItemView earlier =
                item(
                        "LB9",
                        "example-lib",
                        100,
                        true,
                        WRITTEN,
                        Map.of(
                                FieldNames.STATUS,
                                "Idea",
                                FieldNames.SIZE,
                                "S",
                                FieldNames.WORKER,
                                "Agent",
                                FieldNames.PRIORITY,
                                "Medium",
                                FieldNames.RANK,
                                "1"));

        final String reference =
                github.writer()
                        .createIssue(
                                snapshot(earlier), SHOWN, newIssue("example-lib", "A new thing"));

        assertThat(reference)
                .as("the issue GitHub made in this write")
                .isEqualTo("example-lib#101");
        assertThat(held(github))
                .as("the issues GitHub holds")
                .containsExactly("example-lib#100", "example-lib#101");
    }

    /**
     * The project's items as the store reads them again after a write GitHub took a part of: each
     * issue added, with the values GitHub took and its parent.
     *
     * @param automatic the Status a project's own automation gives every item added, or null
     */
    private static ItemView[] onTheProject(final GitHub github, final @Nullable String automatic) {
        final Map<String, String> fieldNames = new LinkedHashMap<>();
        FIELD_IDS.forEach((name, id) -> fieldNames.put(id, name));
        final List<ItemView> items = new ArrayList<>();
        github.made.forEach(
                (repository, issues) -> {
                    for (final ProjectWrites.Found found : issues) {
                        final ProjectWrites.Created issue = found.issue();
                        if (!github.added.contains(issue.nodeId())) {
                            continue;
                        }
                        final Map<String, String> fields = new LinkedHashMap<>();
                        github.values
                                .getOrDefault("PVTI_" + issue.nodeId(), Map.of())
                                .forEach(
                                        (fieldId, value) -> {
                                            final String option = value.optionId();
                                            fields.put(
                                                    fieldNames.get(fieldId),
                                                    option != null
                                                            ? option.substring(2)
                                                            // As the board reads a whole number.
                                                            : String.valueOf(
                                                                    Math.round(
                                                                            Objects.requireNonNull(
                                                                                    value
                                                                                            .number()))));
                                        });
                        final String priority =
                                IssuePriority.held(
                                        github.organizationValues.getOrDefault(
                                                issue.number(), List.of()));
                        if (priority != null) {
                            fields.put(FieldNames.PRIORITY, priority);
                        }
                        if (automatic != null) {
                            fields.putIfAbsent(FieldNames.STATUS, automatic);
                        }
                        @Nullable String parent = null;
                        for (final String link : github.subIssues) {
                            if (link.endsWith(" < " + issue.databaseId())) {
                                final String story = link.substring(0, link.indexOf(' '));
                                parent = "I_" + story.replace('#', '_');
                            }
                        }
                        items.add(
                                new ItemView(
                                                "PVTI_" + issue.nodeId(),
                                                issue.nodeId(),
                                                repository,
                                                issue.number(),
                                                found.title(),
                                                found.body(),
                                                true,
                                                null,
                                                fields,
                                                List.of(),
                                                parent,
                                                List.of(),
                                                List.of(),
                                                URI.create(
                                                        "https://github.com/"
                                                                + OWNER
                                                                + "/"
                                                                + repository
                                                                + "/issues/"
                                                                + issue.number()),
                                                Instant.EPOCH,
                                                false)
                                        .typed(issue.type()));
                    }
                });
        return items.toArray(ItemView[]::new);
    }

    /**
     * GitHub that takes the values of a story and two issues, then fails on its side three times.
     */
    private static GitHub failingOnTheThirdIssuesValues() {
        final Function<List<FieldValue>, Batch<String>> failing =
                values -> {
                    throw GitHubExceptions.refused(502, "bad gateway");
                };
        return new GitHub()
                .answering(FieldsBoardWriterTest::saved)
                .answering(FieldsBoardWriterTest::saved)
                .answering(FieldsBoardWriterTest::saved)
                .answering(failing)
                .answering(failing)
                .answering(failing);
    }

    @Test
    void aRetriedCutOffCreateKeepsAPriorityTheTakenStoryHolds() {
        // Harm: a priority a person gave a story between a cut-off create and its retry written
        // back to the create's, as no other value the taken story holds is.
        final GitHub github = failingOnTheThirdIssuesValues();
        final NewIdea idea = threeIssues();
        assertThatThrownBy(() -> github.writer().createIdea(snapshot(), SHOWN, idea))
                .as("the first try cut off")
                .isInstanceOf(StoreException.PartlySaved.class);
        github.organizationValues.put(100, List.of(OrganizationFields.priority("Urgent")));

        github.writer().createIdea(snapshot(onTheProject(github, "Accepted")), SHOWN, idea);

        assertThat(github.organizationValues.get(100))
                .as("the person's Urgent kept on the story")
                .containsExactly(OrganizationFields.priority("Urgent"));
        assertThat(github.priorityUpdates).as("no priority written over it").isEmpty();
    }

    /** An idea of three issues, one in each repository. */
    private static NewIdea threeIssues() {
        return idea(
                newIssue("example-lib", "First"),
                newIssue("example-project", "Second"),
                newIssue("example-tool", "Third"));
    }

    /** A value of a number field as the fake GitHub took it. */
    private static @Nullable Double numberOf(
            final GitHub github, final String itemId, final String fieldId) {
        final FieldValue value = github.values.getOrDefault(itemId, Map.of()).get(fieldId);
        return value == null ? null : value.number();
    }

    @Test
    void aCreationCutOffMidwayIsFinishedBySendingItAgainWithNothingMadeTwice() {
        // Harm: a story's creation cut off midway that leaves issues on the project without
        // their Status or off it, a story without its values, and a second try that makes the
        // issues of the first one again or moves the ranks the first one gave.
        final GitHub github = failingOnTheThirdIssuesValues();
        final NewIdea idea = threeIssues();

        assertThatThrownBy(() -> github.writer().createIdea(snapshot(), SHOWN, idea))
                .as("a creation whose third issue's values GitHub failed to take")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.getMessage())
                                        .as("what GitHub holds")
                                        .contains(
                                                "example-project#100, example-lib#101,"
                                                        + " example-project#102, example-tool#103"));
        assertThat(github.waits)
                .as("the third issue's values asked again after each failure, then given up")
                .contains(Duration.ofSeconds(2), Duration.ofSeconds(5));
        assertThat(github.values.keySet())
                .as("each item before the third given its values before the next was made")
                .containsExactly(
                        "PVTI_I_example-project_100",
                        "PVTI_I_example-lib_101",
                        "PVTI_I_example-project_102");

        final int before = github.mutations.size();
        final BoardWriter.IdeaIds ids =
                github.writer().createIdea(snapshot(onTheProject(github, "Accepted")), SHOWN, idea);

        assertThat(ids)
                .as("the answer names the story and every issue")
                .isEqualTo(
                        new BoardWriter.IdeaIds(
                                "example-project#100",
                                List.of(
                                        "example-lib#101",
                                        "example-project#102",
                                        "example-tool#103")));
        assertThat(held(github))
                .as("the issues GitHub holds, none made twice")
                .containsExactlyInAnyOrder(
                        "example-project#100",
                        "example-project#102",
                        "example-lib#101",
                        "example-tool#103");
        assertThat(github.added)
                .as("every item added to the project once")
                .containsExactly(
                        "I_example-project_100",
                        "I_example-lib_101",
                        "I_example-project_102",
                        "I_example-tool_103");
        assertThat(github.subIssues)
                .as("every issue hung under the story once, in the idea's order")
                .containsExactly(
                        "example-project#100 < 9101",
                        "example-project#100 < 9102",
                        "example-project#100 < 9103");
        for (final String item :
                List.of(
                        "PVTI_I_example-lib_101",
                        "PVTI_I_example-project_102",
                        "PVTI_I_example-tool_103")) {
            assertThat(github.values.get(item))
                    .as("the Status of " + item)
                    .containsEntry("F_Status", FieldValue.option(item, "F_Status", "O_Idea"));
        }
        assertThat(github.values.get("PVTI_I_example-project_100"))
                .as("the story's values")
                .containsKeys("F_Rank")
                .doesNotContainKey("F_Status");
        assertThat(
                        List.of(
                                numberOf(github, "PVTI_I_example-project_100", "F_Rank"),
                                numberOf(github, "PVTI_I_example-lib_101", "F_Rank"),
                                numberOf(github, "PVTI_I_example-project_102", "F_Rank"),
                                numberOf(github, "PVTI_I_example-tool_103", "F_Rank")))
                .as("the ranks the first try gave kept, the third issue's after them")
                .containsExactly(1.0, 1.0, 2.0, 3.0);
        assertThat(github.mutations.subList(before, github.mutations.size()))
                .as("the second try writes only the values the third issue lacks")
                .hasSize(1)
                .allSatisfy(
                        values ->
                                assertThat(values)
                                        .allSatisfy(
                                                value ->
                                                        assertThat(value.itemId())
                                                                .isEqualTo(
                                                                        "PVTI_I_example-tool_103")));
    }

    @Test
    void aCutOffCreationWhoseStoryTheAutomationGaveIdeaMakesNothingTwiceWhenSentAgain() {
        // Harm: a project whose own automation gives every new item Idea makes the story
        // look whole, so the second try makes a second story and every issue again.
        final GitHub github = failingOnTheThirdIssuesValues();
        final NewIdea idea = threeIssues();
        assertThatThrownBy(() -> github.writer().createIdea(snapshot(), SHOWN, idea))
                .isInstanceOf(StoreException.PartlySaved.class);

        final BoardWriter.IdeaIds ids =
                github.writer().createIdea(snapshot(onTheProject(github, "Idea")), SHOWN, idea);

        assertThat(ids.story()).as("the story taken").isEqualTo("example-project#100");
        assertThat(held(github))
                .as("the issues GitHub holds, none made twice")
                .containsExactlyInAnyOrder(
                        "example-project#100",
                        "example-project#102",
                        "example-lib#101",
                        "example-tool#103");
        assertThat(github.subIssues).as("every issue hung under the story once").hasSize(3);
    }

    @Test
    void aCompleteStoryWhoseIssuesPriorityWasChangedIsNotTakenAsACutOffCreate() {
        // Harm: a story the person completed and then gave an issue another priority taken as an
        // earlier try's, its issue's priority written back over the person's.
        final GitHub github = new GitHub();
        final NewIdea idea = idea(newIssue("example-lib", "First"));
        github.writer().createIdea(snapshot(), SHOWN, idea);
        github.organizationValues.put(101, List.of(OrganizationFields.priority("Low")));

        final BoardWriter.IdeaIds ids =
                github.writer().createIdea(snapshot(onTheProject(github, null)), SHOWN, idea);

        assertThat(ids.story())
                .as("a complete story remains the person's own")
                .isEqualTo("example-project#102");
        assertThat(github.organizationValues.get(101))
                .as("the person's Low priority is preserved")
                .containsExactly(OrganizationFields.priority("Low"));
    }

    @Test
    void aSecondCompleteStoryIsMadeAnewAndItsNamesakesWorkedIssueKeepsItsState() {
        // Harm: a story the person made again with the same words taken as an earlier try's,
        // its namesake's issue moved back from the state the person worked it to.
        final GitHub github = new GitHub();
        final NewIdea idea = idea(newIssue("example-lib", "First"));
        github.writer().createIdea(snapshot(), SHOWN, idea);
        github.values
                .get("PVTI_I_example-lib_101")
                .put(
                        "F_Status",
                        FieldValue.option("PVTI_I_example-lib_101", "F_Status", "O_Analysis"));
        final int before = github.mutations.size();

        final BoardWriter.IdeaIds ids =
                github.writer().createIdea(snapshot(onTheProject(github, null)), SHOWN, idea);

        assertThat(ids)
                .as("a new story and issue")
                .isEqualTo(
                        new BoardWriter.IdeaIds("example-project#102", List.of("example-lib#103")));
        assertThat(github.values.get("PVTI_I_example-lib_101").get("F_Status"))
                .as("the namesake's issue in the state the person worked it to")
                .isEqualTo(FieldValue.option("PVTI_I_example-lib_101", "F_Status", "O_Analysis"));
        assertThat(github.mutations.subList(before, github.mutations.size()))
                .as("the second creation writes nothing to the first one's items")
                .allSatisfy(
                        values ->
                                assertThat(values)
                                        .noneSatisfy(
                                                value ->
                                                        assertThat(value.itemId())
                                                                .isIn(
                                                                        "PVTI_I_example-project_100",
                                                                        "PVTI_I_example-lib_101")));
        assertThat(github.subIssues)
                .as("the new issue hung under the new story")
                .containsExactly("example-project#100 < 9101", "example-project#102 < 9103");
    }

    @Test
    void anIssueAlikeUnderAnotherStoryIsNotTaken() {
        // Harm: an issue of another story, not yet given its Status, taken as the new one and
        // given the new one's values.
        final GitHub github = new GitHub();
        github.make("example-project", "Another story", "", IssueMapping.STORY_TYPE);
        github.make("example-lib", "A new thing", WRITTEN, null);
        github.added.add("I_example-project_100");
        github.added.add("I_example-lib_101");
        github.subIssues.add("example-project#100 < 9101");

        final String reference =
                github.writer()
                        .createIssue(
                                snapshot(onTheProject(github, null)),
                                SHOWN,
                                newIssue("example-lib", "A new thing"));

        assertThat(reference).as("a new issue made").isEqualTo("example-lib#102");
        assertThat(github.values)
                .as("the other story's issue left")
                .doesNotContainKey("PVTI_I_example-lib_101");
    }

    @Test
    void anIssueAlikeOlderThanADayIsNeverTaken() {
        // Harm: an issue the person made long before, off the project, taken as the new one.
        final GitHub github = new GitHub();
        github.makeAt("example-lib", "A new thing", WRITTEN, null, NOW.minus(Duration.ofHours(25)));

        final String reference =
                github.writer()
                        .createIssue(snapshot(), SHOWN, newIssue("example-lib", "A new thing"));

        assertThat(reference).as("a new issue made").isEqualTo("example-lib#101");
    }

    @Test
    void aCreateWritesTheRequestedStatusInItsFirstFieldWrite() {
        // Harm: a second save leaves a newly created issue half placed and gives two revisions.
        final GitHub github = new GitHub();
        final NewIssue issue =
                new NewIssue(
                        "example-lib",
                        "A new thing",
                        "It holds.",
                        "S",
                        Worker.AGENT,
                        null,
                        null,
                        null,
                        "Backlog");

        github.writer().createIssue(snapshot(), SHOWN, issue);

        assertThat(github.mutations).as("one field write for the issue").hasSize(1);
        assertThat(github.mutations.get(0))
                .as("the requested Status is the first value written")
                .first()
                .isEqualTo(FieldValue.option("PVTI_I_example-lib_100", "F_Status", "O_Backlog"));
        assertThat(held(github)).as("one issue made").containsExactly("example-lib#100");
    }

    @Test
    void aCreateSentAgainAfterItsIssueWasMovedKeepsThePersonsMove() {
        // Harm: a create sent again puts an issue the person has since moved back where it began.
        final GitHub github = new GitHub();
        github.make("example-project", "A story", "", IssueMapping.STORY_TYPE);
        github.added.add("I_example-project_100");
        final NewIssue issue =
                new NewIssue(
                        "example-lib",
                        "A new thing",
                        "It holds.",
                        "S",
                        Worker.AGENT,
                        null,
                        null,
                        "example-project#100",
                        "Backlog");
        final String first =
                github.writer().createIssue(snapshot(onTheProject(github, null)), SHOWN, issue);
        final String item = "PVTI_I_" + first.replace('#', '_');
        github.values.get(item).put("F_Status", FieldValue.option(item, "F_Status", "O_Analysis"));

        final String again =
                github.writer().createIssue(snapshot(onTheProject(github, null)), SHOWN, issue);

        assertThat(again).as("the create sent again takes the same issue").isEqualTo(first);
        assertThat(github.values.get(item))
                .as("the Status the person chose since")
                .containsEntry("F_Status", FieldValue.option(item, "F_Status", "O_Analysis"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Idea"})
    void aCutOffCreateRetriedInAStateFinishesTheSameIssueDespiteAnAutomaticFirstStatus(
            final String automatic) {
        // Harm: a retry duplicates an issue or preserves an automation's first state over its
        // requested placement.
        final GitHub github = new GitHub();
        github.make("example-project", "A story", "", IssueMapping.STORY_TYPE);
        github.added.add("I_example-project_100");
        final NewIssue issue =
                new NewIssue(
                        "example-lib",
                        "A new thing",
                        "It holds.",
                        "S",
                        Worker.AGENT,
                        null,
                        null,
                        "example-project#100",
                        "Backlog");
        github.answering(
                values -> {
                    throw GitHubExceptions.refused(422, "fields refused");
                });

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIssue(
                                                snapshot(onTheProject(github, null)), SHOWN, issue))
                .as("the first try stops after creation and linking, before fields")
                .isInstanceOf(StoreException.PartlySaved.class)
                .hasMessageContaining("fields refused");
        final Snapshot left =
                snapshot(onTheProject(github, automatic.isEmpty() ? null : automatic));
        final String reference = github.writer().createIssue(left, SHOWN, issue);

        assertThat(reference).as("the retry finishes the first issue").isEqualTo("example-lib#101");
        assertThat(held(github)).as("no second issue after retry").hasSize(2);
        assertThat(github.values.get("PVTI_I_example-lib_101"))
                .as("the requested Status replaces any automatic first Status")
                .containsEntry(
                        "F_Status",
                        FieldValue.option("PVTI_I_example-lib_101", "F_Status", "O_Backlog"));
        assertThat(github.subIssues).as("the existing story link is retained").hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Idea"})
    void aCreateInNoStoryCutOffAfterItsProjectAddIsFinishedOnARetryNotMadeTwice(
            final String automatic) {
        // Harm: a retry of a create in no story, cut off once its issue stood on the project,
        // makes a second issue and leaves the first on the board without its values.
        final GitHub github = new GitHub();
        final NewIssue issue = newIssue("example-lib", "A new thing");
        github.answering(
                values -> {
                    throw GitHubExceptions.refused(422, "fields refused");
                });

        assertThatThrownBy(() -> github.writer().createIssue(snapshot(), SHOWN, issue))
                .as("the first try stops after the project add, before the values")
                .isInstanceOf(StoreException.PartlySaved.class)
                .hasMessageContaining("fields refused");
        assertThat(github.added)
                .as("the first try added the issue to the project")
                .containsExactly("I_example-lib_100");

        final String reference =
                github.writer()
                        .createIssue(
                                snapshot(
                                        onTheProject(
                                                github, automatic.isEmpty() ? null : automatic)),
                                SHOWN,
                                issue);

        assertThat(reference).as("the retry finishes the first issue").isEqualTo("example-lib#100");
        assertThat(held(github)).as("no second issue").containsExactly("example-lib#100");
        assertThat(github.added)
                .as("the issue added to the project once")
                .containsExactly("I_example-lib_100");
        assertThat(github.values.get("PVTI_I_example-lib_100"))
                .as("the values the first try left out")
                .containsKeys("F_Status", "F_Size", "F_Worker", "F_Rank");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Idea"})
    void aCreateUnderAStoryCutOffBeforeLinkingIsFinishedOnARetryNotMadeTwice(
            final String automatic) {
        // Harm: a retry makes a second issue and leaves the first on the board outside its story.
        final GitHub github = new GitHub();
        github.make("example-project", "A story", "", IssueMapping.STORY_TYPE);
        github.added.add("I_example-project_100");
        final NewIssue issue =
                new NewIssue(
                        "example-lib",
                        "A new thing",
                        "It holds.",
                        "S",
                        Worker.AGENT,
                        null,
                        null,
                        "example-project#100",
                        "Backlog");
        github.linkFailures.add(GitHubExceptions.refused(422, "link refused"));

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIssue(
                                                snapshot(onTheProject(github, null)), SHOWN, issue))
                .as("the first try stops after the project add, before the sub-issue link")
                .isInstanceOf(StoreException.PartlySaved.class)
                .hasMessageContaining("link refused");
        assertThat(github.added)
                .as("the first try added the issue to the project")
                .containsExactly("I_example-project_100", "I_example-lib_101");
        assertThat(github.subIssues).as("the first try left the issue without its link").isEmpty();
        assertThat(github.values)
                .as("the first try stopped before the issue's field values")
                .doesNotContainKey("PVTI_I_example-lib_101");

        final String reference =
                github.writer()
                        .createIssue(
                                snapshot(
                                        onTheProject(
                                                github, automatic.isEmpty() ? null : automatic)),
                                SHOWN,
                                issue);

        assertThat(reference).as("the retry finishes the first issue").isEqualTo("example-lib#101");
        assertThat(held(github))
                .as("exactly one issue beside its story")
                .containsExactlyInAnyOrder("example-project#100", "example-lib#101");
        assertThat(github.added)
                .as("the issue stands on the project once")
                .containsExactly("I_example-project_100", "I_example-lib_101");
        assertThat(github.subIssues)
                .as("the retry finishes the missing story link")
                .containsExactly("example-project#100 < 9101");
        assertThat(github.values.get("PVTI_I_example-lib_101"))
                .as("the retry gives the original issue its requested Status and missing values")
                .containsEntry(
                        "F_Status",
                        FieldValue.option("PVTI_I_example-lib_101", "F_Status", "O_Backlog"))
                .containsKeys("F_Size", "F_Worker", "F_Rank");
    }

    @Test
    void anIssueAlikeOnTheProjectHoldingAValueIsNotTakenByACreateUnderAStory() {
        // Harm: the person's own issue of the same words, given a Size by hand, hung under a story
        // and its values written over by a new create under that story.
        final GitHub github = new GitHub();
        github.make("example-project", "A story", "", IssueMapping.STORY_TYPE);
        github.added.add("I_example-project_100");
        github.writer().createIssue(snapshot(), SHOWN, newIssue("example-lib", "A new thing"));
        final java.util.Map<String, FieldValue> own = github.values.get("PVTI_I_example-lib_101");
        own.keySet().retainAll(java.util.Set.of("F_Size"));
        final NewIssue issue =
                new NewIssue(
                        "example-lib",
                        "A new thing",
                        "It holds.",
                        "S",
                        Worker.AGENT,
                        null,
                        null,
                        "example-project#100",
                        "Backlog");

        final String reference =
                github.writer().createIssue(snapshot(onTheProject(github, null)), SHOWN, issue);

        assertThat(reference)
                .as("a new issue made under the story beside the person's")
                .isEqualTo("example-lib#102");
        assertThat(github.subIssues)
                .as("only the new issue hung under the story")
                .containsExactly("example-project#100 < 9102");
    }

    @Test
    void aWholeIssueAlikeInNoStoryIsThePersonsOwnAndANewOneIsMade() {
        // Harm: the person's finished issue in no story taken by a new create of the same words,
        // the new issue never made and the old one's values written over.
        final GitHub github = new GitHub();
        final NewIssue issue = newIssue("example-lib", "A new thing");
        github.writer().createIssue(snapshot(), SHOWN, issue);
        final int before = github.mutations.size();

        final String reference =
                github.writer().createIssue(snapshot(onTheProject(github, null)), SHOWN, issue);

        assertThat(reference).as("a new issue made").isEqualTo("example-lib#101");
        assertThat(github.mutations.subList(before, github.mutations.size()))
                .as("nothing written to the person's own issue")
                .allSatisfy(
                        values ->
                                assertThat(values)
                                        .noneSatisfy(
                                                value ->
                                                        assertThat(value.itemId())
                                                                .isEqualTo(
                                                                        "PVTI_I_example-lib_100")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"F_Size,F_Worker,F_Rank", "F_Size"})
    void anIssueAlikeInNoStoryHoldingSomeValuesIsThePersonsOwn(final String cleared) {
        // Harm: the person's own issue of the same words, partly filled in or with a value cleared,
        // taken by a new create, which then writes its values, rank and priority over the person's.
        final GitHub github = new GitHub();
        final NewIssue issue = newIssue("example-lib", "A new thing");
        github.writer().createIssue(snapshot(), SHOWN, issue);
        for (final String field : cleared.split(",")) {
            github.values.get("PVTI_I_example-lib_100").remove(field);
        }
        // The person gave it a priority of their own, which a try of this create never sends.
        github.organizationValues.put(100, List.of(OrganizationFields.priority("Urgent")));

        final String reference =
                github.writer().createIssue(snapshot(onTheProject(github, null)), SHOWN, issue);

        assertThat(reference)
                .as("a new issue made beside the person's")
                .isEqualTo("example-lib#101");
    }

    @Test
    void aCreateRetriedAfterLinkingTouchesItsStoryAndTheTakenIssue() {
        // Harm: the story stays without its new issue until a whole read when GitHub's sub-issue
        // list lags the link.
        final GitHub github = new GitHub();
        github.make("example-project", "A story", "", IssueMapping.STORY_TYPE);
        github.added.add("I_example-project_100");
        final Touched touched = new Touched();
        final FieldsBoardWriter writer = github.writer(touched.recording(github));
        final NewIssue issue =
                new NewIssue(
                        "example-lib",
                        "A new thing",
                        "It holds.",
                        "S",
                        Worker.AGENT,
                        null,
                        null,
                        "example-project#100");
        github.answering(
                values -> {
                    throw GitHubExceptions.refused(422, "fields refused");
                });

        assertThatThrownBy(
                        () ->
                                writer.createIssue(
                                        snapshot(onTheProject(github, null)), SHOWN, issue))
                .as("the first try stops after linking and before field values")
                .isInstanceOf(StoreException.PartlySaved.class)
                .hasMessageContaining("fields refused");
        assertThat(github.subIssues)
                .as("the first try already linked the new issue")
                .containsExactly("example-project#100 < 9101");
        assertThat(github.values)
                .as("the first try left the issue without field values")
                .doesNotContainKey("PVTI_I_example-lib_101");
        touched.clear();

        final String reference =
                writer.createIssue(snapshot(onTheProject(github, null)), SHOWN, issue);

        assertThat(reference).as("the retry takes the linked issue").isEqualTo("example-lib#101");
        assertThat(touched.ids().issues())
                .as("both the story and its taken issue must be read again after the retry")
                .containsExactlyInAnyOrder("I_example-project_100", "I_example-lib_101");
        assertThat(github.subIssues)
                .as("the retry retains the existing link")
                .containsExactly("example-project#100 < 9101");
    }

    @Test
    void twoAlikesFoundAfterAnUnansweredCreateAreBothTouchedWhenTheWriteStops() {
        // Harm: issues an earlier try made are left off the board by a whole read in flight.
        final GitHub github = new GitHub().creating("twice-unanswered");
        final Touched touched = new Touched();
        final FieldsBoardWriter writer = github.writer(touched.recording(github));

        assertThatThrownBy(
                        () ->
                                writer.createIssue(
                                        snapshot(), SHOWN, newIssue("example-lib", "A new thing")))
                .as("two alikes found after the unanswered create stop the write partly saved")
                .isInstanceOf(StoreException.PartlySaved.class)
                .hasMessageContaining("example-lib#100, example-lib#101");

        assertThat(touched.ids().issues())
                .as("both possible results of the unanswered create must be read again")
                .containsExactlyInAnyOrder("I_example-lib_100", "I_example-lib_101");
        assertThat(github.calls)
                .as("the alikes are found after the unanswered call, not before it")
                .containsExactly("issuesCreatedSince", "createIssue", "issuesCreatedSince");
    }

    @Test
    void anIssueUnderAStoryCutOffAfterItsStatusIsTakenWhenSentAgain() {
        // Harm: an issue added under a story and given its Status, whose creation was cut off,
        // made a second time when the person sends it again.
        final GitHub github = new GitHub();
        github.make("example-project", "A story", "", IssueMapping.STORY_TYPE);
        github.make("example-lib", "A new thing", WRITTEN, null);
        github.added.add("I_example-project_100");
        github.added.add("I_example-lib_101");
        github.subIssues.add("example-project#100 < 9101");
        github.values.put(
                "PVTI_I_example-lib_101",
                new LinkedHashMap<>(
                        Map.of(
                                "F_Status",
                                FieldValue.option(
                                        "PVTI_I_example-lib_101", "F_Status", "O_Idea"))));

        final String reference =
                github.writer()
                        .createIssue(
                                snapshot(onTheProject(github, null)),
                                SHOWN,
                                new NewIssue(
                                        "example-lib",
                                        "A new thing",
                                        "It holds.",
                                        "S",
                                        Worker.AGENT,
                                        null,
                                        null,
                                        "example-project#100"));

        assertThat(reference).as("the issue taken").isEqualTo("example-lib#101");
        assertThat(held(github)).as("nothing made twice").hasSize(2);
        assertThat(github.subIssues).as("no second link").hasSize(1);
    }

    @Test
    void aSubIssueLinkThatFailsOnGitHubsSideIsMadeAgain() {
        // Harm: a passing failure on GitHub's side stops the creation before the issue's
        // Status, leaving it on the project without one.
        final GitHub github = new GitHub();
        github.linkFailures.add(GitHubExceptions.refused(502, "bad gateway"));

        github.writer().createIdea(snapshot(), SHOWN, idea(newIssue("example-lib", "First")));

        assertThat(github.subIssues)
                .as("the link made")
                .containsExactly("example-project#100 < 9101");
        assertThat(github.values.get("PVTI_I_example-lib_101"))
                .as("the issue's values")
                .containsKey("F_Status");
    }

    @Test
    void anAddToTheProjectGitHubRefusedAsRateLimitedIsAskedAgainAfterTheWait() {
        // Harm: a new issue left off the project because GitHub's limit on writes in a burst
        // asked for a wait, answered as partly saved.
        final GitHub github = new GitHub();
        github.addFailures.add(
                GitHubExceptions.rateLimited(403, "secondary rate limit", Duration.ofSeconds(3)));

        final String reference =
                github.writer()
                        .createIssue(snapshot(), SHOWN, newIssue("example-lib", "A new thing"));

        assertThat(reference).as("the issue made").isEqualTo("example-lib#100");
        assertThat(github.added)
                .as("the issue added to the project")
                .containsExactly("I_example-lib_100");
        assertThat(github.waits).as("the waits").containsExactly(Duration.ofSeconds(3));
        assertThat(github.values.get("PVTI_I_example-lib_100"))
                .as("its values")
                .containsKey("F_Status");
    }

    @Test
    void twoIssuesAlikeOfAnEarlierTryStopTheWriteNamingBoth() {
        // Harm: one of two issues alike an earlier try may have made taken at a guess, the
        // other left beside it unnamed.
        final GitHub github = new GitHub();
        github.make("example-lib", "A new thing", WRITTEN, null);
        github.make("example-lib", "A new thing", WRITTEN, null);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIssue(
                                                snapshot(),
                                                SHOWN,
                                                newIssue("example-lib", "A new thing")))
                .as("a write that finds two issues alike made before it")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.getMessage())
                                        .as("both named")
                                        .contains("example-lib#100, example-lib#101"));
        assertThat(github.calls).as("the calls made").containsExactly("issuesCreatedSince");
    }

    @Test
    void aStoryFoundAfterAnUnansweredCallWithoutItsIssueTypeStopsTheWrite() {
        // Harm: issues made under a story found again that GitHub holds as a plain issue.
        final GitHub github = new GitHub().creating("untyped-unanswered");

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIdea(
                                                snapshot(),
                                                SHOWN,
                                                idea(newIssue("example-lib", "First"))))
                .as("an idea whose story was found again without its issue type")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.getMessage())
                                        .as("the story named")
                                        .contains("example-project#100"));
        assertThat(github.calls)
                .as("the calls made")
                .containsExactly("issuesCreatedSince", "createIssue", "issuesCreatedSince");
    }

    @Test
    void aFailureOnGitHubsSideWhileMakingAnIssueIsPartlySavedAndNotSentAgain() {
        // Harm: an issue GitHub made before failing on its side made again, or answered as
        // refused, so the window adds it once more.
        final GitHub github = new GitHub().creating("failed");

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIssue(
                                                snapshot(),
                                                SHOWN,
                                                newIssue("example-lib", "A new thing")))
                .as("a call GitHub failed on its side")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.pushed())
                                        .as("the repository that may hold it")
                                        .containsExactly("example-lib"));
        assertThat(github.calls)
                .as("the calls made")
                .containsExactly("issuesCreatedSince", "createIssue");
    }

    @Test
    void aRefusalAfterAnUnansweredCallIsPartlySaved() {
        // Harm: a refusal answered as if nothing was made, so the window hands the idea in
        // again while GitHub may still hold the issue of the call that went unanswered.
        final GitHub github = new GitHub().creating("lost", "refused");

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIssue(
                                                snapshot(),
                                                SHOWN,
                                                newIssue("example-lib", "A new thing")))
                .as("a refusal after an unanswered call")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.getMessage())
                                        .as("its words")
                                        .doesNotContain(TOKEN));
    }

    @Test
    void whatMustBeTrueOfSeveralParagraphsIsMadeWholeAsItsOwnSection() {
        // Harm: a text the window takes refused on a GitHub project, or cut to its first
        // paragraph.
        final GitHub github = new GitHub();

        github.writer()
                .createIssue(
                        snapshot(),
                        SHOWN,
                        new NewIssue(
                                "example-lib",
                                "A new thing",
                                "One.\n\nTwo.",
                                "S",
                                Worker.AGENT,
                                "task-1"));

        final String body = github.made.get("example-lib").get(0).body();
        assertThat(BodySections.read(body).part(BodySections.Part.WHAT_MUST_BE_TRUE))
                .as("what must be true, read back whole")
                .contains("One.\n\nTwo.");
        assertThat(BodySections.read(body).ambiguity())
                .as("whether its sections are clear")
                .isEmpty();
        assertThat(body)
                .as("the task that proposed it")
                .contains("Proposed by Porelo task `task-1`.");
    }

    @Test
    void whatMustBeTrueTheBodyCannotHoldIsRefusedBeforeAnyCall() {
        // Harm: an issue made on GitHub before the refusal of its own text, or the refusal
        // answered as GitHub unreachable.
        final GitHub github = new GitHub();

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIssue(
                                                snapshot(),
                                                SHOWN,
                                                new NewIssue(
                                                        "example-lib",
                                                        "A new thing",
                                                        "One.\n\n## Plan\n\nTwo.",
                                                        "S")))
                .as("what must be true with a section of its own")
                .isInstanceOfSatisfying(
                        StoreException.NotSavable.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .as("its words")
                                        .doesNotContain("Two."));
        assertThat(github.calls).as("the calls made").isEmpty();
    }

    private static StageResult analysis() {
        return new StageResult(
                WorkRule.Result.ANALYSIS,
                List.of(
                        new StageResult.ForIssue(
                                "PL1",
                                "Analysis",
                                "Backlog",
                                "- new step",
                                "S",
                                "small",
                                null,
                                List.of(),
                                List.of())));
    }

    private static StageEntry stage(final String key, final String kind) {
        return new StageEntry(
                key,
                kind,
                "done",
                "agent",
                "a-model",
                "high",
                4.5,
                1.5,
                Map.of("claude_input_tokens", 200L, "claude_output_tokens", 100L));
    }

    @Test
    void aMoveOfAnIssueOutsideTheWorkflowWritesItsStatusAndItsOpenState() {
        // Harm: an issue a person set to a Status the workflow lacks cannot be moved back into it,
        // or comes back closed on GitHub against the state it was moved to.
        final ItemView accepted = item("PL1", "example-project", 1, false, "", state("Accepted"));
        final GitHub github = new GitHub().holding(accepted);
        final Snapshot current =
                new Snapshot(
                        new Board(
                                SHOWN,
                                WORKFLOW,
                                List.of(),
                                List.of(
                                        issue(accepted)
                                                .withWarnings(
                                                        List.of(Warning.stateOutsideWorkflow()))),
                                new Work(Map.of(), List.of()),
                                CONFIG.repositories(),
                                List.of(),
                                null),
                        Map.of("PL1", accepted),
                        CONFIG);

        github.writer().save(current, SHOWN, new Writes(Map.of("PL1", "Backlog"), Map.of()));

        assertThat(github.mutations)
                .as("its Status written")
                .containsExactly(List.of(FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog")));
        assertThat(github.updates)
                .as("reopened, as its open state asks")
                .containsExactly("example-project#1 Opening[open=true, notPlanned=false]");
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                current,
                                                SHOWN,
                                                new Writes(Map.of("PL9", "Backlog"), Map.of())))
                .as("an issue not on the board")
                .isInstanceOf(StoreException.NoSuchIssue.class);
    }

    /** An issue standing on the project in Idea, in example-lib, under no story. */
    private static ItemView standing(final String id, final int number) {
        return item(id, "example-lib", number, true, "", ordered(FieldNames.STATUS, "Idea"));
    }

    /** An item as it stands once GitHub hangs it under a parent, all else unchanged. */
    private static ItemView under(final ItemView item, final String parent) {
        return linked(item, parent, item.subIssueIds());
    }

    /** An item as it stands once a person hung another issue under it on GitHub. */
    private static ItemView holdingAlso(final ItemView item, final String subIssueId) {
        final List<String> subIssues = new ArrayList<>(item.subIssueIds());
        subIssues.add(subIssueId);
        return linked(item, item.parentIssueId(), subIssues);
    }

    /** An item with another parent and other sub-issues, all else unchanged. */
    private static ItemView linked(
            final ItemView item, final @Nullable String parent, final List<String> subIssues) {
        return new ItemView(
                item.itemId(),
                item.issueId(),
                item.repository(),
                item.number(),
                item.title(),
                item.body(),
                item.open(),
                item.stateReason(),
                item.fields(),
                item.labels(),
                parent,
                subIssues,
                item.blockedByIds(),
                item.link(),
                item.updatedAt(),
                item.archived(),
                item.blockers(),
                item.unseenBlockers(),
                item.issueType(),
                null);
    }

    /** The body a story made by {@link #overTwo()} is given, without the request's marker. */
    private static final String OVER_TWO_BODY =
            "**What a user gains.** People find stories\n\n**The test.** Tag one\n";

    private static StoryFromIssues overTwo() {
        return new StoryFromIssues(
                "Tag stories", "People find stories", "Tag one", "High", List.of("B2", "A1"));
    }

    @Test
    void aStoryFromIssuesIsMadeAndEachIssueHungUnderItOnceInTheOrderNamedKeepingItsFields() {
        // Harm: issues that stand left outside the new story, hung twice or in another order,
        // or given the story's fields over their own.
        final ItemView a = standing("A1", 1);
        final ItemView b = standing("B2", 2);
        final GitHub github = new GitHub().holding(a, b);

        final BoardWriter.IdeaIds ids =
                github.writer().createStoryFromIssues(snapshot(a, b), SHOWN, overTwo());

        assertThat(ids)
                .as("the answer: the story made and the issues in the order named")
                .isEqualTo(new BoardWriter.IdeaIds("example-project#100", List.of("B2", "A1")));
        assertThat(held(github))
                .as("the one issue GitHub made")
                .containsExactly("example-project#100");
        assertThat(github.made.get("example-project").get(0).issue().type())
                .as("the story's issue type")
                .isEqualTo(IssueMapping.STORY_TYPE);
        assertThat(github.made.get("example-project").get(0).body())
                .as("the story's body, ending in the request's marker")
                .isEqualTo(OVER_TWO_BODY + "\n" + overTwo().marker() + "\n");
        assertThat(github.subIssues)
                .as("each issue hung under the story once, in the order named")
                .containsExactly("example-project#100 < 5002", "example-project#100 < 5001");
        assertThat(github.values.keySet())
                .as("field values written for the story alone")
                .containsExactly("PVTI_I_example-project_100");
        assertThat(github.calls)
                .as("the issues read again before the story is made")
                .containsSubsequence("issueBoard", "createIssue", "addSubIssue", "addSubIssue");
    }

    @Test
    void anIssueLinkedOrChangedOnGitHubRefusesTheStoryBeforeAnythingIsMade() {
        // Harm: a new story left on GitHub, partly saved, over an issue that hangs under another
        // parent the board did not show, or that a person changed since the board was read; or a
        // refusal that does not name the story holding the issue and the field that named it.
        final ItemView a = under(standing("A1", 1), "I_elsewhere");
        final ItemView b = standing("B2", 2);
        final GitHub linked = new GitHub().holding(a, b);

        assertThatThrownBy(
                        () ->
                                linked.writer()
                                        .createStoryFromIssues(snapshot(a, b), SHOWN, overTwo()))
                .as("an issue with a parent on GitHub the board does not hold")
                .isInstanceOfSatisfying(
                        StoreException.InStory.class,
                        e -> {
                            assertThat(e.subject()).as("the issue").isEqualTo("A1");
                            assertThat(e.story()).as("no story the board names").isEmpty();
                            assertThat(e.field()).as("its field").isEqualTo("existing[1]");
                        });
        assertThat(linked.calls)
                .as("nothing made over a linked issue")
                .doesNotContain("createIssue");

        final ItemView other =
                item("ST5", "example-project", 5, true, "", Map.of())
                        .typed(IssueMapping.STORY_TYPE);
        final ItemView held = under(standing("A1", 1), other.issueId());
        final GitHub inOther = new GitHub().holding(other, held, b);
        assertThatThrownBy(
                        () ->
                                inOther.writer()
                                        .createStoryFromIssues(
                                                snapshot(other, held, b), SHOWN, overTwo()))
                .as("an issue another story of the board holds, which core did not see")
                .isInstanceOfSatisfying(
                        StoreException.InStory.class,
                        e -> {
                            assertThat(e.subject()).as("the issue").isEqualTo("A1");
                            assertThat(e.story()).as("the story holding it").isEqualTo("ST5");
                            assertThat(e.field()).as("its field").isEqualTo("existing[1]");
                        });
        assertThat(inOther.calls)
                .as("nothing made over an issue of another story")
                .doesNotContain("createIssue");

        final ItemView c = standing("A1", 1);
        final GitHub changed =
                new GitHub()
                        .holding(
                                item(
                                        "A1",
                                        "example-lib",
                                        1,
                                        true,
                                        "",
                                        ordered(FieldNames.STATUS, "Backlog")),
                                b);
        assertThatThrownBy(
                        () ->
                                changed.writer()
                                        .createStoryFromIssues(snapshot(c, b), SHOWN, overTwo()))
                .as("an issue a person moved on GitHub since the board was read")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(changed.calls)
                .as("nothing made over a changed issue")
                .doesNotContain("createIssue", "addSubIssue");
    }

    @Test
    void aStoryFromIssuesAgainstAnotherRevisionIsRefusedWithNoCall() {
        // Harm: a story written over a board the caller never saw.
        final ItemView a = standing("A1", 1);
        final ItemView b = standing("B2", 2);
        final GitHub github = new GitHub().holding(a, b);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createStoryFromIssues(
                                                snapshot(a, b), new Revision("older"), overTwo()))
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(github.calls).as("no call made").isEmpty();
    }

    @Test
    void aStoryFromIssuesCutOffAfterItsFirstLinkIsFinishedBySendingItAgainWithNothingMadeTwice() {
        // Harm: a save cut off midway that a second try cannot finish, or finishes by making a
        // second story or hanging an issue twice.
        final ItemView a = standing("A1", 1);
        final ItemView b = standing("B2", 2);
        final GitHub github = new GitHub().holding(a, b);
        github.linksBeforeFailing = 1;
        github.linkFailures.add(GitHubExceptions.refused(422, "Validation failed"));

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createStoryFromIssues(snapshot(a, b), SHOWN, overTwo()))
                .as("a save whose second link GitHub refused")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.getMessage())
                                        .as("what GitHub holds")
                                        .contains("example-project#100"));
        assertThat(github.subIssues)
                .as("the first issue hung before the cut")
                .containsExactly("example-project#100 < 5002");

        final ItemView story = onTheProject(github, null)[0];
        final ItemView hung = under(b, story.issueId());
        github.holding(story, hung, a);
        final BoardWriter.IdeaIds ids =
                github.writer().createStoryFromIssues(snapshot(story, a, hung), SHOWN, overTwo());

        assertThat(ids)
                .as("the answer names the story the first try made")
                .isEqualTo(new BoardWriter.IdeaIds("example-project#100", List.of("B2", "A1")));
        assertThat(held(github)).as("no second story made").containsExactly("example-project#100");
        assertThat(github.subIssues)
                .as("each issue hung once, the second by the second try")
                .containsExactly("example-project#100 < 5002", "example-project#100 < 5001");
    }

    @Test
    void aStoryAPersonMadeOrAnotherRequestMadeIsNeverTakenAndANewOneIsMade() {
        // Harm: a create that extends a story a person made on purpose, or one of another
        // request, of the same title and text, and answers 201 as if it were new.
        final ItemView a = standing("A1", 1);
        final ItemView b = standing("B2", 2);
        final GitHub github = new GitHub().holding(a, b);
        github.make("example-project", "Tag stories", OVER_TWO_BODY, IssueMapping.STORY_TYPE);
        github.make(
                "example-project",
                "Tag stories",
                OVER_TWO_BODY
                        + "\n"
                        + StoryFromIssues.marker(
                                "Tag stories", "People find stories", "Tag one", List.of("A1"))
                        + "\n",
                IssueMapping.STORY_TYPE);

        final BoardWriter.IdeaIds ids =
                github.writer().createStoryFromIssues(snapshot(a, b), SHOWN, overTwo());

        assertThat(ids.story())
                .as("a new story, neither alike taken")
                .isEqualTo("example-project#102");
        assertThat(github.subIssues)
                .as("the issues hung under the new story alone")
                .containsExactly("example-project#102 < 5002", "example-project#102 < 5001");
    }

    @Test
    void aStoryOfTheSameRequestThatAPersonHungAnotherIssueUnderIsNotTaken() {
        // Harm: the issues named hung beside an issue a person put under the story an earlier
        // try made, as if that story were still the request's alone.
        final ItemView a = standing("A1", 1);
        final ItemView b = standing("B2", 2);
        final ItemView c = standing("C3", 3);
        final GitHub github = new GitHub().holding(a, b, c);
        github.linksBeforeFailing = 0;
        github.linkFailures.add(GitHubExceptions.refused(422, "Validation failed"));
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createStoryFromIssues(snapshot(a, b, c), SHOWN, overTwo()))
                .as("a try cut off before its first link")
                .isInstanceOf(StoreException.PartlySaved.class);
        final ItemView story = onTheProject(github, null)[0];
        final ItemView person = under(c, story.issueId());
        github.holding(story, person);

        final BoardWriter.IdeaIds ids =
                github.writer()
                        .createStoryFromIssues(snapshot(story, a, b, person), SHOWN, overTwo());

        assertThat(ids.story())
                .as("a new story beside the one the person added to")
                .isEqualTo("example-project#101");
        assertThat(github.subIssues)
                .as("the issues hung under the new story alone")
                .containsExactly("example-project#101 < 5002", "example-project#101 < 5001");
    }

    @Test
    void aRequestSentAgainAfterAWholeSaveWhoseAnswerWasLostWritesNothing() {
        // Harm: a second story made, or an issue hung twice, by the retry of a save that stood.
        final ItemView a = standing("A1", 1);
        final ItemView b = standing("B2", 2);
        final GitHub github = new GitHub().holding(a, b);
        github.writer().createStoryFromIssues(snapshot(a, b), SHOWN, overTwo());
        final ItemView story = onTheProject(github, null)[0];
        final ItemView hungA = under(a, story.issueId());
        final ItemView hungB = under(b, story.issueId());
        github.holding(story, hungA, hungB);
        final int before = github.calls.size();

        final BoardWriter.IdeaIds ids =
                github.writer()
                        .createStoryFromIssues(snapshot(story, hungA, hungB), SHOWN, overTwo());

        assertThat(ids)
                .as("the story the save made")
                .isEqualTo(new BoardWriter.IdeaIds("example-project#100", List.of("B2", "A1")));
        assertThat(github.calls.subList(before, github.calls.size()))
                .as("nothing made or hung again")
                .doesNotContain("createIssue", "addSubIssue", "addProjectItems");
        assertThat(held(github)).as("one story").containsExactly("example-project#100");
    }

    @Test
    void aStoryTakenThatAPersonChangedOnGitHubSinceTheBoardWasReadRefusesTheRetry() {
        // Harm: the issues named hung under the story an earlier try made, beside an issue a
        // person hung under it on GitHub after the board was read.
        final ItemView a = standing("A1", 1);
        final ItemView b = standing("B2", 2);
        final GitHub github = new GitHub().holding(a, b);
        github.linksBeforeFailing = 0;
        github.linkFailures.add(GitHubExceptions.refused(422, "Validation failed"));
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createStoryFromIssues(snapshot(a, b), SHOWN, overTwo()))
                .isInstanceOf(StoreException.PartlySaved.class);
        final ItemView story = onTheProject(github, null)[0];
        github.holding(holdingAlso(story, "I_someone"));
        final int before = github.calls.size();

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createStoryFromIssues(
                                                snapshot(story, a, b), SHOWN, overTwo()))
                .as("the story taken, changed on GitHub")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(github.calls.subList(before, github.calls.size()))
                .as("nothing hung")
                .doesNotContain("addSubIssue", "createIssue");
    }

    @Test
    void aClosedIssueIsRefusedByTheStoreBeforeAnyCall() {
        // Harm: a closed issue hung into a new story when the check before the store let it by.
        final ItemView a =
                item("A1", "example-lib", 1, false, "", ordered(FieldNames.STATUS, "Idea"));
        final ItemView b = standing("B2", 2);
        final GitHub github = new GitHub().holding(a, b);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createStoryFromIssues(snapshot(a, b), SHOWN, overTwo()))
                .as("a closed issue")
                .isInstanceOfSatisfying(
                        StoreException.NotSavable.class,
                        e -> assertThat(e.subject()).as("the issue").isEqualTo("A1"));
        assertThat(github.calls).as("no call made").isEmpty();
    }

    @Test
    void anIssueOutsideTheWorkflowIsRefusedByTheStoreBeforeAnyCall() {
        // Harm: an issue whose state the workflow lacks hung into a new story when the check
        // before the store let it by.
        final ItemView a =
                item("A1", "example-lib", 1, true, "", ordered(FieldNames.STATUS, "Parked"));
        final ItemView b = standing("B2", 2);
        final GitHub github = new GitHub().holding(a, b);
        final Map<String, ItemView> items = new LinkedHashMap<>();
        items.put("A1", a);
        items.put("B2", b);
        final Snapshot current =
                new Snapshot(
                        new Board(
                                SHOWN,
                                WORKFLOW,
                                List.of(),
                                List.of(
                                        issue(a).withWarnings(
                                                        List.of(Warning.stateOutsideWorkflow())),
                                        issue(b)),
                                new Work(Map.of(), List.of()),
                                CONFIG.repositories()),
                        items,
                        CONFIG);

        assertThatThrownBy(() -> github.writer().createStoryFromIssues(current, SHOWN, overTwo()))
                .as("an issue outside the workflow")
                .isInstanceOfSatisfying(
                        StoreException.NotSavable.class,
                        e -> assertThat(e.subject()).as("the issue").isEqualTo("A1"));
        assertThat(github.calls).as("no call made").isEmpty();
    }

    /** The basis of a caller that saw the items given, each by its id. */
    private static Basis seen(final ItemView... items) {
        Basis basis = Basis.none();
        for (final ItemView item : items) {
            basis = basis.with(idOf(item), ItemVersions.of(item));
        }
        return basis;
    }

    @Test
    void aWriteToOneIssueGoesThroughWhileAnotherIssueWasCreated() {
        // Harm: a move of one issue is refused because another client created an issue elsewhere
        // on the project after the caller read the board.
        final ItemView moved = item("PL1", "example-project", 1, true, "", state("Idea"));
        final ItemView created = item("LB9", "example-lib", 9, true, "", state("Idea"));
        final GitHub github = new GitHub().holding(moved, created);

        github.writer()
                .save(
                        snapshot(moved, created),
                        seen(moved),
                        new Writes(Map.of("PL1", "Backlog"), Map.of()));

        assertThat(github.mutations)
                .as("the move written")
                .containsExactly(List.of(FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog")));
    }

    @Test
    void aTargetChangedAfterTheCallersReadIsRefusedAndNothingIsWritten() {
        // Harm: a move is written over an issue a person changed after the caller read it.
        final ItemView before = item("PL1", "example-project", 1, true, "", state("Idea"));
        final ItemView edited =
                item("PL1", "example-project", 1, true, "Edited on GitHub.", state("Idea"));
        final GitHub github = new GitHub().holding(edited);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(before),
                                                seen(before),
                                                new Writes(Map.of("PL1", "Backlog"), Map.of())))
                .as("the move over a changed issue")
                .isInstanceOfSatisfying(
                        StoreException.Changed.class,
                        e -> assertThat(e.ids()).as("the changed").containsExactly("PL1"));
        assertThat(github.calls).as("the calls made").containsExactly("issueBoard");
        assertThat(github.mutations).as("the field values written").isEmpty();
    }

    @Test
    void aTargetWithoutAVersionIsRefusedBeforeGitHubIsAsked() {
        // Harm: a version the caller did not send is taken as "do not check", and the write goes
        // out over whatever GitHub holds.
        final ItemView moved = item("PL1", "example-project", 1, true, "", state("Idea"));
        final ItemView other = item("LB2", "example-lib", 2, true, "", state("Idea"));
        final GitHub github = new GitHub().holding(moved, other);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(moved, other),
                                                seen(other),
                                                new Writes(Map.of("PL1", "Backlog"), Map.of())))
                .as("the move without its target's version")
                .isInstanceOfSatisfying(
                        StoreException.BasisIncomplete.class,
                        e -> assertThat(e.missing()).as("the missing").containsExactly("PL1"));
        assertThat(github.calls).as("the calls made").isEmpty();
    }

    @Test
    void aChangeToAnItemTheBasisNamesButTheWriteDoesNotChangeRefusesNothing() {
        // Harm: the screen sends the versions of everything it saw, and a move of one issue is
        // refused because another client changed an unrelated issue among them.
        final ItemView moved = item("PL1", "example-project", 1, true, "", state("Idea"));
        final ItemView before = item("LB2", "example-lib", 2, true, "", state("Idea"));
        final ItemView edited =
                item("LB2", "example-lib", 2, true, "Edited on GitHub.", state("Idea"));
        final GitHub github = new GitHub().holding(moved, edited);

        github.writer()
                .save(
                        snapshot(moved, before),
                        seen(moved, before),
                        new Writes(Map.of("PL1", "Backlog"), Map.of()));

        assertThat(github.mutations)
                .as("the move written")
                .containsExactly(List.of(FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog")));
    }

    @Test
    void aWriteCheckedByVersionsThatGitHubRefusesInPartIsPartlySaved() {
        // Harm: after the versions were checked, GitHub takes one part of the write and refuses
        // another, and the window is told nothing was saved.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Idea"));
        final ItemView two = item("LB2", "example-lib", 2, true, "", state("Idea"));
        final GitHub github =
                new GitHub()
                        .holding(one, two)
                        .answering(
                                values ->
                                        new Batch<>(
                                                List.of(
                                                        Batch.Outcome.of(values.get(0).itemId()),
                                                        Batch.Outcome.<String>refused(
                                                                "refused for " + TOKEN)),
                                                null,
                                                GitHubExceptions.WITHOUT_A_WORD));

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(one, two),
                                                seen(one, two),
                                                new Writes(
                                                        ordered("PL1", "Backlog", "LB2", "Backlog"),
                                                        Map.of())))
                .as("a write whose second part GitHub refused")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.pushed())
                                        .as("the repository saved")
                                        .containsExactly("example-project"));
    }

    /** One write that changes its target's open state or priority, without a reason. */
    private record OpenOrPriority(ItemView before, ItemView edited, String target, Writes writes) {}

    /** The item as a person left it on GitHub: the same issue, its body edited. */
    private static ItemView edited(final ItemView item) {
        return new ItemView(
                        item.itemId(),
                        item.issueId(),
                        item.repository(),
                        item.number(),
                        item.title(),
                        "Edited on GitHub.",
                        item.open(),
                        item.stateReason(),
                        item.fields(),
                        item.labels(),
                        item.parentIssueId(),
                        item.subIssueIds(),
                        item.blockedByIds(),
                        item.link(),
                        item.updatedAt(),
                        item.archived())
                .typed(item.issueType());
    }

    private static OpenOrPriority openOrPriority(final String write) {
        final ItemView issue = item("PL1", "example-project", 1, true, "", state("Backlog"));
        final ItemView story = storyItem("ST1", 2);
        final ItemView closedStory = closedAs(story, "completed").typed(IssueMapping.STORY_TYPE);
        return switch (write) {
            case "issue close" ->
                    new OpenOrPriority(
                            issue,
                            edited(issue),
                            "PL1",
                            new Writes(Map.of("PL1", "Finished"), Map.of()));
            case "story close" ->
                    new OpenOrPriority(
                            story,
                            edited(story),
                            "ST1",
                            new Writes(Map.of(), Map.of("ST1", "Finished")));
            case "story reopen" ->
                    new OpenOrPriority(
                            closedStory,
                            edited(closedStory),
                            "ST1",
                            new Writes(Map.of(), Map.of("ST1", "Backlog")));
            case "story priority" ->
                    new OpenOrPriority(
                            story,
                            edited(story),
                            "ST1",
                            new Writes(Map.of(), Map.of(), Map.of(), Map.of("ST1", "Medium")));
            default -> throw new IllegalArgumentException(write);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"issue close", "story close", "story reopen", "story priority"})
    void aCloseReopenOrPriorityOverATargetChangedSinceTheCallersReadIsRefused(final String write) {
        // Harm: a close, a reopen or a priority is written over an item a person changed after
        // the caller read it, as only the Status and the other fields were compared.
        final OpenOrPriority given = openOrPriority(write);
        final GitHub github = new GitHub().holding(given.edited());

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(given.before()),
                                                seen(given.before()),
                                                given.writes()))
                .as("the %s over a changed item", write)
                .isInstanceOfSatisfying(
                        StoreException.Changed.class,
                        e -> assertThat(e.ids()).as("the changed").containsExactly(given.target()));
        assertThat(github.mutations).as("the field values written").isEmpty();
        assertThat(github.updates).as("the issues closed or reopened").isEmpty();
        assertThat(github.priorityUpdates).as("the priorities written").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"issue close", "story close", "story reopen", "story priority"})
    void aCloseReopenOrPriorityWithoutItsTargetsVersionIsRefusedBeforeGitHubIsAsked(
            final String write) {
        // Harm: a close, a reopen or a priority goes out unchecked when the caller sent no version
        // of the item it changes.
        final OpenOrPriority given = openOrPriority(write);
        final ItemView other = item("LB2", "example-lib", 2, true, "", state("Idea"));
        final GitHub github = new GitHub().holding(given.before(), other);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(given.before(), other),
                                                seen(other),
                                                given.writes()))
                .as("the %s without its target's version", write)
                .isInstanceOfSatisfying(
                        StoreException.BasisIncomplete.class,
                        e ->
                                assertThat(e.missing())
                                        .as("the missing")
                                        .containsExactly(given.target()));
        assertThat(github.calls).as("the calls made").isEmpty();
    }

    @Test
    void anIssuesPriorityOverAnIssueChangedSinceTheCallersReadIsRefused() {
        // Harm: an issue's priority is written over an issue a person changed after the caller
        // read it.
        final ItemView before =
                item("PL1", "example-project", 1, true, "", Map.of(FieldNames.PRIORITY, "High"));
        final GitHub github = new GitHub().holding(edited(before));

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .setFields(
                                                snapshot(before),
                                                seen(before),
                                                "PL1",
                                                new org.fuin.porelo.planner.store.Fields(
                                                        Set.of(
                                                                org.fuin.porelo.planner.store.Fields
                                                                        .Field.PRIORITY),
                                                        null,
                                                        null,
                                                        "Medium",
                                                        null)))
                .as("the priority over a changed issue")
                .isInstanceOfSatisfying(
                        StoreException.Changed.class,
                        e -> assertThat(e.ids()).as("the changed").containsExactly("PL1"));
        assertThat(github.priorityUpdates).as("the priorities written").isEmpty();
    }
}

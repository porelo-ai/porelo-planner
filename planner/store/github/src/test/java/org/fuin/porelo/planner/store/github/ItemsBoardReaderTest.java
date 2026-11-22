package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GitHubGraphQl.BoardIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldOption;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueBoard;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueOnBoard;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueRef;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueRefs;
import org.fuin.porelo.forge.github.GitHubGraphQl.ItemValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.PageInfo;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectField;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectFields;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectItem;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectItems;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.OutsideIssue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ItemsBoardReaderTest {

    private static final String TOKEN = "github_pat_reader_test_0123456789";

    private static final String OWNER = "example-org";

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Backlog", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Backlog", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef(OWNER, 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    List.of("example-project", "example-lib"),
                    Map.of(),
                    Map.of());

    private static final GraphQlCost COST = new GraphQlCost(1, 4999, Instant.EPOCH);

    private static final StoryStates FIRST_OPEN = states -> states.isEmpty() ? null : states.get(0);

    /** The project's fields as GitHub holds them: every field the store uses, of its kind. */
    private static final List<ProjectField> FIELDS =
            List.of(
                    select("F_status", FieldNames.STATUS, "Idea", "Backlog", "Finished"),
                    select(
                            "F_priority",
                            FieldNames.PRIORITY,
                            Story.PRIORITIES.toArray(String[]::new)),
                    select("F_size", FieldNames.SIZE, IssueMapping.SIZES.toArray(String[]::new)),
                    select("F_risk", FieldNames.RISK, "harm", "ordinary", "small"),
                    select("F_worker", FieldNames.WORKER, "Agent", "Manual"),
                    field("F_rank", FieldNames.RANK, "NUMBER"),
                    field("F_estimate", FieldNames.ESTIMATE, "NUMBER"),
                    field("F_minutes", FieldNames.MINUTES, "NUMBER"),
                    field("F_vm", FieldNames.VM_MINUTES, "NUMBER"),
                    field("F_tokens", FieldNames.TOKENS, "NUMBER"),
                    field("F_stages", FieldNames.STAGES, "NUMBER"));

    /** GitHub's answers, page by page, in the order the reader asks for them. */
    private static final class GitHub {
        private final Deque<Supplier<ProjectFields>> fields = new ArrayDeque<>();
        private final Deque<Supplier<ProjectItems>> items = new ArrayDeque<>();
        private final Deque<Supplier<IssueBoard>> boards = new ArrayDeque<>();
        private final List<String> calls = new ArrayList<>();
        private final List<GitHubGraphQl.ItemLimits> limits = new ArrayList<>();
        private final List<List<String>> issueBatches = new ArrayList<>();
        private final List<Duration> pauses = new ArrayList<>();
        private final AtomicInteger tokenAsks = new AtomicInteger();

        private GitHub fieldsPage(final List<ProjectField> page, final @Nullable String next) {
            fields.add(
                    () ->
                            new ProjectFields(
                                    "PVT_1", "Plan", Instant.EPOCH, page, info(next), COST));
            return this;
        }

        private GitHub itemsPage(final @Nullable String next, final ProjectItem... page) {
            items.add(() -> new ProjectItems(List.of(page), info(next), COST, 1));
            return this;
        }

        /**
         * The issues' own project items as GitHub answers them, each on the project or not, in the
         * order asked.
         */
        private GitHub issuesOnProject(final boolean... onProject) {
            boards.add(
                    () -> {
                        final List<@Nullable IssueOnBoard> issues = new ArrayList<>();
                        for (final boolean on : onProject) {
                            issues.add(
                                    new IssueOnBoard(
                                            issue(
                                                    OWNER + "/example-lib",
                                                    3,
                                                    List.of(),
                                                    null,
                                                    refs(false)),
                                            on ? "PVTI_lib_3" : null,
                                            List.of(),
                                            new GitHubGraphQl.PageInfo(false, null)));
                        }
                        return new IssueBoard(issues, COST);
                    });
            return this;
        }

        private GitHub boardPage(final @Nullable IssueOnBoard... issues) {
            boards.add(() -> new IssueBoard(Arrays.asList(issues), COST));
            return this;
        }

        private GitHub boardItems(final ProjectItem... items) {
            return boardPage(
                    Arrays.stream(items)
                            .map(ItemsBoardReaderTest::onBoard)
                            .toArray(IssueOnBoard[]::new));
        }

        private GitHub boardFailure(final RuntimeException failure) {
            boards.add(
                    () -> {
                        throw failure;
                    });
            return this;
        }

        private GitHub itemsFailure(final RuntimeException failure) {
            items.add(
                    () -> {
                        throw failure;
                    });
            return this;
        }

        /**
         * The fake client: it answers the two reads from the pages given and fails the test on any
         * other call, a write above all.
         */
        private ProjectQueries queries() {
            return (ProjectQueries)
                    Proxy.newProxyInstance(
                            ProjectQueries.class.getClassLoader(),
                            new Class<?>[] {ProjectQueries.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("issueBoard")) {
                                    calls.add("issueBoard of " + args[1] + " in " + args[2]);
                                    issueBatches.add(
                                            ((List<?>) args[1])
                                                    .stream().map(String.class::cast).toList());
                                } else if (args.length > 4 && (int) args[4] != 100) {
                                    calls.add(
                                            method.getName()
                                                    + " after "
                                                    + args[3]
                                                    + " of "
                                                    + args[4]);
                                } else {
                                    calls.add(method.getName() + " after " + args[3]);
                                }
                                if (method.getName().equals("projectItems")) {
                                    limits.add(
                                            args.length == 6
                                                    ? (GitHubGraphQl.ItemLimits) args[5]
                                                    : GitHubGraphQl.ItemLimits.DEFAULT);
                                }
                                assertThat(args[0])
                                        .as("the token the read asked for")
                                        .isEqualTo(TOKEN);
                                return switch (method.getName()) {
                                    case "projectFields" -> next(fields).get();
                                    case "projectItems" -> next(items).get();
                                    case "issueBoard" -> next(boards).get();
                                    default ->
                                            throw new AssertionError(
                                                    "A board read called " + method.getName());
                                };
                            });
        }

        private static <T> T next(final Deque<T> pages) {
            final T page = pages.poll();
            if (page == null) {
                throw new AssertionError("A page was read that GitHub was not asked to give");
            }
            return page;
        }

        private ItemsBoardReader reader(final AccessRefusals refusals) {
            return new ItemsBoardReader(
                    queries(),
                    origin -> {
                        tokenAsks.incrementAndGet();
                        return TOKEN;
                    },
                    refusals,
                    FIRST_OPEN,
                    InstantSource.system(),
                    ItemsBoardReader.DEADLINE,
                    pauses::add);
        }

        private Snapshot read() {
            return reader(AccessRefusals.DEFAULT).read(CONFIG);
        }
    }

    private static PageInfo info(final @Nullable String next) {
        return new PageInfo(next != null, next);
    }

    private static ProjectField field(final String id, final String name, final String type) {
        return new ProjectField(id, name, type, List.of(), 1L);
    }

    private static ProjectField select(
            final String id, final String name, final String... options) {
        return new ProjectField(
                id,
                name,
                "SINGLE_SELECT",
                Arrays.stream(options)
                        .map(option -> new FieldOption(id + "_" + option, option, "GRAY", ""))
                        .toList(),
                1L);
    }

    private static ItemValue option(final String fieldId, final String name) {
        return new ItemValue(fieldId, fieldId + "_" + name, name, null, null);
    }

    private static ItemValue text(final String fieldId, final String text) {
        return new ItemValue(fieldId, null, null, text, null);
    }

    private static ItemValue number(final String fieldId, final double number) {
        return new ItemValue(fieldId, null, null, null, number);
    }

    private static IssueRefs refs(final boolean more, final String... ids) {
        return new IssueRefs(
                Arrays.stream(ids)
                        .map(id -> new IssueRef(id, 1L, 1, OWNER + "/example-lib"))
                        .toList(),
                new PageInfo(more, more ? "more" : null));
    }

    private static BoardIssue issue(
            final String nameWithOwner,
            final int number,
            final List<String> labels,
            final @Nullable String parent,
            final IssueRefs subs) {
        return issue(nameWithOwner, number, labels, parent, subs, refs(false));
    }

    private static BoardIssue issue(
            final String nameWithOwner,
            final int number,
            final List<String> labels,
            final @Nullable String parent,
            final IssueRefs subs,
            final IssueRefs blockers) {
        return new BoardIssue(
                node(nameWithOwner, number),
                number,
                number,
                "A title",
                "A body",
                "OPEN",
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                nameWithOwner,
                labels,
                parent,
                subs,
                blockers,
                new GitHubGraphQl.PageInfo(false, null));
    }

    private static String node(final String nameWithOwner, final int number) {
        return "I_" + nameWithOwner.replace('/', '_') + "_" + number;
    }

    private static ProjectItem story(final String priority, final String... subNodes) {
        return story(false, priority, subNodes);
    }

    private static ProjectItem story(
            final boolean archived, final String priority, final String... subNodes) {
        return new ProjectItem(
                "PVTI_story",
                archived,
                issue(
                        OWNER + "/example-project",
                        1,
                        List.of(IssueMapping.STORY_LABEL),
                        null,
                        refs(false, subNodes)),
                List.of(option("F_status", "Idea"), option("F_priority", priority)),
                new GitHubGraphQl.PageInfo(false, null));
    }

    private static ProjectItem libIssue(final int number, final IssueRefs subs) {
        return new ProjectItem(
                "PVTI_lib_" + number,
                false,
                issue(
                        OWNER + "/example-lib",
                        number,
                        List.of(),
                        node(OWNER + "/example-project", 1),
                        subs),
                List.of(
                        option("F_status", "Backlog"),
                        option("F_size", "M"),
                        number("F_rank", 3),
                        number("F_minutes", 12.5)),
                new GitHubGraphQl.PageInfo(false, null));
    }

    private static final String LIB_3 = node(OWNER + "/example-lib", 3);

    private static IssueOnBoard onBoard(final ProjectItem item) {
        return new IssueOnBoard(item.issue(), item.id(), item.values(), item.valuesPage());
    }

    private static ProjectItem partialValues(final ProjectItem item) {
        return new ProjectItem(
                item.id(), item.archived(), item.issue(), item.values(), info("values-2"));
    }

    private static ProjectItem withLabels(
            final ProjectItem item, final List<String> labels, final @Nullable String next) {
        final BoardIssue issue = java.util.Objects.requireNonNull(item.issue());
        return new ProjectItem(
                item.id(),
                item.archived(),
                new BoardIssue(
                        issue.id(),
                        issue.databaseId(),
                        issue.number(),
                        issue.title(),
                        issue.body(),
                        issue.state(),
                        issue.stateReason(),
                        issue.updatedAt(),
                        issue.repository(),
                        labels,
                        issue.parentId(),
                        issue.subIssues(),
                        issue.blockedBy(),
                        info(next)),
                item.values(),
                item.valuesPage());
    }

    @Test
    void anItemsPageAsksForSmallerListsEvenAfterACostlyPage() {
        // Harm: GitHub refuses an entire board page because its nested lists cost too much.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage("next", story("High"))
                        .itemsFailure(
                                new IllegalStateException(
                                        "Resource limits for this query exceeded."))
                        .itemsPage(null);

        github.read();

        assertThat(github.limits)
                .as("small lists on every page and retry")
                .containsExactly(
                        new GitHubGraphQl.ItemLimits(10, 20, 10, 30),
                        new GitHubGraphQl.ItemLimits(10, 20, 10, 30),
                        new GitHubGraphQl.ItemLimits(10, 20, 10, 30));
    }

    @Test
    void pacedItemsReadsKeepTheirListLimitsWhenThePageIsHalved() {
        // Harm: a pacing wrapper drops list limits and makes a smaller retry too costly again.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsFailure(
                                new IllegalStateException(
                                        "Resource limits for this query exceeded."))
                        .itemsPage(null, story("High"));
        final InstantSource clock = InstantSource.fixed(Instant.EPOCH);
        final ProjectQueries paced = new GitHubPace(clock).watching(github.queries());
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        paced,
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        FIRST_OPEN,
                        clock,
                        ItemsBoardReader.DEADLINE);

        final Snapshot snapshot = reader.read(CONFIG);

        assertThat(snapshot.board().stories())
                .as("a board read through the pacing wrapper")
                .hasSize(1);
        assertThat(github.limits)
                .as("the pacing wrapper preserves the requested nested limits")
                .containsExactly(
                        new GitHubGraphQl.ItemLimits(10, 20, 10, 30),
                        new GitHubGraphQl.ItemLimits(10, 20, 10, 30));
        assertThat(github.calls)
                .as("the smaller page size also reaches the source")
                .containsSubsequence("projectItems after null", "projectItems after null of 50");
    }

    @Test
    void aCompletedItemKeepsItsArchiveFlagUnderTheItemIdGitHubAnswers() {
        // Harm: the rest-read resurrects archived work or retains a stale project item id.
        final ProjectItem original = story(true, "High");
        final ProjectItem whole =
                new ProjectItem(
                        "PVTI_replaced", true, original.issue(), original.values(), info(null));
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, partialValues(original))
                        .boardItems(whole);

        final Snapshot snapshot = github.read();

        assertThat(snapshot.raw())
                .as("only the current item id remains")
                .containsOnlyKeys(whole.id());
        assertThat(snapshot.raw().get(whole.id()))
                .as("the items page owns the archive flag")
                .isEqualTo(whole);
        assertThat(snapshot.board().stories()).as("archived work stays off the board").isEmpty();
    }

    @Test
    void aReplacementItemKeepsItsOwnArchiveFlagWhenBothIdsWereListed() {
        // Harm: a stale item's archive flag hides active work after the project item is replaced.
        final ProjectItem old = story(true, "High");
        final ProjectItem current =
                new ProjectItem("PVTI_current", false, old.issue(), old.values(), info(null));
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage("next", partialValues(old))
                        .itemsPage(null, partialValues(current))
                        .boardItems(current);

        final Snapshot snapshot = github.read();

        assertThat(github.issueBatches)
                .as("one issue asked once despite its two item ids")
                .containsExactly(List.of(java.util.Objects.requireNonNull(current.issue()).id()));
        assertThat(snapshot.raw())
                .as("only the current project item remains")
                .containsOnlyKeys(current.id());
        assertThat(snapshot.raw().get(current.id()))
                .as("the current item's observed archive flag")
                .isEqualTo(current);
        assertThat(snapshot.board().stories()).as("active work remains visible").hasSize(1);
    }

    @Test
    void anIssueWithMoreBlockersIsReadWholeAndItsStoryStaysWhole() {
        // Harm: an issue and its story's completeness are lost when the first blockers page ends.
        final ProjectItem partial =
                new ProjectItem(
                        "PVTI_lib_3",
                        false,
                        issue(
                                OWNER + "/example-lib",
                                3,
                                List.of(),
                                node(OWNER + "/example-project", 1),
                                refs(false),
                                refs(true, node(OWNER + "/example-lib", 4))),
                        libIssue(3, refs(false)).values(),
                        info(null));
        final ProjectItem whole =
                new ProjectItem(
                        partial.id(),
                        false,
                        issue(
                                OWNER + "/example-lib",
                                3,
                                List.of(),
                                node(OWNER + "/example-project", 1),
                                refs(false),
                                refs(
                                        false,
                                        node(OWNER + "/example-lib", 4),
                                        node(OWNER + "/example-lib", 5))),
                        partial.values(),
                        info(null));
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(
                                null,
                                story("High", LIB_3),
                                partial,
                                libIssue(4, refs(false)),
                                libIssue(5, refs(false)))
                        .boardItems(whole);
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);

        final Snapshot snapshot = reader.read(CONFIG);

        assertThat(github.issueBatches)
                .as("only the incomplete issue read again")
                .containsExactly(List.of(LIB_3));
        assertThat(snapshot.board().issue("example-lib#3").orElseThrow().blockedBy())
                .as("every blocker from the complete answer")
                .containsExactly("example-lib#4", "example-lib#5");
        assertThat(snapshot.board().stories().get(0).issuesWhole())
                .as("the story is whole")
                .isTrue();
        assertThat(snapshot.raw().get(partial.id()))
                .as("the whole item retained for later saves")
                .isEqualTo(whole);
        final BoardCopy copy = reader.toCopy(snapshot, ChangeCursor.NONE, List.of());
        assertThat(copy).as("the board copy").isNotNull();
        assertThat(copy.items())
                .as("the recovered item is complete in the copy")
                .filteredOn(item -> item.id().equals(whole.id()))
                .allSatisfy(item -> assertThat(item.partial()).as("complete lists").isFalse())
                .hasSize(1);
        assertThat(reader.fromCopy(copy, CONFIG))
                .as("the copy rebuilds the whole snapshot locally")
                .isEqualTo(snapshot);
        assertThat(github.issueBatches)
                .as("loading a copy asks no GitHub reads")
                .containsExactly(List.of(LIB_3));
    }

    @Test
    void aStoryWithMoreLabelsIsReadWholeBeforeItIsMapped() {
        // Harm: a story label beyond the first page hides the story and all its planning work.
        final ProjectItem whole = story("High");
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, withLabels(whole, List.of("other"), "labels-2"))
                        .boardItems(whole);

        final Snapshot snapshot = github.read();

        assertThat(github.issueBatches)
                .as("the issue with incomplete labels")
                .containsExactly(List.of(java.util.Objects.requireNonNull(whole.issue()).id()));
        assertThat(snapshot.board().stories()).as("the recovered story label is used").hasSize(1);
        assertThat(snapshot.raw().get(whole.id()))
                .as("all labels kept in the raw item")
                .isEqualTo(whole);
        assertThat(snapshot.board().faults()).as("no false overflow fault").isEmpty();
    }

    @Test
    void moreSubIssuesAreReadBeforeStoryCompletenessIsCalculated() {
        // Harm: work beyond a story's first sub-issues page disappears from its completeness.
        final ProjectItem whole = story("High", LIB_3);
        final ProjectItem partial =
                new ProjectItem(
                        whole.id(),
                        false,
                        issue(
                                OWNER + "/example-project",
                                1,
                                List.of(IssueMapping.STORY_LABEL),
                                null,
                                refs(true)),
                        whole.values(),
                        info(null));
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, partial, libIssue(3, refs(false)))
                        .boardItems(whole);

        final Snapshot snapshot = github.read();

        assertThat(snapshot.board().stories().get(0).issues())
                .as("all the story's work")
                .containsExactly("example-lib#3");
        assertThat(snapshot.board().stories().get(0).issuesWhole()).as("a complete story").isTrue();
        assertThat(snapshot.raw().get(whole.id()))
                .as("complete sub-issues retained")
                .isEqualTo(whole);
    }

    @Test
    void theRestOfFieldValuesChangesTheBoardAndItsRevision() {
        // Harm: a priority beyond the first values page is lost and later changes are never shown.
        final ProjectItem before = story("High");
        final ProjectItem whole = story("Low");
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, partialValues(before))
                        .boardItems(whole);
        final Snapshot baseline =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, before).read();

        final Snapshot snapshot = github.read();

        assertThat(snapshot.board().stories().get(0).priority())
                .as("the reread priority")
                .isEqualTo("Low");
        assertThat(snapshot.raw().get(whole.id())).as("whole values retained").isEqualTo(whole);
        assertThat(snapshot.board().revision())
                .as("completed values participate in the revision")
                .isNotEqualTo(baseline.board().revision());
    }

    @Test
    void anIssueGoneBeforeTheRestReadLeavesTheBoardWithoutACrash() {
        // Harm: an issue removed during pagination crashes every board read.
        final ProjectItem whole = libIssue(3, refs(false));
        for (final @Nullable IssueOnBoard gone :
                Arrays.asList(
                        null, new IssueOnBoard(whole.issue(), null, whole.values(), info(null)))) {
            final GitHub github =
                    new GitHub()
                            .fieldsPage(FIELDS, null)
                            .itemsPage(null, partialValues(whole))
                            .boardPage(gone);

            final Snapshot snapshot = github.read();

            assertThat(github.issueBatches)
                    .as("the vanished issue was checked")
                    .containsExactly(List.of(LIB_3));
            assertThat(snapshot.raw()).as("no stale item retained").isEmpty();
            assertThat(snapshot.board().issues()).as("the vanished issue is absent").isEmpty();
        }
    }

    @Test
    void anIncompleteItemNamedOnTwoPagesIsReadAgainOnceAfterTheLastPage() {
        // Harm: repeated node ids make GitHub reject the rest-read and block the whole board.
        final ProjectItem whole = libIssue(3, refs(false));
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage("next", partialValues(whole))
                        .itemsPage(null, partialValues(whole))
                        .boardItems(whole);

        final Snapshot snapshot = github.read();

        assertThat(github.calls)
                .as("rest-read happens after pagination")
                .containsExactly(
                        "projectFields after null",
                        "projectItems after null",
                        "projectItems after next",
                        "issueBoard of [" + LIB_3 + "] in PVT_1");
        assertThat(github.issueBatches).as("one distinct issue id").containsExactly(List.of(LIB_3));
        assertThat(snapshot.raw()).as("one complete item").containsOnlyKeys(whole.id());
        assertThat(snapshot.raw().get(whole.id()))
                .as("the full answer replaces the partial one")
                .isEqualTo(whole);
    }

    @Test
    void restReadsAreBatchedWithinGitHubsIssueLimit() {
        // Harm: a board with many incomplete items exceeds GitHub's batch limit and cannot load.
        final ProjectItem[] whole =
                java.util.stream.IntStream.rangeClosed(1, GitHubGraphQl.MAX_ISSUES + 1)
                        .mapToObj(number -> libIssue(number, refs(false)))
                        .toArray(ProjectItem[]::new);
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(
                                "next",
                                Arrays.stream(whole)
                                        .limit(GitHubGraphQl.MAX_ISSUES)
                                        .map(ItemsBoardReaderTest::partialValues)
                                        .toArray(ProjectItem[]::new))
                        .itemsPage(null, partialValues(whole[GitHubGraphQl.MAX_ISSUES]))
                        .boardItems(Arrays.copyOf(whole, GitHubGraphQl.MAX_ISSUES))
                        .boardItems(whole[GitHubGraphQl.MAX_ISSUES]);

        final Snapshot snapshot = github.read();

        assertThat(github.issueBatches)
                .as("bounded batches in item order")
                .containsExactly(
                        Arrays.stream(whole)
                                .limit(GitHubGraphQl.MAX_ISSUES)
                                .map(item -> java.util.Objects.requireNonNull(item.issue()).id())
                                .toList(),
                        List.of(
                                java.util.Objects.requireNonNull(
                                                whole[GitHubGraphQl.MAX_ISSUES].issue())
                                        .id()));
        assertThat(snapshot.raw().values()).as("every item completed").containsExactly(whole);
    }

    @Test
    void aCostlyRestReadIsHalvedWithoutLosingAnyItems() {
        // Harm: a costly rest-read makes a recoverable board unavailable or silently loses work.
        final ProjectItem[] whole =
                java.util.stream.IntStream.rangeClosed(1, 24)
                        .mapToObj(number -> libIssue(number, refs(false)))
                        .toArray(ProjectItem[]::new);
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(
                                null,
                                Arrays.stream(whole)
                                        .map(ItemsBoardReaderTest::partialValues)
                                        .toArray(ProjectItem[]::new))
                        .boardFailure(
                                new IllegalStateException(
                                        "Resource limits for this query exceeded."))
                        .boardItems(Arrays.copyOfRange(whole, 0, 12))
                        .boardItems(Arrays.copyOfRange(whole, 12, 24));

        final Snapshot snapshot = github.read();

        assertThat(github.issueBatches)
                .as("same prefix retried at half size, then the remainder")
                .containsExactly(
                        Arrays.stream(whole)
                                .map(item -> java.util.Objects.requireNonNull(item.issue()).id())
                                .toList(),
                        Arrays.stream(whole)
                                .limit(12)
                                .map(item -> java.util.Objects.requireNonNull(item.issue()).id())
                                .toList(),
                        Arrays.stream(whole)
                                .skip(12)
                                .map(item -> java.util.Objects.requireNonNull(item.issue()).id())
                                .toList());
        assertThat(snapshot.raw().values()).as("every completed item").containsExactly(whole);
    }

    @Test
    void aRestReadStillTooCostlyAtTheSmallestSizeFailsLoudly() {
        // Harm: endlessly halving a refused rest-read hangs the board or silently drops its work.
        final ProjectItem[] partial =
                java.util.stream.IntStream.rangeClosed(1, 13)
                        .mapToObj(number -> partialValues(libIssue(number, refs(false))))
                        .toArray(ProjectItem[]::new);
        final RuntimeException refused =
                new IllegalStateException("Resource limits for this query exceeded.");
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, partial)
                        .boardFailure(refused)
                        .boardFailure(refused);

        assertThatThrownBy(github::read)
                .as("the last refusal reaches the caller")
                .isInstanceOf(StoreException.class)
                .hasMessageContaining(refused.getMessage());
        assertThat(github.issueBatches)
                .as("bounded shrinking stops at twelve")
                .extracting(List::size)
                .containsExactly(13, 12);
    }

    @Test
    void aRestReadRefusalKeepsGitHubsWordsAndStatus() {
        // Harm: a GitHub refusal is hidden as a missing issue and the caller trusts a cut board.
        final GitHubException refused = GitHubExceptions.refused(403, "The project cannot be read");
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, partialValues(libIssue(3, refs(false))))
                        .boardFailure(refused);
        final AtomicInteger refusals = new AtomicInteger();
        final StoreException answer =
                new StoreException.Unreachable(CONFIG.own(), refused.getMessage());
        final ItemsBoardReader reader =
                github.reader(
                        (repository, failure, token) -> {
                            refusals.incrementAndGet();
                            assertThat(failure)
                                    .as("GitHub's original refusal passed to the refusal mapping")
                                    .isSameAs(refused);
                            assertThat(((GitHubException) failure).status())
                                    .as("GitHub's HTTP status")
                                    .isEqualTo(403);
                            return answer;
                        });

        assertThatThrownBy(() -> reader.read(CONFIG))
                .as("a refusal cannot turn into a partial board")
                .isSameAs(answer)
                .hasMessageContaining("The project cannot be read");
        assertThat(refusals).as("the refusal was passed on once").hasValue(1);
    }

    @Test
    void twoPagesOfItemsWithTheirFieldValuesBecomeTheBoardAndTheFieldIdsAreKept() {
        // Harm: a project of more than one page shows only its first, a field's value is lost on
        // the way, or a save cannot find the field it writes to.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS.subList(0, 5), "f1")
                        .fieldsPage(FIELDS.subList(5, FIELDS.size()), null)
                        .itemsPage("i1", story("High", LIB_3))
                        .itemsPage(null, libIssue(3, refs(false)));

        final Snapshot snapshot = github.read();

        final Board board = snapshot.board();
        assertThat(board.faults()).as("nothing left out").isEmpty();
        assertThat(board.stories()).as("the story of the first page").hasSize(1);
        final Story story = board.stories().get(0);
        assertThat(story.priority()).as("the story's Priority field").isEqualTo("High");
        assertThat(story.issues())
                .as("its issue from the second page")
                .containsExactly("example-lib#3");
        final Issue issue = board.issue("example-lib#3").orElseThrow();
        assertThat(issue.state()).as("the issue's Status field").isEqualTo("Backlog");
        assertThat(issue.repository()).as("the repository's short name").isEqualTo("example-lib");
        assertThat(snapshot.items().get("example-lib#3").fields())
                .as("the number fields as text")
                .containsEntry(FieldNames.RANK, "3")
                .containsEntry(FieldNames.MINUTES, "12.5");
        assertThat(snapshot.items().get("example-lib#3").comments())
                .as("comments are not read on the board")
                .isNull();
        assertThat(snapshot.config().fieldIds())
                .as("the field ids of both pages")
                .containsEntry(FieldNames.STATUS, "F_status")
                .containsEntry(FieldNames.STAGES, "F_stages");
        assertThat(snapshot.config().optionIds().get(FieldNames.STATUS))
                .as("the options' ids")
                .containsEntry("Backlog", "F_status_Backlog");
        assertThat(github.calls)
                .as("each page asked after the one before")
                .containsExactly(
                        "projectFields after null",
                        "projectFields after f1",
                        "projectItems after null",
                        "projectItems after i1");
        assertThat(github.tokenAsks).as("one token for the whole read").hasValue(1);
    }

    @Test
    void aChangeOfOneFieldAloneChangesTheRevision() {
        // Harm: a priority changed on GitHub is never seen, as the board kept looks unchanged.
        final Snapshot first =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, story("High")).read();
        final Snapshot again =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, story("High")).read();
        final Snapshot changed =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, story("Low")).read();

        assertThat(again.board().revision())
                .as("the same project read twice")
                .isEqualTo(first.board().revision());
        assertThat(changed.board().revision())
                .as("the same project with another Priority")
                .isNotEqualTo(first.board().revision());
    }

    @Test
    void anItemArchivedOnGitHubChangesTheRevision() {
        // Harm: an item archived on GitHub is still offered for work, as the board kept looks
        // unchanged.
        final Snapshot live =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, story("High")).read();
        final Snapshot archived =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, story(true, "High")).read();

        assertThat(archived.board().revision())
                .as("the same project with its story archived")
                .isNotEqualTo(live.board().revision());
        assertThat(live.board().stories()).as("the live story on the board").hasSize(1);
        assertThat(archived.board().stories()).as("the archived story off the board").isEmpty();
    }

    @Test
    void aDraftItemIsLeftOutWithoutAFault() {
        // Harm: a draft note on the project fails the board or shows as a fault on every read.
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(
                                null,
                                story("High"),
                                new ProjectItem(
                                        "PVTI_draft",
                                        false,
                                        null,
                                        List.of(option("F_status", "Idea")),
                                        new GitHubGraphQl.PageInfo(false, null)))
                        .read();

        assertThat(snapshot.board().stories())
                .as("only the issue's item")
                .extracting(Story::id)
                .containsExactly("example-project#1");
        assertThat(snapshot.board().faults()).as("a draft is no fault").isEmpty();
    }

    @Test
    void anItemWithASecondPageOfSubIssuesIsLeftOutAndItsStoryIsNotWhole() {
        // Harm: an issue shown with only its first hundred parts, and its story counted as whole
        // and taken for done while parts are missing.
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), libIssue(3, refs(true, "I_part")))
                        .boardItems(libIssue(3, refs(true, "I_part")))
                        .read();

        assertThat(snapshot.board().issue("example-lib#3")).as("the issue left out").isEmpty();
        assertThat(snapshot.board().faults())
                .as("the one fault naming it, never one calling it outside the project")
                .containsExactly(
                        "Item example-org/example-lib#3 has more sub-issues or blockers than one"
                                + " page carries and is left out; its story is not whole");
        assertThat(snapshot.board().stories().get(0).issuesWhole()).as("its story").isFalse();
    }

    @Test
    void anItemWithASecondPageOfFieldValuesIsLeftOutAndKeptMarkedInItsCopy() {
        // Harm: an issue mapped from its first fifty values, its Status or Size among those cut
        // off, shown and copied as if whole.
        final ProjectItem cut = libIssue(3, refs(false));
        final ProjectItem partial =
                new ProjectItem(
                        cut.id(),
                        cut.archived(),
                        cut.issue(),
                        cut.values(),
                        new GitHubGraphQl.PageInfo(true, "values-2"));
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), partial)
                        .boardItems(partial)
                        .read();

        assertThat(snapshot.board().issue("example-lib#3")).as("the issue left out").isEmpty();
        assertThat(snapshot.board().faults())
                .as("the fault keeps an incomplete item off the board")
                .containsExactly(
                        "Item example-org/example-lib#3 has more labels or field values than one"
                                + " page carries and is left out");
        assertThat(BoardCopyMapping.tracked(partial).partial()).as("its copy").isTrue();
        assertThat(
                        BoardCopyMapping.item(BoardCopyMapping.tracked(partial))
                                .valuesPage()
                                .hasNextPage())
                .as("read back from its copy, still not whole")
                .isTrue();
    }

    @Test
    void anIssueBlockedByAnItemLeftOutForItsPagesIsLeftOutAsDependingOnIt() {
        // Harm: an issue offered for work while its blocker is unknown, or told as blocked by
        // something outside the project and sent looking for it there.
        final ProjectItem blocked =
                new ProjectItem(
                        "PVTI_lib_4",
                        false,
                        issue(
                                OWNER + "/example-lib",
                                4,
                                List.of(),
                                node(OWNER + "/example-project", 1),
                                refs(false),
                                refs(false, LIB_3)),
                        List.of(option("F_status", "Backlog")),
                        new GitHubGraphQl.PageInfo(false, null));
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(
                                null,
                                story("High", LIB_3),
                                libIssue(3, refs(true, "I_part")),
                                blocked)
                        .boardItems(libIssue(3, refs(true, "I_part")))
                        .read();

        assertThat(snapshot.board().issues()).as("neither issue on the board").isEmpty();
        assertThat(snapshot.board().faults())
                .as("the item left out, and the issue that depends on it")
                .containsExactly(
                        "Item example-org/example-lib#3 has more sub-issues or blockers than one"
                                + " page carries and is left out; its story is not whole",
                        "example-lib#4 depends on an issue left out and is left out too");
    }

    @Test
    void anItemOfTheOwnersRepositoryWithANameThePlannerDoesNotTakeIsOutsideItsRepositories() {
        // Harm: a person told the item belongs to another owner and sent to look for it there.
        final ProjectItem odd =
                new ProjectItem(
                        "PVTI_odd",
                        false,
                        issue(OWNER + "/_repo", 3, List.of(), null, refs(false)),
                        List.of(option("F_status", "Idea")),
                        new GitHubGraphQl.PageInfo(false, null));

        final Snapshot snapshot = new GitHub().fieldsPage(FIELDS, null).itemsPage(null, odd).read();

        assertThat(snapshot.board().faults())
                .as("the fault naming where it is")
                .containsExactly(
                        "Item example-org/_repo#3 lies outside the project's repositories and is"
                                + " left out");
    }

    @Test
    void anItemOfARepositoryGitHubNamesInCapitalsIsKnownByItsLowercasedReference() {
        // Harm: an issue of a repository made with capitals is left out of the board, or known by
        // a reference no call can name.
        final ProjectItem capitals =
                new ProjectItem(
                        "PVTI_capitals",
                        false,
                        issue(OWNER + "/Example-Lib", 3, List.of(), null, refs(false)),
                        List.of(option("F_status", "Idea")),
                        new GitHubGraphQl.PageInfo(false, null));

        final Snapshot snapshot =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, capitals).read();

        assertThat(snapshot.board().faults()).as("no fault").isEmpty();
        assertThat(snapshot.board().issues())
                .as("the issue by its lowercased reference")
                .extracting(Issue::id, Issue::repository)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("example-lib#3", "example-lib"));
        assertThat(snapshot.items()).as("its item by that reference").containsKey("example-lib#3");
    }

    @Test
    void aRepositoryGitHubNamesInNoFormOfAnAddressIsNotRepeatedInTheFault() {
        // Harm: words from GitHub's answer, a line break among them, written into a fault the
        // window shows and the log keeps.
        final ProjectItem odd =
                new ProjectItem(
                        "PVTI_odd",
                        false,
                        issue(OWNER + "/lib\nInjected line", 3, List.of(), null, refs(false)),
                        List.of(option("F_status", "Idea")),
                        new GitHubGraphQl.PageInfo(false, null));

        final Snapshot snapshot = new GitHub().fieldsPage(FIELDS, null).itemsPage(null, odd).read();

        assertThat(snapshot.board().faults())
                .as("the fault without GitHub's words")
                .containsExactly("An item lies outside the project's owner and is left out");
    }

    private static List<ProjectField> fieldsWhere(
            final java.util.function.Function<ProjectField, @Nullable ProjectField> change) {
        final List<ProjectField> fields = new ArrayList<>();
        for (final ProjectField field : FIELDS) {
            final ProjectField changed = change.apply(field);
            if (changed != null) {
                fields.add(changed);
            }
        }
        return fields;
    }

    @Test
    void aFieldTheProjectLacksOrHoldsOfAnotherKindIsAFaultNamedOnce() {
        // Harm: a save writes to a field the project lacks, or one field of another kind named as
        // lacking every option besides.
        final List<ProjectField> fields =
                fieldsWhere(
                        field ->
                                switch (field.name()) {
                                    case FieldNames.ESTIMATE -> null;
                                    case FieldNames.WORKER ->
                                            field("F_worker", FieldNames.WORKER, "TEXT");
                                    default -> field;
                                });

        final Snapshot snapshot =
                new GitHub().fieldsPage(fields, null).itemsPage(null, story("High")).read();

        assertThat(snapshot.board().faults())
                .as("one fault for each field")
                .containsExactly(
                        "The project has no field Estimate",
                        "The project's field Worker is not of the kind used");
    }

    @Test
    void anOptionTheStoreWritesThatAFieldLacksIsAFault() {
        // Harm: a save of a state or a risk the project's field cannot hold, found only when it
        // fails halfway.
        final List<ProjectField> fields =
                fieldsWhere(
                        field ->
                                switch (field.name()) {
                                    case FieldNames.STATUS ->
                                            select(
                                                    "F_status",
                                                    FieldNames.STATUS,
                                                    "Idea",
                                                    "Finished");
                                    case FieldNames.RISK ->
                                            select("F_risk", FieldNames.RISK, "harm", "ordinary");
                                    default -> field;
                                });

        final Snapshot snapshot =
                new GitHub().fieldsPage(fields, null).itemsPage(null, story("High")).read();

        assertThat(snapshot.board().faults())
                .as("each option missing")
                .containsExactly(
                        "The project's field Status has no option Backlog",
                        "The project's field Risk has no option small");
    }

    @Test
    void aSecondFieldOfOneNameWithoutOptionsKeepsNoOptionOfTheFirst() {
        // Harm: a save writes an option's id of one field to another field of the same name.
        final List<ProjectField> fields = new ArrayList<>(FIELDS);
        fields.add(field("F_status_text", FieldNames.STATUS, "TEXT"));

        final Snapshot snapshot =
                new GitHub().fieldsPage(fields, null).itemsPage(null, story("High")).read();

        assertThat(snapshot.config().fieldIds())
                .as("the field the name stands for")
                .containsEntry(FieldNames.STATUS, "F_status_text");
        assertThat(snapshot.config().optionIds())
                .as("no options of the field before")
                .doesNotContainKey(FieldNames.STATUS);
    }

    @Test
    void aPageWithMoreItemsOrFieldsThanAskedForIsUnreadable() {
        // Harm: an answer of any size held and mapped, the board's bound passed by one page.
        final ProjectItem[] items = new ProjectItem[ItemsBoardReader.PAGE_SIZE + 1];
        for (int i = 0; i < items.length; i++) {
            items[i] = libIssue(i + 1, refs(false));
        }
        assertThatThrownBy(
                        () -> new GitHub().fieldsPage(FIELDS, null).itemsPage(null, items).read())
                .as("a page of items beyond the size asked for")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("more items than asked for");

        final List<ProjectField> fields = new ArrayList<>();
        for (int i = 0; i <= ItemsBoardReader.PAGE_SIZE; i++) {
            fields.add(field("F_extra_" + i, "Extra " + i, "TEXT"));
        }
        assertThatThrownBy(() -> new GitHub().fieldsPage(fields, null).read())
                .as("a page of fields beyond the size asked for")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("more fields than asked for");
    }

    @Test
    void aBlankCursorIsRefused() {
        // Harm: a read that asks GitHub again and again for a page named by nothing.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage("   ", libIssue(3, refs(false)))
                        .itemsPage(null, libIssue(4, refs(false)));

        assertThatThrownBy(github::read)
                .as("a cursor of blanks")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("without a new place to read on");
        assertThat(github.calls).as("no page asked after it").hasSize(2);
    }

    @Test
    void aReadWhoseLastAnswerCameAfterTheDeadlineIsUnreachable() {
        // Harm: a board answered as current although GitHub took longer than the window waits.
        final GitHub github =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, libIssue(3, refs(false)));
        final Instant start = Instant.parse("2026-01-01T00:00:00Z");
        final AtomicInteger ticks = new AtomicInteger();
        final InstantSource slow = () -> start.plusSeconds(25L * ticks.getAndIncrement());
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        github.queries(),
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        FIRST_OPEN,
                        slow,
                        Duration.ofSeconds(60));

        assertThatThrownBy(() -> reader.read(CONFIG))
                .as("the read whose last page came late")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageContaining("within 60 s");
        assertThat(github.calls).as("every page was asked for").hasSize(2);
    }

    @Test
    void aFaultOfTheProjectAloneChangesTheRevision() {
        // Harm: a field added on GitHub to mend a fault never reaches the window, as the board
        // kept with the fault looks unchanged.
        final Snapshot lacking =
                new GitHub()
                        .fieldsPage(
                                fieldsWhere(
                                        field ->
                                                field.name().equals(FieldNames.ESTIMATE)
                                                        ? null
                                                        : field),
                                null)
                        .itemsPage(null, story("High"))
                        .read();
        final Snapshot mended =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, story("High")).read();

        assertThat(lacking.board().faults()).as("the fault before").isNotEmpty();
        assertThat(mended.board().revision())
                .as("the same items once the field is there")
                .isNotEqualTo(lacking.board().revision());
    }

    private static ProjectItem withIssue(
            final ProjectItem item, final java.util.function.UnaryOperator<BoardIssue> change) {
        return new ProjectItem(
                item.id(),
                item.archived(),
                change.apply(java.util.Objects.requireNonNull(item.issue())),
                item.values(),
                item.valuesPage());
    }

    private static BoardIssue changed(
            final BoardIssue issue,
            final String title,
            final @Nullable String body,
            final @Nullable String stateReason,
            final List<String> labels) {
        return new BoardIssue(
                issue.id(),
                issue.databaseId(),
                issue.number(),
                title,
                body,
                issue.state(),
                stateReason,
                issue.updatedAt(),
                issue.repository(),
                labels,
                issue.parentId(),
                issue.subIssues(),
                issue.blockedBy(),
                new GitHubGraphQl.PageInfo(false, null));
    }

    @Test
    void anEditOfAnIssuesWordsWithinOneSecondChangesTheRevision() {
        // Harm: a title, body, label or reason edited twice within one second on GitHub never
        // reaches the window, as GitHub's change time stayed the same.
        final ProjectItem base = story("High");
        final BoardIssue issue = java.util.Objects.requireNonNull(base.issue());
        final List<String> labels = new ArrayList<>(issue.labels());
        labels.add("bug");
        final Map<String, ProjectItem> edits =
                Map.of(
                        "the title",
                        withIssue(
                                base,
                                i ->
                                        changed(
                                                i,
                                                "Another title",
                                                i.body(),
                                                i.stateReason(),
                                                i.labels())),
                        "the body",
                        withIssue(
                                base,
                                i ->
                                        changed(
                                                i,
                                                i.title(),
                                                "Another body",
                                                i.stateReason(),
                                                i.labels())),
                        "the reason",
                        withIssue(
                                base,
                                i -> changed(i, i.title(), i.body(), "NOT_PLANNED", i.labels())),
                        "the labels",
                        withIssue(
                                base,
                                i -> changed(i, i.title(), i.body(), i.stateReason(), labels)));
        final Snapshot before = new GitHub().fieldsPage(FIELDS, null).itemsPage(null, base).read();

        edits.forEach(
                (what, edited) ->
                        assertThat(
                                        new GitHub()
                                                .fieldsPage(FIELDS, null)
                                                .itemsPage(null, edited)
                                                .read()
                                                .board()
                                                .revision())
                                .as(what + " edited")
                                .isNotEqualTo(before.board().revision()));
    }

    @Test
    void anItemOfAnotherOwnersRepositoryIsLeftOutAsAFault() {
        // Harm: an issue of a stranger's repository shown and worked as the project's own.
        final ProjectItem stranger =
                new ProjectItem(
                        "PVTI_stranger",
                        false,
                        issue("other-org/example-lib", 3, List.of(), null, refs(false)),
                        List.of(option("F_status", "Idea")),
                        new GitHubGraphQl.PageInfo(false, null));

        final Snapshot snapshot =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, stranger).read();

        assertThat(snapshot.board().issues()).as("nothing of the stranger").isEmpty();
        assertThat(snapshot.board().faults())
                .as("the fault naming it")
                .containsExactly(
                        "Item other-org/example-lib#3 lies outside the project's owner and is left"
                                + " out");
    }

    @Test
    void anItemSeenOnTwoPagesCountsOnce() {
        // Harm: an item GitHub moves while the pages are read shows as two items with one id,
        // and both are left out as repeating it.
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage("i1", story("High", LIB_3), libIssue(3, refs(false)))
                        .itemsPage(null, libIssue(3, refs(false)))
                        .read();

        assertThat(snapshot.board().issues())
                .extracting(Issue::id)
                .as("one issue")
                .containsExactly("example-lib#3");
        assertThat(snapshot.board().faults()).as("no repeated id").isEmpty();
    }

    @Test
    void aProjectBeyondTheBoundIsUnreadableAndNeverACutBoard() {
        // Harm: a board cut at the bound shown as the whole project, its missing stories taken as
        // gone.
        final GitHub github = new GitHub().fieldsPage(FIELDS, null);
        for (int page = 1; page <= ItemsBoardReader.MOST_PAGES; page++) {
            github.itemsPage("i" + page, libIssue(page, refs(false)));
        }

        assertThatThrownBy(github::read)
                .as("the read beyond the bound")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("more items than " + ItemsBoardReader.MOST_PAGES + " pages");
        assertThat(github.calls)
                .as("no page beyond the bound asked for")
                .hasSize(1 + ItemsBoardReader.MOST_PAGES);
    }

    @Test
    void aCursorThatDoesNotAdvanceIsRefused() {
        // Harm: a read that asks for the same page for ever, or until the bound, on a GitHub
        // that answers the same cursor again.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage("i1", libIssue(3, refs(false)))
                        .itemsPage("i1", libIssue(4, refs(false)));

        assertThatThrownBy(github::read)
                .as("the cursor given again")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("without a new place to read on");
        assertThat(github.calls).as("no third page asked for").hasSize(3);
    }

    @Test
    void aRateLimitIsUnreachableNamingTheWaitAndNeverMissingRights() {
        // Harm: a person told the token lacks rights and sent to change it, when GitHub only asks
        // to wait.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsFailure(
                                GitHubExceptions.rateLimited(
                                        403, "API rate limit exceeded", Duration.ofSeconds(90)));
        final AccessRefusals rightsMissing =
                (repository, failure, token) ->
                        new StoreException.NoForgeAccess("https://github.com", "issues");

        assertThatThrownBy(() -> github.reader(rightsMissing).read(CONFIG))
                .as("the rate limited read")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageContaining("rate limit")
                .hasMessageContaining("90 s");
    }

    @Test
    void aTokenInAFailuresWordsIsScrubbedWithTheTokenTheReadUsed() {
        // Harm: the person's token shown in the window or written to the log, or asked for a
        // second time to scrub a failure.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsFailure(
                                GitHubExceptions.refused(502, "Bad gateway for Bearer " + TOKEN))
                        // A failure of the gateway is asked once more.
                        .itemsFailure(
                                GitHubExceptions.refused(502, "Bad gateway for Bearer " + TOKEN));

        assertThatThrownBy(github::read)
                .as("a failure that names the token")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageNotContaining(TOKEN)
                .hasMessageContaining(Scrubbed.MARK);
        assertThat(github.tokenAsks).as("no second ask for the token").hasValue(1);
    }

    @Test
    void aReadPastItsDeadlineIsUnreachable() {
        // Harm: a slow GitHub holds the board and every save behind it for minutes.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage("i1", libIssue(3, refs(false)))
                        .itemsPage(null, libIssue(4, refs(false)));
        final Instant start = Instant.parse("2026-01-01T00:00:00Z");
        final AtomicInteger ticks = new AtomicInteger();
        final InstantSource slow = () -> start.plusSeconds(25L * ticks.getAndIncrement());
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        github.queries(),
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        FIRST_OPEN,
                        slow,
                        Duration.ofSeconds(60));

        assertThatThrownBy(() -> reader.read(CONFIG))
                .as("the read past its deadline")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageContaining("within 60 s");
        assertThat(github.calls).as("no call after the deadline").hasSize(2);
    }

    @Test
    void aPageGitHubFindsTooCostlyIsAskedAgainHalvedFromTheSameCursor() {
        // Harm: a board whose items cost GitHub too much to answer in pages of 100 is never read
        // again, and the window shows only GitHub's refusal.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsFailure(
                                new IllegalStateException(
                                        "Resource limits for this query exceeded."))
                        .itemsPage(null, story("High", LIB_3), libIssue(3, refs(false)));

        final Snapshot read = github.read();

        assertThat(read.items()).as("the items read at the smaller page").hasSize(2);
        assertThat(github.calls)
                .as("the same cursor asked again for half the page")
                .containsSubsequence("projectItems after null", "projectItems after null of 50");
    }

    @Test
    void aPageStillTooCostlyAtTheSmallestSizeFailsTheRead() {
        // Harm: a read that halves without end, or one that quietly answers a part of the board.
        final RuntimeException tooCostly =
                new IllegalStateException("Resource limits for this query exceeded.");
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsFailure(tooCostly)
                        .itemsFailure(tooCostly)
                        .itemsFailure(tooCostly)
                        .itemsFailure(tooCostly);

        assertThatThrownBy(github::read)
                .as("the read refused at 12 items a page")
                .isInstanceOf(StoreException.class);
        assertThat(github.calls)
                .as("the page sizes asked")
                .containsSubsequence(
                        "projectItems after null",
                        "projectItems after null of 50",
                        "projectItems after null of 25",
                        "projectItems after null of 12");
    }

    @Test
    void aBoardReadSendsNoMutation() {
        // Harm: reading the board changes the person's project, its fields, options or sums.
        final List<String> reads =
                Arrays.stream(ProjectQueries.class.getMethods())
                        .filter(method -> !Modifier.isStatic(method.getModifiers()))
                        .map(Method::getName)
                        .distinct()
                        .sorted()
                        .toList();
        assertThat(reads)
                .as("all a board read can call")
                .containsExactly("issueBoard", "projectFields", "projectItems");

        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), libIssue(3, refs(false)));
        github.read();
        assertThat(github.calls)
                .as("only the reads were sent")
                .allMatch(
                        call ->
                                call.startsWith("projectFields")
                                        || call.startsWith("projectItems")
                                        || call.startsWith("issueBoard"));
    }

    @Test
    void aReadStoppedWhileItWaitsForTheItemListIsUnreachableAndKeepsTheThreadStopped() {
        // Harm: a stopped read that is swallowed keeps the thread working after its owner asked
        // it to stop, or answers a board as whole that it never finished reading.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3))
                        .issuesOnProject(true);
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        github.queries(),
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        FIRST_OPEN,
                        InstantSource.system(),
                        ItemsBoardReader.DEADLINE,
                        length -> {
                            throw new InterruptedException("stopped");
                        });
        try {
            assertThatThrownBy(() -> reader.read(CONFIG))
                    .as("the read stopped while it waited")
                    .isInstanceOf(StoreException.Unreachable.class);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the thread still told to stop")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aSubIssueTheItemListLacksButThatNamesTheProjectIsReadOnceTheListHoldsIt() {
        // Harm: a person who adds an issue to the project and opens the board at once sees a
        // fault they cannot act on, and the story without the issue.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3))
                        .issuesOnProject(true)
                        .itemsPage(null, story("High", LIB_3), libIssue(3, refs(false)));

        final Board board = github.read().board();

        assertThat(board.faults()).as("no fault once the list holds the issue").isEmpty();
        assertThat(board.stories().get(0).issues())
                .as("the story with its issue")
                .containsExactly("example-lib#3");
        assertThat(github.calls)
                .as("the issue's own projects asked once, then the list read again")
                .containsExactly(
                        "projectFields after null",
                        "projectItems after null",
                        "issueBoard of [" + LIB_3 + "] in PVT_1",
                        "projectItems after null");
        assertThat(github.pauses)
                .as("one pause before the read again")
                .containsExactly(ItemsBoardReader.REREAD_PAUSE);
    }

    @Test
    void aSubIssueWhoseOwnProjectItemsNameNoProjectIsAFaultWithoutAReadAgain() {
        // Harm: an issue really outside the project hides from the person, or holds every board
        // read for seconds and spends the person's rate limit on reads again.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3))
                        .issuesOnProject(false);

        final Board board = github.read().board();

        assertThat(board.faults())
                .as("the sub-issue outside the project named")
                .containsExactly("example-project#1 has a sub-issue outside the project");
        assertThat(github.calls)
                .as("no read again of the list")
                .containsExactly(
                        "projectFields after null",
                        "projectItems after null",
                        "issueBoard of [" + LIB_3 + "] in PVT_1");
        assertThat(github.pauses).as("no pause").isEmpty();
    }

    @Test
    void aSubIssueTheItemListStillLacksAfterTheLastReadAgainIsAFault() {
        // Harm: a board read that waits on GitHub's list without end, or shows a story as whole
        // while an issue of it is missing.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3))
                        .issuesOnProject(true);
        for (int again = 0; again < ItemsBoardReader.MOST_REREADS; again++) {
            github.itemsPage(null, story("High", LIB_3));
        }

        final Board board = github.read().board();

        assertThat(board.faults())
                .as("the sub-issue still missing named")
                .containsExactly("example-project#1 has a sub-issue outside the project");
        assertThat(github.calls.stream().filter(call -> call.startsWith("projectItems")))
                .as("the list read once and then as often as the bound allows")
                .hasSize(1 + ItemsBoardReader.MOST_REREADS);
        assertThat(github.calls.stream().filter(call -> call.startsWith("issueBoard")))
                .as("the issue's own projects asked once")
                .hasSize(1);
        assertThat(github.pauses)
                .as("a pause before each read again")
                .hasSize(ItemsBoardReader.MOST_REREADS);
    }

    @Test
    void aBoardWhoseStoriesHaveEverySubIssueListedAsksForNoIssuesProjects() {
        // Harm: every board read spends the person's rate limit on a read only a missing
        // sub-issue needs.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), libIssue(3, refs(false)));

        github.read();

        assertThat(github.calls)
                .as("only the fields and the items")
                .containsExactly("projectFields after null", "projectItems after null");
        assertThat(github.pauses).as("no pause").isEmpty();
    }

    @Test
    void aListedSubIssueOfARepositoryTheProjectDoesNotPlanAsksForNoIssuesProjects() {
        // Harm: a sub-issue in a repository outside the project, which the list holds, holds
        // every board read for seconds and spends the person's rate limit on reads again.
        final String other = node(OWNER + "/example-other", 5);
        final ProjectItem otherIssue =
                new ProjectItem(
                        "PVTI_other_5",
                        false,
                        issue(
                                OWNER + "/example-other",
                                5,
                                List.of(),
                                node(OWNER + "/example-project", 1),
                                refs(false)),
                        List.of(option("F_status", "Backlog")),
                        new GitHubGraphQl.PageInfo(false, null));
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", other), otherIssue);

        final Board board = github.read().board();

        assertThat(board.faults())
                .as("the sub-issue named outside the project")
                .contains("example-project#1 has a sub-issue outside the project");
        assertThat(github.calls)
                .as("only the fields and the items")
                .containsExactly("projectFields after null", "projectItems after null");
    }

    @Test
    void anIssueWithAStatusTheWorkflowLacksIsReadOutsideTheBoardAndOnItOnceMovedIntoIt() {
        // Harm: an issue whose Status a person set outside the workflow vanishes from the window
        // with no way to move it back, or shows on the board and outside it at once.
        final ProjectItem accepted =
                new ProjectItem(
                        "PVTI_lib_3",
                        false,
                        issue(
                                OWNER + "/example-lib",
                                3,
                                List.of(),
                                node(OWNER + "/example-project", 1),
                                refs(false)),
                        List.of(option("F_status", "Accepted"), option("F_worker", "Manual")),
                        new GitHubGraphQl.PageInfo(false, null));

        final Board before =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), accepted)
                        .read()
                        .board();

        assertThat(before.issues()).as("not on the board").isEmpty();
        assertThat(before.outside())
                .as("listed outside it, under its story")
                .containsExactly(
                        new OutsideIssue(
                                "example-lib#3",
                                "example-lib",
                                "A title",
                                "Accepted",
                                "example-project#1",
                                null,
                                URI.create("https://github.com/" + OWNER + "/example-lib/issues/3"),
                                Worker.MANUAL));
        assertThat(before.faults())
                .as("named with how a person repairs it")
                .contains(
                        "example-lib#3 has the Status Accepted, which the workflow does not hold;"
                                + " move it into a state of the board to repair it");

        final ProjectItem moved =
                new ProjectItem(
                        accepted.id(),
                        false,
                        accepted.issue(),
                        List.of(option("F_status", "Backlog"), option("F_worker", "Manual")),
                        new GitHubGraphQl.PageInfo(false, null));
        final Board after =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), moved)
                        .read()
                        .board();

        assertThat(after.issue("example-lib#3").map(Issue::state))
                .as("on the board in the state it was moved to")
                .contains("Backlog");
        assertThat(after.stories().get(0).issues())
                .as("under its story again")
                .containsExactly("example-lib#3");
        assertThat(after.outside()).as("no longer outside").isEmpty();
    }

    @Test
    void aSnapshotThroughItsCopyGivesTheSameBoardAndRevision() {
        // Harm: a board shown at start from its copy that differs from the one GitHub gave, or
        // whose revision refuses the first save made against it.
        final List<ProjectField> fields = new ArrayList<>(FIELDS);
        // A second field of one name: its values are still read by the field's id.
        fields.add(field("F_rank_new", FieldNames.RANK, "NUMBER"));
        // An item more than one page carries, asked again and still more, stays a fault.
        final ProjectItem overflowing = libIssue(4, refs(true, "I_part"));
        final GitHub github =
                new GitHub()
                        .fieldsPage(fields, null)
                        .itemsPage(
                                null,
                                story("High", LIB_3, node(OWNER + "/example-lib", 4)),
                                libIssue(3, refs(false)),
                                overflowing,
                                new ProjectItem(
                                        "PVTI_draft",
                                        true,
                                        null,
                                        List.of(),
                                        new GitHubGraphQl.PageInfo(false, null)))
                        .boardPage(onBoard(overflowing));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot read = reader.read(CONFIG);

        final BoardCopy copy = reader.toCopy(read, ChangeCursor.NONE, List.of());
        assertThat(copy).as("the copy of a snapshot read from GitHub").isNotNull();
        final Snapshot loaded = reader.fromCopy(copy, CONFIG);

        assertThat(read.board().faults()).as("the faults the copy must keep").isNotEmpty();
        assertThat(loaded).as("the snapshot from the copy").isNotNull();
        assertThat(loaded.board().revision())
                .as("the revision from the copy")
                .isEqualTo(read.board().revision());
        assertThat(loaded.board()).as("the board from the copy").isEqualTo(read.board());
        assertThat(loaded).as("the whole snapshot from the copy").isEqualTo(read);
        assertThat(github.calls)
                .as("GitHub asked only by the read itself")
                .containsExactly(
                        "projectFields after null",
                        "projectItems after null",
                        "issueBoard of [" + overflowing.issue().id() + "] in PVT_1");
    }

    @Test
    void aCopyOfOtherProjectFilesOrAnotherProjectGivesNoSnapshot() {
        // Harm: a board held under another workflow or of another project shown as this one.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), libIssue(3, refs(false)));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final BoardCopy copy = reader.toCopy(reader.read(CONFIG), ChangeCursor.NONE, List.of());
        assertThat(copy).as("the copy").isNotNull();

        for (final ProjectConfig other :
                List.of(
                        new ProjectConfig(
                                CONFIG.forge(),
                                CONFIG.project(),
                                "beef001",
                                CONFIG.workflow(),
                                CONFIG.work(),
                                CONFIG.repositories(),
                                Map.of(),
                                Map.of()),
                        new ProjectConfig(
                                CONFIG.forge(),
                                new ProjectRef(OWNER, 8),
                                CONFIG.commit(),
                                CONFIG.workflow(),
                                CONFIG.work(),
                                CONFIG.repositories(),
                                Map.of(),
                                Map.of()),
                        new ProjectConfig(
                                CONFIG.forge(),
                                CONFIG.project(),
                                CONFIG.commit(),
                                CONFIG.workflow(),
                                CONFIG.work(),
                                List.of("example-project"),
                                Map.of(),
                                Map.of()))) {
            assertThat(reader.fromCopy(copy, other)).as("the copy read for %s", other).isNull();
        }
    }

    @Test
    void aSnapshotNotReadFromGitHubGivesNoCopy() {
        // Harm: a board made without GitHub's items kept and shown at start as an empty board.
        final Snapshot made =
                new Snapshot(
                        new GitHub().fieldsPage(FIELDS, null).itemsPage(null).read().board(),
                        Map.of(),
                        CONFIG);

        assertThat(
                        new GitHub()
                                .reader(AccessRefusals.DEFAULT)
                                .toCopy(made, ChangeCursor.NONE, List.of()))
                .as("the copy of a snapshot not read from GitHub")
                .isNull();
    }

    /** An issue of the library, under the story, blocked by the issues named. */
    private static ProjectItem waiting(final int number, final IssueRef... blockers) {
        final ProjectItem plain = libIssue(number, refs(false));
        final BoardIssue issue = java.util.Objects.requireNonNull(plain.issue());
        return new ProjectItem(
                plain.id(),
                false,
                issue(
                        issue.repository(),
                        number,
                        List.of(),
                        issue.parentId(),
                        refs(false),
                        new IssueRefs(List.of(blockers), new PageInfo(false, null))),
                plain.values(),
                plain.valuesPage());
    }

    /** An issue of another owner's repository as GitHub answers it by its id. */
    private static IssueOnBoard elsewhere(final IssueRef ref, final String state) {
        return new IssueOnBoard(
                new BoardIssue(
                        ref.id(),
                        ref.databaseId(),
                        ref.number(),
                        "Not the project's",
                        "",
                        state,
                        null,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        ref.repository(),
                        List.of(),
                        null,
                        refs(false),
                        refs(false),
                        new GitHubGraphQl.PageInfo(false, null)),
                null,
                List.of(),
                new GitHubGraphQl.PageInfo(false, null));
    }

    private static final IssueRef FAR = new IssueRef("I_far_7", 70L, 7, "other-org/elsewhere");

    private static final IssueRef HIDDEN = new IssueRef("I_far_8", 80L, 8, "other-org/private");

    @Test
    void aBlockerNoItemHoldsIsReadByItsIdAndOneTheTokenCannotReadIsNotKnownDone() {
        // Harm: a wait the person's token cannot read, or one never read, taken as done, so work
        // starts on an issue whose blocker is still open.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), waiting(3, FAR, HIDDEN))
                        .boardPage(elsewhere(FAR, "CLOSED"), null);

        final Snapshot snapshot = github.read();

        assertThat(github.calls)
                .as("the item list, then the blockers no item holds, in one read")
                .containsExactly(
                        "projectFields after null",
                        "projectItems after null",
                        "issueBoard of [I_far_7, I_far_8] in PVT_1");
        assertThat(snapshot.board().faults()).as("no fault for a wait").isEmpty();
        assertThat(snapshot.board().issue("example-lib#3").orElseThrow().waitsOn())
                .as("the closed blocker done, the unreadable one unknown")
                .extracting(Wait::reference, Wait::done)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("other-org/elsewhere#7", true),
                        org.assertj.core.groups.Tuple.tuple("other-org/private#8", null));
    }

    @Test
    void aWaitOpenAgainGivesAnotherRevision() {
        // Harm: a blocker reopened outside the project that the window is never told of, so it
        // still shows its issue free.
        final Snapshot done =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), waiting(3, FAR))
                        .boardPage(elsewhere(FAR, "CLOSED"))
                        .read();
        final Snapshot open =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), waiting(3, FAR))
                        .boardPage(elsewhere(FAR, "OPEN"))
                        .read();

        assertThat(open.board().revision())
                .as("the revision once the wait opened again")
                .isNotEqualTo(done.board().revision());
    }

    @Test
    void aReadAfterASaveReadsOnlyTheTouchedIssuesBlockersAgain() {
        // Harm: every save pays a read of every wait on the board, so a busy board spends the
        // person's rate limit on what did not change.
        final ProjectItem three = waiting(3, FAR);
        final ProjectItem four = waiting(4, HIDDEN);
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), three, four)
                        .boardPage(elsewhere(FAR, "OPEN"), elsewhere(HIDDEN, "OPEN"))
                        .boardItems(three)
                        .boardPage(elsewhere(FAR, "CLOSED"));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot held = reader.read(CONFIG);

        final Snapshot after =
                reader.reread(
                        CONFIG, held, new Touched.Ids(Set.of(LIB_3), Set.of(), Set.of(), Set.of()));

        assertThat(github.issueBatches)
                .as("the touched issue, then its blocker alone")
                .containsExactly(List.of("I_far_7", "I_far_8"), List.of(LIB_3), List.of("I_far_7"));
        assertThat(after.board().issue("example-lib#3").orElseThrow().waitsOn())
                .as("the touched issue's wait read again")
                .extracting(Wait::done)
                .containsExactly(true);
        assertThat(after.board().issue("example-lib#4").orElseThrow().waitsOn())
                .as("the other issue's wait kept as held")
                .extracting(Wait::done)
                .containsExactly(false);
    }

    @Test
    void aReadOfTheWaitsAloneReadsEveryWaitAndKeepsTheBoardWhenNoneChanged() {
        // Harm: a blocker closed in another repository that the board never takes in, so its
        // issue is never offered; or a board replaced on every look though nothing changed.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), waiting(3, FAR, HIDDEN))
                        .boardPage(elsewhere(FAR, "OPEN"), elsewhere(HIDDEN, "OPEN"))
                        .boardPage(elsewhere(FAR, "OPEN"), elsewhere(HIDDEN, "OPEN"))
                        .boardPage(elsewhere(FAR, "CLOSED"), elsewhere(HIDDEN, "OPEN"));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot held = reader.read(CONFIG);

        assertThat(reader.rewait(CONFIG, held)).as("nothing changed").isSameAs(held);
        final Snapshot closed = reader.rewait(CONFIG, held);

        assertThat(closed.board().issue("example-lib#3").orElseThrow().waitsOn())
                .as("the closed one done")
                .extracting(Wait::done)
                .containsExactly(true, false);
        assertThat(closed.board().revision())
                .as("a new revision")
                .isNotEqualTo(held.board().revision());
        assertThat(github.issueBatches)
                .as("every wait read each time, in one read")
                .containsExactly(
                        List.of("I_far_7", "I_far_8"),
                        List.of("I_far_7", "I_far_8"),
                        List.of("I_far_7", "I_far_8"));
    }

    @Test
    void aCopyKeepsTheWaitsAndTheirState() {
        // Harm: a board shown at start from its copy whose waiting issue is offered, its wait lost.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), waiting(3, FAR, HIDDEN))
                        .boardPage(elsewhere(FAR, "OPEN"), null);
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot read = reader.read(CONFIG);

        final BoardCopy copy = reader.toCopy(read, ChangeCursor.NONE, List.of());
        assertThat(copy).as("the copy").isNotNull();
        final Snapshot loaded = reader.fromCopy(copy, CONFIG);

        assertThat(loaded).as("the snapshot from the copy, waits included").isEqualTo(read);
        assertThat(loaded.board().issue("example-lib#3").orElseThrow().waitsOn())
                .as("both waits still holding")
                .extracting(Wait::done)
                .containsExactly(false, null);
    }
}

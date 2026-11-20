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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl.BoardIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldOption;
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
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
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
                    select("F_risk", FieldNames.RISK, "Ordinary"),
                    select("F_worker", FieldNames.WORKER, "Agent", "Manual"),
                    field("F_id", FieldNames.ID, "TEXT"),
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
        private final List<String> calls = new ArrayList<>();
        private final AtomicInteger tokenAsks = new AtomicInteger();

        private GitHub fieldsPage(final List<ProjectField> page, final @Nullable String next) {
            fields.add(
                    () ->
                            new ProjectFields(
                                    "PVT_1", "Plan", Instant.EPOCH, page, info(next), COST));
            return this;
        }

        private GitHub itemsPage(final @Nullable String next, final ProjectItem... page) {
            items.add(() -> new ProjectItems(List.of(page), info(next), COST));
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
                                calls.add(method.getName() + " after " + args[3]);
                                assertThat(args[0])
                                        .as("the token the read asked for")
                                        .isEqualTo(TOKEN);
                                return switch (method.getName()) {
                                    case "projectFields" -> next(fields).get();
                                    case "projectItems" -> next(items).get();
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
                    FIRST_OPEN);
        }

        private Snapshot read() {
            return reader(AccessRefusals.DEFAULT).read(CONFIG);
        }
    }

    private static PageInfo info(final @Nullable String next) {
        return new PageInfo(next != null, next);
    }

    private static ProjectField field(final String id, final String name, final String type) {
        return new ProjectField(id, name, type, List.of());
    }

    private static ProjectField select(
            final String id, final String name, final String... options) {
        return new ProjectField(
                id,
                name,
                "SINGLE_SELECT",
                Arrays.stream(options)
                        .map(option -> new FieldOption(id + "_" + option, option, "GRAY", ""))
                        .toList());
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
                refs(false));
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
                List.of(
                        text("F_id", "ST1"),
                        option("F_status", "Idea"),
                        option("F_priority", priority)));
    }

    private static ProjectItem libIssue(final int number, final String id, final IssueRefs subs) {
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
                        text("F_id", id),
                        option("F_status", "Backlog"),
                        option("F_size", "M"),
                        number("F_rank", 3),
                        number("F_minutes", 12.5)));
    }

    private static final String LIB_3 = node(OWNER + "/example-lib", 3);

    @Test
    void twoPagesOfItemsWithTheirFieldValuesBecomeTheBoardAndTheFieldIdsAreKept() {
        // Harm: a project of more than one page shows only its first, a field's value is lost on
        // the way, or a save cannot find the field it writes to.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS.subList(0, 5), "f1")
                        .fieldsPage(FIELDS.subList(5, FIELDS.size()), null)
                        .itemsPage("i1", story("High", LIB_3))
                        .itemsPage(null, libIssue(3, "LB5", refs(false)));

        final Snapshot snapshot = github.read();

        final Board board = snapshot.board();
        assertThat(board.faults()).as("nothing left out").isEmpty();
        assertThat(board.stories()).as("the story of the first page").hasSize(1);
        final Story story = board.stories().get(0);
        assertThat(story.priority()).as("the story's Priority field").isEqualTo("High");
        assertThat(story.issues()).as("its issue from the second page").containsExactly("LB5");
        final Issue issue = board.issue("LB5").orElseThrow();
        assertThat(issue.state()).as("the issue's Status field").isEqualTo("Backlog");
        assertThat(issue.repository()).as("the repository's short name").isEqualTo("example-lib");
        assertThat(snapshot.items().get("LB5").fields())
                .as("the number fields as text")
                .containsEntry(FieldNames.RANK, "3")
                .containsEntry(FieldNames.MINUTES, "12.5");
        assertThat(snapshot.items().get("LB5").comments())
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
                                        "PVTI_draft", false, null, List.of(text("F_id", "ST2"))))
                        .read();

        assertThat(snapshot.board().stories())
                .as("only the issue's item")
                .extracting(Story::id)
                .containsExactly("ST1");
        assertThat(snapshot.board().faults()).as("a draft is no fault").isEmpty();
    }

    @Test
    void anItemWithASecondPageOfSubIssuesIsLeftOutAndItsStoryIsNotWhole() {
        // Harm: an issue shown with only its first hundred parts, and its story counted as whole
        // and taken for done while parts are missing.
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(
                                null,
                                story("High", LIB_3),
                                libIssue(3, "LB5", refs(true, "I_part")))
                        .read();

        assertThat(snapshot.board().issue("LB5")).as("the issue left out").isEmpty();
        assertThat(snapshot.board().faults())
                .as("the fault naming it")
                .contains(
                        "Item example-org/example-lib#3 has more sub-issues or blockers than one"
                                + " page carries and is left out; its story is not whole");
        assertThat(snapshot.board().stories().get(0).issuesWhole()).as("its story").isFalse();
    }

    @Test
    void anItemOfAnotherOwnersRepositoryIsLeftOutAsAFault() {
        // Harm: an issue of a stranger's repository shown and worked as the project's own.
        final ProjectItem stranger =
                new ProjectItem(
                        "PVTI_stranger",
                        false,
                        issue("other-org/example-lib", 3, List.of(), null, refs(false)),
                        List.of(text("F_id", "LB5"), option("F_status", "Idea")));

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
                        .itemsPage("i1", story("High", LIB_3), libIssue(3, "LB5", refs(false)))
                        .itemsPage(null, libIssue(3, "LB5", refs(false)))
                        .read();

        assertThat(snapshot.board().issues())
                .extracting(Issue::id)
                .as("one issue")
                .containsExactly("LB5");
        assertThat(snapshot.board().faults()).as("no repeated id").isEmpty();
    }

    @Test
    void aProjectBeyondTheBoundIsUnreadableAndNeverACutBoard() {
        // Harm: a board cut at the bound shown as the whole project, its missing stories taken as
        // gone.
        final GitHub github = new GitHub().fieldsPage(FIELDS, null);
        for (int page = 1; page <= ItemsBoardReader.MOST_PAGES; page++) {
            github.itemsPage("i" + page, libIssue(page, "LB" + page, refs(false)));
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
                        .itemsPage("i1", libIssue(3, "LB5", refs(false)))
                        .itemsPage("i1", libIssue(4, "LB6", refs(false)));

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
                        .itemsPage("i1", libIssue(3, "LB5", refs(false)))
                        .itemsPage(null, libIssue(4, "LB6", refs(false)));
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
    void aBoardReadSendsNoMutation() {
        // Harm: reading the board changes the person's project, its fields, options or sums.
        final List<String> reads =
                Arrays.stream(ProjectQueries.class.getMethods())
                        .filter(method -> !Modifier.isStatic(method.getModifiers()))
                        .map(Method::getName)
                        .sorted()
                        .toList();
        assertThat(reads)
                .as("all a board read can call")
                .containsExactly("projectFields", "projectItems");

        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), libIssue(3, "LB5", refs(false)));
        github.read();
        assertThat(github.calls)
                .as("only the two reads were sent")
                .allMatch(
                        call ->
                                call.startsWith("projectFields")
                                        || call.startsWith("projectItems"));
    }
}

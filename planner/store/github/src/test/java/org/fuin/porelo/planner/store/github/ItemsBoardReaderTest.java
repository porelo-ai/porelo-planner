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
import org.assertj.core.groups.Tuple;
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
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ItemsBoardReaderTest {

    /**
     * The move offered on an issue outside the workflow: into Idea or Backlog, which no work enters
     * alone.
     */
    private static final org.fuin.porelo.planner.store.Warning MOVE_OFFERED =
            org.fuin.porelo.planner.store.Warning.stateOutsideWorkflow()
                    .offering(
                            new org.fuin.porelo.planner.store.Correction(
                                    org.fuin.porelo.planner.store.Correction.MOVE,
                                    List.of("Idea", "Backlog")));

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
                    org.fuin.porelo.planner.store.ProjectRepository.named(
                            List.of("example-project", "example-lib")),
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

        /** The organization's issue fields, each read once in turn; null when none are read. */
        private @Nullable Deque<Supplier<List<org.fuin.porelo.forge.github.model.IssueField>>>
                issueFields;

        /** The owners whose issue fields were read. */
        private final List<String> fieldOwners = new ArrayList<>();

        private GitHub issueFields(
                final Supplier<List<org.fuin.porelo.forge.github.model.IssueField>> answer) {
            if (issueFields == null) {
                issueFields = new ArrayDeque<>();
            }
            java.util.Objects.requireNonNull(issueFields).add(answer);
            return this;
        }

        private ItemsBoardReader.@Nullable IssueFields issueFieldsRead() {
            final var answers = issueFields;
            if (answers == null) {
                return null;
            }
            return (token, owner) -> {
                assertThat(token).as("the token the read asked for").isEqualTo(TOKEN);
                fieldOwners.add(owner);
                return next(answers).get();
            };
        }

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
                                            new GitHubGraphQl.PageInfo(false, null),
                                            0));
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
                    pauses::add,
                    null,
                    LimitFactors.DEFAULT,
                    issueFieldsRead());
        }

        private Snapshot read() {
            return reader(AccessRefusals.DEFAULT).read(CONFIG);
        }

        private Snapshot read(final LimitFactors limits) {
            return new ItemsBoardReader(
                            queries(),
                            origin -> TOKEN,
                            AccessRefusals.DEFAULT,
                            FIRST_OPEN,
                            InstantSource.system(),
                            ItemsBoardReader.DEADLINE,
                            pauses::add,
                            null,
                            limits)
                    .read(CONFIG);
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
                new PageInfo(more, more ? "more" : null),
                ids.length);
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
        return issue(nameWithOwner, number, labels, parent, subs, blockers, null);
    }

    /** A story as GitHub answers it: an issue of the issue type Story, with no label. */
    private static BoardIssue storyIssue(
            final String nameWithOwner,
            final int number,
            final @Nullable String parent,
            final IssueRefs subs) {
        return issue(
                nameWithOwner,
                number,
                List.of(),
                parent,
                subs,
                refs(false),
                IssueMapping.STORY_TYPE);
    }

    private static BoardIssue issue(
            final String nameWithOwner,
            final int number,
            final List<String> labels,
            final @Nullable String parent,
            final IssueRefs subs,
            final IssueRefs blockers,
            final @Nullable String type) {
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
                new GitHubGraphQl.PageInfo(false, null),
                type,
                null,
                List.of(new GitHubGraphQl.IssueFieldOption(FieldNames.PRIORITY, "Medium")),
                0);
    }

    private static BoardIssue withPriority(final BoardIssue issue, final @Nullable String option) {
        return new BoardIssue(
                issue.id(),
                issue.databaseId(),
                issue.number(),
                issue.title(),
                issue.body(),
                issue.state(),
                issue.stateReason(),
                issue.updatedAt(),
                issue.repository(),
                issue.labels(),
                issue.parentId(),
                issue.subIssues(),
                issue.blockedBy(),
                issue.labelsPage(),
                issue.issueType(),
                issue.closedAt(),
                option == null
                        ? List.of()
                        : List.of(new GitHubGraphQl.IssueFieldOption("Priority", option)),
                0);
    }

    @Test
    void theIssuePriorityWinsOverAProjectPriorityAndAMissingOneIsWarned() {
        // Harm: work follows a project priority that nobody reads, or a story without an issue
        // priority is shown with the project's.
        final ProjectItem original = story("High");
        final ProjectItem urgent =
                new ProjectItem(
                        original.id(),
                        false,
                        withPriority(java.util.Objects.requireNonNull(original.issue()), "Urgent"),
                        original.values(),
                        original.valuesPage(),
                        0);
        final Snapshot read = new GitHub().fieldsPage(FIELDS, null).itemsPage(null, urgent).read();
        assertThat(read.board().stories().getFirst().priority())
                .as("the organization's Urgent wins over the project's High")
                .isEqualTo("Highest");

        final ProjectItem missing =
                new ProjectItem(
                        original.id(),
                        false,
                        withPriority(java.util.Objects.requireNonNull(original.issue()), null),
                        original.values(),
                        original.valuesPage(),
                        0);
        final Snapshot absent =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, missing).read();
        assertThat(absent.board().stories().getFirst().warnings())
                .as("a project value cannot replace a missing issue Priority")
                .extracting(Warning::kind)
                .contains(Warning.NO_PRIORITY);
        assertThat(absent.board().stories().getFirst().priority())
                .as("shown as Medium, as a story without a priority is, never the project's High")
                .isEqualTo("Medium");
    }

    /** A story without a priority, its issue holding no option of the organization's field. */
    private static ProjectItem unprioritized() {
        final ProjectItem original = story("High");
        return new ProjectItem(
                original.id(),
                false,
                withPriority(java.util.Objects.requireNonNull(original.issue()), null),
                original.values(),
                original.valuesPage(),
                0);
    }

    /** The organization's single-select issue field Priority with the options given. */
    private static List<org.fuin.porelo.forge.github.model.IssueField> priorityField(
            final String... options) {
        return List.of(
                new org.fuin.porelo.forge.github.model.IssueField(
                        11L,
                        "IF_11",
                        FieldNames.PRIORITY,
                        "",
                        org.fuin.porelo.forge.github.model.IssueField.DataTypeEnum.SINGLE_SELECT,
                        null,
                        Arrays.stream(options)
                                .map(
                                        name ->
                                                new org.fuin.porelo.forge.github.model
                                                        .IssueFieldOptionsInner(
                                                        1L,
                                                        name,
                                                        null,
                                                        org.fuin.porelo.forge.github.model
                                                                .IssueFieldOptionsInner.ColorEnum
                                                                .GRAY,
                                                        null,
                                                        null,
                                                        null))
                                .toList(),
                        null,
                        null));
    }

    private static List<Warning> storyWarnings(final Snapshot read) {
        return read.board().stories().getFirst().warnings();
    }

    @Test
    void aStorysPriorityIsOfferedTheOptionsTheOrganizationsFieldHoldsAsTheReadFoundThem() {
        // Harm: a priority offered that the organization's field lacks, so its save is refused
        // only after the person confirmed it.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .issueFields(() -> priorityField("High", "Medium", "Low"))
                        .itemsPage(null, unprioritized());

        final Snapshot read = github.read();

        assertThat(github.fieldOwners)
                .as("the project owner's fields, read once")
                .containsExactly(OWNER);
        assertThat(storyWarnings(read))
                .as("the priorities the field holds, no Highest without Urgent")
                .containsExactly(
                        Warning.noPriority()
                                .offering(
                                        new org.fuin.porelo.planner.store.Correction(
                                                org.fuin.porelo.planner.store.Correction.PRIORITY,
                                                List.of("High", "Medium", "Low"))));
        assertThat(read.fieldFaults()).as("no fault").isEmpty();
        assertThat(read.config().priorities())
                .as("kept for the reads that map the board again")
                .containsExactly("High", "Medium", "Low");
    }

    @Test
    void anOwnerWithoutAPriorityFieldIsOfferedNoPriorityAndNoFault() {
        // Harm: a priority offered that no field can hold, or a user's project shown as broken.
        for (final Supplier<List<org.fuin.porelo.forge.github.model.IssueField>> answer :
                List.<Supplier<List<org.fuin.porelo.forge.github.model.IssueField>>>of(
                        List::of,
                        () -> {
                            throw GitHubExceptions.refused(404, "Not Found");
                        })) {
            final Snapshot read =
                    new GitHub()
                            .fieldsPage(FIELDS, null)
                            .issueFields(answer)
                            .itemsPage(null, unprioritized())
                            .read();

            assertThat(storyWarnings(read))
                    .as("no correction")
                    .containsExactly(Warning.noPriority());
            assertThat(read.fieldFaults()).as("no fault").isEmpty();
        }
    }

    @Test
    void aFailedReadOfTheOrganizationsFieldsIsAFaultAndOffersNoPriority() {
        // Harm: a priority offered that the field may not hold, or the failure kept from the
        // person.
        final Snapshot read =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .issueFields(
                                () -> {
                                    throw GitHubExceptions.refused(403, "Resource not accessible");
                                })
                        .itemsPage(null, unprioritized())
                        .read();

        assertThat(storyWarnings(read)).as("no correction").containsExactly(Warning.noPriority());
        assertThat(read.fieldFaults())
                .as("the fault, in fixed words naming GitHub's status")
                .containsExactly(
                        "The organization's issue field Priority could not be read (GitHub"
                                + " answered HTTP 403), so no story's priority is offered to set");
        assertThat(read.board().faults())
                .as("on the board")
                .contains(read.fieldFaults().getFirst());
    }

    @Test
    void theHeldPrioritiesComeBackFromTheBoardCopy() {
        // Harm: a board loaded from its copy offering a priority the field lacks, or none at all.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .issueFields(() -> priorityField("Urgent", "Low"))
                        .itemsPage(null, unprioritized());
        final Snapshot read = github.read();
        final BoardCopy copy =
                BoardCopyMapping.toCopy(
                        read, ChangeCursor.NONE, List.of(), Map.of(), Instant.EPOCH);

        final Snapshot loaded =
                java.util.Objects.requireNonNull(
                        github.reader(AccessRefusals.DEFAULT).fromCopy(copy, CONFIG));

        assertThat(loaded.config().priorities()).as("as read").containsExactly("Highest", "Low");
        assertThat(storyWarnings(loaded)).as("as the read offered").isEqualTo(storyWarnings(read));
    }

    @Test
    void theProjectNeedsNoPriorityField() {
        // Harm: a board with the organization's Priority is refused for lacking an unused project
        // field.
        final Snapshot read =
                new GitHub()
                        .fieldsPage(
                                FIELDS.stream()
                                        .filter(field -> !FieldNames.PRIORITY.equals(field.name()))
                                        .toList(),
                                null)
                        .itemsPage(null, story("High"))
                        .read();
        assertThat(read.board().stories().getFirst().priority())
                .as("the issue field supplies High")
                .isEqualTo("High");
        assertThat(read.board().faults()).as("no project Priority is required").isEmpty();
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
                withPriority(
                        storyIssue(OWNER + "/example-project", 1, null, refs(false, subNodes)),
                        priority.equals("Highest") ? "Urgent" : priority),
                List.of(option("F_status", "Idea"), option("F_priority", priority)),
                new GitHubGraphQl.PageInfo(false, null),
                0);
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
                new GitHubGraphQl.PageInfo(false, null),
                0);
    }

    private static final String LIB_3 = node(OWNER + "/example-lib", 3);

    private static IssueOnBoard onBoard(final ProjectItem item) {
        return new IssueOnBoard(
                item.issue(), item.id(), item.values(), item.valuesPage(), item.valueCount());
    }

    private static ProjectItem partialValues(final ProjectItem item) {
        return new ProjectItem(
                item.id(), item.archived(), item.issue(), item.values(), info("values-2"), 0);
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
                        info(next),
                        issue.issueType(),
                        null,
                        issue.fieldOptions(),
                        0),
                item.values(),
                item.valuesPage(),
                0);
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
                        "PVTI_replaced", true, original.issue(), original.values(), info(null), 0);
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
                new ProjectItem("PVTI_current", false, old.issue(), old.values(), info(null), 0);
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
                        info(null),
                        0);
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
                        info(null),
                        0);
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
                        storyIssue(OWNER + "/example-project", 1, null, refs(true)),
                        whole.values(),
                        info(null),
                        0);
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
        // Harm: a value beyond the first values page, such as a story's size, is lost and later
        // changes of it are never shown.
        final ProjectItem before = story("High");
        final ProjectItem whole =
                new ProjectItem(
                        before.id(),
                        false,
                        before.issue(),
                        List.of(option("F_status", "Idea"), option("F_size", "M")),
                        new GitHubGraphQl.PageInfo(false, null),
                        0);
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, partialValues(before))
                        .boardItems(whole);
        final Snapshot baseline =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, before).read();

        final Snapshot snapshot = github.read();

        assertThat(snapshot.board().stories().get(0).size())
                .as("the size from the values' second page")
                .isEqualTo("M");
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
                        null,
                        new IssueOnBoard(whole.issue(), null, whole.values(), info(null), 0))) {
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
    void aChangeOfTheIssueTypeAloneChangesTheRevision() {
        // Harm: an issue retyped on GitHub, as a story made a bug, maps anew while the revision
        // stays, so a window at that revision saves against a board that no longer holds it.
        final Snapshot bug =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, typed("Bug")).read();
        final Snapshot task =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, typed("Task")).read();

        assertThat(task.board().issues())
                .as("both map to the same issue")
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("version")
                .containsExactlyElementsOf(bug.board().issues());
        assertThat(task.board().issues().getFirst().version())
                .as("an issue type is the item's own stored state")
                .isNotEqualTo(bug.board().issues().getFirst().version());
        assertThat(task.board().revision())
                .as("the same project with another issue type")
                .isNotEqualTo(bug.board().revision());
    }

    private static ProjectItem typed(final String type) {
        return new ProjectItem(
                "PVTI_typed",
                false,
                issue(
                        OWNER + "/example-project",
                        7,
                        List.of(),
                        null,
                        refs(false),
                        refs(false),
                        type),
                List.of(option("F_status", "Backlog"), option("F_priority", "High")),
                new GitHubGraphQl.PageInfo(false, null),
                0);
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
                                        new GitHubGraphQl.PageInfo(false, null),
                                        0))
                        .read();

        assertThat(snapshot.board().stories())
                .as("only the issue's item")
                .extracting(Story::id)
                .containsExactly("example-project#1");
        assertThat(snapshot.board().faults()).as("a draft is no fault").isEmpty();
    }

    @Test
    void anIssueGitHubClosedAsNotPlannedUnderAnOpenStatusIsReadClosed() {
        // Harm: an issue a person closed as not planned while it stood in Backlog read as open
        // work and proposed for a start.
        final ProjectItem open = libIssue(3, refs(false));
        final GitHubGraphQl.BoardIssue was = open.issue();
        final ProjectItem closed =
                new ProjectItem(
                        open.id(),
                        open.archived(),
                        new GitHubGraphQl.BoardIssue(
                                was.id(),
                                was.databaseId(),
                                was.number(),
                                was.title(),
                                was.body(),
                                "CLOSED",
                                "NOT_PLANNED",
                                was.updatedAt(),
                                was.repository(),
                                was.labels(),
                                was.parentId(),
                                was.subIssues(),
                                was.blockedBy(),
                                was.labelsPage(),
                                null,
                                null,
                                was.fieldOptions(),
                                0),
                        open.values(),
                        open.valuesPage(),
                        0);

        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), closed)
                        .read();

        assertThat(snapshot.board().issue("example-lib#3").orElseThrow())
                .as("in the workflow's one closed state, unwarned")
                .extracting(Issue::state, Issue::warnings)
                .containsExactly("Finished", List.of());
        assertThat(snapshot.board().faults()).as("no fault").isEmpty();
    }

    @Test
    void anItemWithASecondPageOfSubIssuesIsShownWarnedAndItsStoryIsNotWhole() {
        // Harm: an issue with more than a hundred parts hidden from the board; or shown as if whole
        // and its story taken for done while parts are missing.
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), libIssue(3, refs(true, "I_part")))
                        .boardItems(libIssue(3, refs(true, "I_part")))
                        .read();

        assertThat(snapshot.board().issue("example-lib#3").orElseThrow().warnings())
                .as("the issue, warned")
                .containsExactly(org.fuin.porelo.planner.store.Warning.pastOnePage());
        assertThat(org.fuin.porelo.planner.store.Warning.pastOnePage().holdsWork())
                .as("no work starts on it")
                .isTrue();
        assertThat(snapshot.board().faults())
                .as("never one calling it outside the project")
                .isEmpty();
        assertThat(snapshot.board().stories().get(0).issuesWhole()).as("its story").isFalse();
    }

    @Test
    void anItemWithASecondPageOfFieldValuesIsShownWarnedAndKeptMarkedInItsCopy() {
        // Harm: an issue with many field values hidden from the board; or mapped from its first
        // fifty values, its Status or Size among those cut off, and worked as if whole.
        final ProjectItem cut = libIssue(3, refs(false));
        final ProjectItem partial =
                new ProjectItem(
                        cut.id(),
                        cut.archived(),
                        cut.issue(),
                        cut.values(),
                        new GitHubGraphQl.PageInfo(true, "values-2"),
                        0);
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), partial)
                        .boardItems(partial)
                        .read();

        assertThat(snapshot.board().issue("example-lib#3").orElseThrow().warnings())
                .as("the issue, warned so no work starts on it")
                .containsExactly(org.fuin.porelo.planner.store.Warning.pastOnePage());
        assertThat(snapshot.board().faults()).as("nothing left out").isEmpty();
        assertThat(BoardCopyMapping.tracked(partial).partial()).as("its copy").isTrue();
        assertThat(
                        BoardCopyMapping.item(BoardCopyMapping.tracked(partial))
                                .valuesPage()
                                .hasNextPage())
                .as("read back from its copy, still not whole")
                .isTrue();
    }

    @Test
    void anIssueBlockedByAnItemReadPastOnePageWaitsOnItWhileItIsHeld() {
        // Harm: an issue and its blocker hidden because the blocker has many parts; or the issue
        // told as blocked by something outside the project and sent looking for it there.
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
                        new GitHubGraphQl.PageInfo(false, null),
                        0);
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

        assertThat(snapshot.board().issue("example-lib#3").orElseThrow().warnings())
                .as("the issue read in part, warned so no work starts on it")
                .containsExactly(org.fuin.porelo.planner.store.Warning.pastOnePage());
        assertThat(snapshot.board().issue("example-lib#4").orElseThrow().blockedBy())
                .as("the issue blocked by it, on the board")
                .containsExactly("example-lib#3");
        assertThat(snapshot.board().faults()).as("nothing left out").isEmpty();
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
                        new GitHubGraphQl.PageInfo(false, null),
                        0);

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
                        new GitHubGraphQl.PageInfo(false, null),
                        0);

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
                        new GitHubGraphQl.PageInfo(false, null),
                        0);

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
    void aLargeProjectWhosePagesKeepComingIsReadToItsEnd() {
        // Harm: a project too large to read within a fixed time is cut off at every read and
        // can never be loaded, though GitHub answers each page.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage("i1", libIssue(3, refs(false)))
                        .itemsPage("i2", libIssue(4, refs(false)))
                        .itemsPage("i3", libIssue(5, refs(false)))
                        .itemsPage("i4", libIssue(6, refs(false)))
                        .itemsPage(null, libIssue(7, refs(false)));
        final Instant start = Instant.parse("2026-01-01T00:00:00Z");
        final AtomicInteger ticks = new AtomicInteger();
        // Each look at the clock 25 s later: far longer than 60 s in all, never 60 s silent.
        final InstantSource slow = () -> start.plusSeconds(25L * ticks.getAndIncrement());
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        github.queries(),
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        FIRST_OPEN,
                        slow,
                        Duration.ofSeconds(60));

        final Snapshot snapshot = reader.read(CONFIG);

        assertThat(slow.instant())
                .as("a read far longer than the deadline")
                .isAfter(start.plusSeconds(180));
        assertThat(snapshot.board().issues()).as("every page's issue").hasSize(5);
    }

    @Test
    void anAnswerGitHubGivesOnlyAfterTheDeadlineIsUnreachable() {
        // Harm: a GitHub that has stopped answering holds the board and every save behind it.
        final GitHub github =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, libIssue(3, refs(false)));
        final Instant start = Instant.parse("2026-01-01T00:00:00Z");
        final AtomicInteger ticks = new AtomicInteger();
        final InstantSource silent = () -> start.plusSeconds(70L * ticks.getAndIncrement());
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        github.queries(),
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        FIRST_OPEN,
                        silent,
                        Duration.ofSeconds(60));

        assertThatThrownBy(() -> reader.read(CONFIG))
                .as("the read GitHub left without an answer for 70 s")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageContaining("no answer to the project's read within 60 s");
    }

    @Test
    void aDeadlineShorterThanASecondIsToldWithItsFraction() {
        // Harm: a read under a small services factor refused as given no answer "within 0 s",
        // which tells the person nothing of the limit the factor set.
        final GitHub github =
                new GitHub().fieldsPage(FIELDS, null).itemsPage(null, libIssue(3, refs(false)));
        final Instant start = Instant.parse("2026-01-01T00:00:00Z");
        final AtomicInteger ticks = new AtomicInteger();
        final InstantSource silent = () -> start.plusSeconds(ticks.getAndIncrement());
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        github.queries(),
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        FIRST_OPEN,
                        silent,
                        Duration.ofMillis(500));

        assertThatThrownBy(() -> reader.read(CONFIG))
                .as("the read GitHub left without an answer for a second")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageContaining("no answer to the project's read within 0.5 s");
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
                item.valuesPage(),
                0);
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
                new GitHubGraphQl.PageInfo(false, null),
                issue.issueType(),
                null,
                issue.fieldOptions(),
                0);
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
                        new GitHubGraphQl.PageInfo(false, null),
                        0);

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
    void twoItemsOfOneReferenceAreBothShownWarnedAndTheBoardIsRead() {
        // Harm: two items that name one issue take the whole board down for everyone, or one of
        // them is hidden while the other is worked or moved as if it were the only one.
        final ProjectItem capitals =
                new ProjectItem(
                        "PVTI_capitals",
                        false,
                        issue(OWNER + "/Example-Lib", 3, List.of(), null, refs(false)),
                        List.of(option("F_status", "Idea")),
                        new GitHubGraphQl.PageInfo(false, null),
                        0);

        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), libIssue(3, refs(false)), capitals)
                        .read();

        assertThat(snapshot.board().issues())
                .as("both copies, each warned")
                .extracting(Issue::id, Issue::warnings)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "example-lib#3",
                                List.of(org.fuin.porelo.planner.store.Warning.idTwice())),
                        org.assertj.core.groups.Tuple.tuple(
                                "example-lib#3",
                                List.of(org.fuin.porelo.planner.store.Warning.idTwice())));
        assertThat(snapshot.board().issue("example-lib#3")).as("no one issue by the id").isEmpty();
        assertThat(snapshot.board().twice("example-lib#3")).as("why").isPresent();
        assertThat(snapshot.board().faults()).as("nothing left out").isEmpty();
    }

    @Test
    void aCopyOfOneReferenceOutsideTheWorkflowAndBlockedByAnotherOutsideItLeavesTheBoardRead() {
        // Harm: two items of one reference, one of them in a Status the workflow lacks, take the
        // whole board down for everyone; or a copy loses its link to a blocker the board holds.
        final ProjectItem parked =
                new ProjectItem(
                        "PVTI_lib_5",
                        false,
                        issue(OWNER + "/example-lib", 5, List.of(), null, refs(false)),
                        List.of(option("F_status", "Parked")),
                        new GitHubGraphQl.PageInfo(false, null),
                        0);
        final ProjectItem onHold =
                new ProjectItem(
                        "PVTI_capitals",
                        false,
                        issue(
                                OWNER + "/Example-Lib",
                                3,
                                List.of(),
                                null,
                                refs(false),
                                refs(false, node(OWNER + "/example-lib", 5))),
                        List.of(option("F_status", "On hold")),
                        new GitHubGraphQl.PageInfo(false, null),
                        0);

        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(
                                null,
                                story("High", LIB_3),
                                libIssue(3, refs(false)),
                                onHold,
                                parked)
                        .read();

        assertThat(snapshot.board().issues())
                .as("both copies and the blocker, each outside the workflow warned for it")
                .extracting(
                        Issue::id, Issue::state, Issue::blockedBy, i -> Set.copyOf(i.warnings()))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "example-lib#3",
                                "Backlog",
                                List.of(),
                                Set.of(org.fuin.porelo.planner.store.Warning.idTwice())),
                        org.assertj.core.groups.Tuple.tuple(
                                "example-lib#3",
                                "On hold",
                                List.of("example-lib#5"),
                                Set.of(
                                        org.fuin.porelo.planner.store.Warning.idTwice(),
                                        org.fuin.porelo.planner.store.Warning
                                                .stateOutsideWorkflow())),
                        org.assertj.core.groups.Tuple.tuple(
                                "example-lib#5", "Parked", List.of(), Set.of(MOVE_OFFERED)));
        assertThat(snapshot.board().holdsWork("example-lib#3"))
                .as("neither copy worked")
                .isPresent();
    }

    @Test
    void anArchivedItemOfAReferenceALiveItemHoldsGivesTheWriterTheLiveOne() {
        // Harm: a move checked against the live issue sets the Status of an archived item that
        // GitHub listed first under the same reference.
        final ProjectItem archived =
                new ProjectItem(
                        "PVTI_archived",
                        true,
                        issue(OWNER + "/Example-Lib", 3, List.of(), null, refs(false)),
                        List.of(option("F_status", "Finished")),
                        new GitHubGraphQl.PageInfo(false, null),
                        0);

        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), archived, libIssue(3, refs(false)))
                        .read();

        assertThat(snapshot.board().issue("example-lib#3"))
                .as("the live issue, on the board once")
                .isPresent();
        assertThat(snapshot.items().get("example-lib#3").itemId())
                .as("the item a write of the reference goes to")
                .isEqualTo("PVTI_lib_3");
    }

    @Test
    void aSecondPageOfFieldValuesComingOrGoingAloneChangesTheRevision() {
        // Harm: an item's warning that it was read in part comes or goes while the revision stays,
        // so the window keeps showing work held, or free, that no longer is.
        final ProjectItem whole = libIssue(3, refs(false));
        final ProjectItem partial =
                new ProjectItem(
                        whole.id(),
                        whole.archived(),
                        whole.issue(),
                        whole.values(),
                        new GitHubGraphQl.PageInfo(true, "values-2"),
                        0);
        final Snapshot read =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), whole)
                        .read();
        final Snapshot cut =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), partial)
                        .boardItems(partial)
                        .read();

        assertThat(cut.board().issue("example-lib#3").orElseThrow().warnings())
                .as("the issue read in part, warned")
                .containsExactly(org.fuin.porelo.planner.store.Warning.pastOnePage());
        assertThat(cut.board().revision())
                .as("the same values with a page past them")
                .isNotEqualTo(read.board().revision());
        final ProjectItem labelsCut = withLabels(whole, List.of(), "labels-2");
        final Snapshot labels =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), labelsCut)
                        .boardItems(labelsCut)
                        .read();
        assertThat(labels.board().issue("example-lib#3").orElseThrow().warnings())
                .as("the issue whose labels were read in part, warned")
                .containsExactly(org.fuin.porelo.planner.store.Warning.pastOnePage());
        assertThat(labels.board().revision())
                .as("the same labels with a page past them")
                .isNotEqualTo(read.board().revision());
    }

    /** The item with GitHub's counts of its labels and of its field values. */
    private static ProjectItem counted(
            final ProjectItem item, final int labelCount, final int valueCount) {
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
                        issue.labels(),
                        issue.parentId(),
                        issue.subIssues(),
                        issue.blockedBy(),
                        issue.labelsPage(),
                        issue.issueType(),
                        issue.closedAt(),
                        issue.fieldOptions(),
                        labelCount),
                item.values(),
                item.valuesPage(),
                valueCount);
    }

    /** The board read of one item beside the story it belongs to. */
    private static Snapshot readOf(final ProjectItem item) {
        return new GitHub()
                .fieldsPage(FIELDS, null)
                .itemsPage(null, story("High", LIB_3), item)
                .boardItems(item)
                .read();
    }

    @Test
    void aLabelOrValueAddedPastThePageChangesTheItemsVersionAndTheBoardsRevision() {
        // Harm: a label or field value added past the page read leaves the board's version of
        // the item and the board's revision as they were, so the window is told nothing and a
        // write made against the old board is taken as made against the item as it stands.
        final ProjectItem whole = libIssue(3, refs(false));
        final ProjectItem labelsCut = withLabels(whole, List.of("bug"), "labels-2");
        final ProjectItem valuesCut =
                new ProjectItem(
                        whole.id(),
                        whole.archived(),
                        whole.issue(),
                        whole.values(),
                        new GitHubGraphQl.PageInfo(true, "values-2"),
                        0);
        final int values = whole.values().size();
        for (final ProjectItem[] pair :
                List.of(
                        new ProjectItem[] {
                            counted(labelsCut, 101, values), counted(labelsCut, 102, values)
                        },
                        new ProjectItem[] {
                            counted(valuesCut, 0, values + 1), counted(valuesCut, 0, values + 2)
                        })) {
            final Snapshot before = readOf(pair[0]);
            final Snapshot after = readOf(pair[1]);
            final Issue first = before.board().issue("example-lib#3").orElseThrow();

            assertThat(readOf(pair[0]).board().issue("example-lib#3").orElseThrow().version())
                    .as("the same item read again")
                    .isEqualTo(first.version());
            assertThat(after.board().issue("example-lib#3").orElseThrow().version())
                    .as("one more past the page, the page the same")
                    .isNotEqualTo(first.version());
            assertThat(after.board().revision())
                    .as("the board's revision with one more past the page")
                    .isNotEqualTo(before.board().revision());
        }
    }

    @Test
    void anItemOfTheOwnersRepositoryTheProjectDoesNotPlanIsAFaultAndTheBoardIsRead() {
        // Harm: one issue of a repository project.yml does not name, added to the project by
        // hand, takes the whole board down for everyone. A guard of behavior the parent commit
        // already had: the same check left this item out as a fault before.
        final ProjectItem elsewhere =
                new ProjectItem(
                        "PVTI_elsewhere",
                        false,
                        issue(
                                OWNER + "/other-repo",
                                5,
                                List.of(),
                                node(OWNER + "/example-project", 1),
                                refs(false)),
                        List.of(option("F_status", "Idea")),
                        new GitHubGraphQl.PageInfo(false, null),
                        0);

        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), libIssue(3, refs(false)), elsewhere)
                        .read();

        assertThat(snapshot.board().issues())
                .as("the issues of the project's repositories")
                .extracting(Issue::id)
                .containsExactly("example-lib#3");
        assertThat(snapshot.board().faults())
                .as("the item named, left out")
                .containsExactly(
                        "Item other-repo#5 lies outside the project's repositories and is left out");
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
    void aLongProjectIsReadToItsLastPage() {
        // Harm: a large project refused as unreadable at a count of pages, its board never shown,
        // though GitHub still gave a next page.
        final GitHub github = new GitHub().fieldsPage(FIELDS, null);
        for (int page = 1; page <= 50; page++) {
            github.itemsPage("i" + page, libIssue(page, refs(false)));
        }
        github.itemsPage(null, libIssue(51, refs(false)));

        final Snapshot snapshot = github.read();

        assertThat(snapshot.board().issues())
                .as("the issues of every page, the last one's among them")
                .extracting(Issue::id)
                .hasSize(51)
                .contains("example-lib#51");
        assertThat(github.calls).as("the fields' page and every items' page").hasSize(1 + 51);
    }

    @Test
    void aCursorGivenAgainAfterAnotherIsRefused() {
        // Harm: a read that asks for the same two pages for ever on a GitHub that leads its
        // cursors back to one already read.
        final GitHub github = new GitHub().fieldsPage(FIELDS, null);
        for (int page = 1; page <= 60; page++) {
            github.itemsPage(page % 2 == 1 ? "i1" : "i2", libIssue(page, refs(false)));
        }

        assertThatThrownBy(github::read)
                .as("the cursor given again")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining(
                        "GitHub named a next page of items without a new place to read on");
        assertThat(github.calls).as("no fourth page asked for").hasSize(1 + 3);
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
    void anEmptyPageOfItemsThatNamesANextOneIsRefused() {
        // Harm: a read that asks for page after page for ever on a GitHub that gives no item and
        // yet a new cursor each time.
        final GitHub github = new GitHub().fieldsPage(FIELDS, null);
        for (int page = 1; page <= 60; page++) {
            github.itemsPage("i" + page);
        }

        assertThatThrownBy(github::read)
                .as("the empty page that names a next one")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("GitHub named a next page of items after a page without any");
        assertThat(github.calls).as("no second page of items asked for").hasSize(1 + 1);
    }

    @Test
    void anEmptyPageOfFieldsThatNamesANextOneIsRefused() {
        // Harm: a read that asks for the project's fields for ever on a GitHub that gives no field
        // and yet a new cursor each time.
        final GitHub github =
                new GitHub()
                        .fieldsPage(List.of(), "f1")
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, libIssue(3, refs(false)));

        assertThatThrownBy(github::read)
                .as("the empty page that names a next one")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining(
                        "GitHub named a next page of fields after a page without any");
        assertThat(github.calls).as("no second page of fields asked for").hasSize(1);
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

    /** A reader whose pause moves its clock on by the wait, with the silence limit given. */
    private static ItemsBoardReader waiting(
            final GitHub github,
            final java.util.concurrent.atomic.AtomicReference<Instant> now,
            final List<Duration> waits) {
        return new ItemsBoardReader(
                github.queries(),
                origin -> TOKEN,
                AccessRefusals.DEFAULT,
                FIRST_OPEN,
                now::get,
                Duration.ofSeconds(30),
                length -> {
                    waits.add(length);
                    now.set(now.get().plus(length));
                });
    }

    @Test
    void aShortRateLimitInAReadBesideTheSavesIsWaitedOutAndTheReadGoesOn() {
        // Harm: a large board's read ends at GitHub's short rate limit, though GitHub would go on
        // answering after the wait it named.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsFailure(
                                GitHubExceptions.rateLimited(
                                        403, "secondary rate limit", Duration.ofSeconds(50)))
                        .itemsPage(null, story("High"));
        final java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
        final List<Duration> waits = new ArrayList<>();

        // The wait is longer than the read's 30 s silence limit.
        final Snapshot snapshot = waiting(github, now, waits).read(CONFIG, true);

        assertThat(waits).as("the waits taken").containsExactly(Duration.ofSeconds(50));
        assertThat(snapshot.board().stories()).as("the board read after the wait").hasSize(1);
    }

    /** Another project's look on the same token's pace, which reads nothing of its own. */
    private static ConditionalChangeProbe otherProject(final GitHubPace pace) {
        final ConditionalReads none =
                (ConditionalReads)
                        Proxy.newProxyInstance(
                                ConditionalReads.class.getClassLoader(),
                                new Class<?>[] {ConditionalReads.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError("the other project reads nothing");
                                });
        return new ConditionalChangeProbe(none, origin -> TOKEN, pace, Runnable::run);
    }

    /**
     * A reader on the token's shared pace, as the opener wires it, whose pause moves its clock on
     * by the wait after it lets the test look.
     */
    private static ItemsBoardReader paced(
            final GitHub github,
            final GitHubPace pace,
            final java.util.concurrent.atomic.AtomicReference<Instant> now,
            final java.util.function.Consumer<Duration> during) {
        return new ItemsBoardReader(
                pace.answering(github.queries()),
                origin -> TOKEN,
                AccessRefusals.DEFAULT,
                FIRST_OPEN,
                now::get,
                Duration.ofSeconds(30),
                length -> {
                    during.accept(length);
                    now.set(now.get().plus(length));
                },
                null,
                LimitFactors.DEFAULT,
                null,
                pace::spent);
    }

    @Test
    void aRateLimitAReadWaitsOutHoldsNoOtherProjectOnTheSharedPace() {
        // Harm: another project's board stamped as the one read before, and its looks held for
        // GitHub's wait, while this project's read waits a few seconds and goes on answered.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsFailure(
                                GitHubExceptions.rateLimited(
                                        403, "secondary rate limit", Duration.ofSeconds(50)))
                        .itemsPage(null, story("High"));
        final java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
        final GitHubPace pace = new GitHubPace(now::get);
        final ConditionalChangeProbe other = otherProject(pace);
        final List<@Nullable String> holdsDuringTheWait = new ArrayList<>();
        final List<String> faultsDuringTheWait = new ArrayList<>();
        final ItemsBoardReader reader =
                paced(
                        github,
                        pace,
                        now,
                        length -> {
                            holdsDuringTheWait.add(other.hold());
                            faultsDuringTheWait.addAll(other.faults());
                        });

        final Snapshot snapshot = reader.read(CONFIG, true);

        assertThat(snapshot.board().stories()).as("the board read after the wait").hasSize(1);
        assertThat(holdsDuringTheWait)
                .as("the other project's looks during the wait")
                .containsExactly((String) null);
        assertThat(faultsDuringTheWait).as("the other project's board during the wait").isEmpty();
        assertThat(other.hold()).as("the other project's looks after the read").isNull();
        assertThat(other.faults()).as("the other project's board after the read").isEmpty();
    }

    @Test
    void aRateLimitAReadFailsOnHoldsTheOtherProjectsOnTheSharedPace() {
        // Harm: another project's look sent to GitHub while the person's rate limit is spent, so
        // it spends what is left of GitHub's patience, and its board not told it is the one read
        // before.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsFailure(
                                GitHubExceptions.rateLimited(
                                        403, "API rate limit exceeded", Duration.ofSeconds(90)));
        final java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
        final GitHubPace pace = new GitHubPace(now::get);
        final ConditionalChangeProbe other = otherProject(pace);

        assertThatThrownBy(() -> paced(github, pace, now, length -> {}).read(CONFIG, true))
                .as("the read GitHub asks to wait 90 s")
                .isInstanceOf(StoreException.Unreachable.class);

        assertThat(other.hold())
                .as("the other project's looks")
                .isEqualTo("GitHub's rate limit is spent; it reads again in 90 s");
        assertThat(other.faults())
                .as("the other project's board")
                .anySatisfy(fault -> assertThat(fault).contains("the one read before"));
    }

    @Test
    void anAnswerToABoardsReadEndsTheHoldOnTheSharedPace() {
        // Harm: every project's board told it is the one read before, and no look sent, until
        // GitHub's named wait ends, though GitHub already answers the person's reads again.
        final GitHub github = new GitHub().fieldsPage(FIELDS, null).itemsPage(null, story("High"));
        final java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
        final GitHubPace pace = new GitHubPace(now::get);
        final ConditionalChangeProbe other = otherProject(pace);
        pace.spent(Duration.ofMinutes(30));

        paced(github, pace, now, length -> {}).read(CONFIG, true);

        assertThat(other.hold()).as("the other project's looks").isNull();
        assertThat(other.faults()).as("the other project's board").isEmpty();
    }

    @Test
    void aRateLimitLongerThanAReadWaitsFailsTheReadNamingTheWait() {
        // Harm: a read that sleeps for many minutes behind a spent rate limit, while the person
        // sees it progress.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsFailure(
                                GitHubExceptions.rateLimited(
                                        403, "API rate limit exceeded", Duration.ofSeconds(90)));
        final List<Duration> waits = new ArrayList<>();

        assertThatThrownBy(
                        () ->
                                waiting(
                                                github,
                                                new java.util.concurrent.atomic.AtomicReference<>(
                                                        Instant.EPOCH),
                                                waits)
                                        .read(CONFIG, true))
                .as("the read GitHub asks to wait 90 s")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageContaining("90 s");
        assertThat(waits).as("no wait taken").isEmpty();
    }

    @Test
    void aReadUnderASavesLockDoesNotWaitForTheRateLimit() {
        // Harm: saves stand behind a read that sleeps for GitHub's rate limit.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsFailure(
                                GitHubExceptions.rateLimited(
                                        403, "secondary rate limit", Duration.ofSeconds(5)));
        final List<Duration> waits = new ArrayList<>();

        assertThatThrownBy(
                        () ->
                                waiting(
                                                github,
                                                new java.util.concurrent.atomic.AtomicReference<>(
                                                        Instant.EPOCH),
                                                waits)
                                        .read(CONFIG))
                .as("the read GitHub asks to wait 5 s")
                .isInstanceOf(StoreException.Unreachable.class);
        assertThat(waits).as("no wait taken").isEmpty();
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
    void aSubIssueWhoseOwnProjectItemsNameNoProjectIsWarnedWithoutAReadAgain() {
        // Harm: an issue really outside the project hides from the person, or holds every board
        // read for seconds and spends the person's rate limit on reads again.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3))
                        .issuesOnProject(false);

        final Board board = github.read().board();

        assertThat(board.story("example-project#1").orElseThrow().warnings())
                .as("the sub-issue outside the project warned on its story")
                .containsExactly(org.fuin.porelo.planner.store.Warning.issueOutsideProject());
        assertThat(github.calls)
                .as("no read again of the list")
                .containsExactly(
                        "projectFields after null",
                        "projectItems after null",
                        "issueBoard of [" + LIB_3 + "] in PVT_1");
        assertThat(github.pauses).as("no pause").isEmpty();
    }

    @Test
    void theReadsAgainOfTheItemListAndTheirPausesFollowTheRetriesFactor() {
        // Harm: a person whose retries factor asks for more patience with GitHub's lagging list
        // shown a story without its issue after the default reads again.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3))
                        .issuesOnProject(true);
        for (int again = 0; again < 2 * ItemsBoardReader.MOST_REREADS; again++) {
            github.itemsPage(null, story("High", LIB_3));
        }

        github.read(LimitFactors.parse("retries=2.0"));

        assertThat(github.calls.stream().filter(call -> call.startsWith("projectItems")))
                .as("the list read once and then twice as often as the default bound")
                .hasSize(1 + 2 * ItemsBoardReader.MOST_REREADS);
        assertThat(github.pauses)
                .as("each pause twice the default")
                .hasSize(2 * ItemsBoardReader.MOST_REREADS)
                .containsOnly(ItemsBoardReader.REREAD_PAUSE.multipliedBy(2));
    }

    @Test
    void aSubIssueTheItemListStillLacksAfterTheLastReadAgainIsWarnedOnItsStory() {
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

        assertThat(board.story("example-project#1").orElseThrow().warnings())
                .as("the sub-issue still missing warned on its story")
                .containsExactly(org.fuin.porelo.planner.store.Warning.issueOutsideProject());
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
                        new GitHubGraphQl.PageInfo(false, null),
                        0);
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", other), otherIssue);

        final Board board = github.read().board();

        assertThat(board.story("example-project#1").orElseThrow().warnings())
                .as("the story warned for its sub-issue outside the project")
                .containsExactly(org.fuin.porelo.planner.store.Warning.issueOutsideProject());
        assertThat(github.calls)
                .as("only the fields and the items")
                .containsExactly("projectFields after null", "projectItems after null");
    }

    @Test
    void anIssueWithAStatusTheWorkflowLacksStandsUnderItsStoryWarnedUntilMovedIntoIt() {
        // Harm: an issue whose Status a person set outside the workflow leaves its story for a
        // list apart, or stays warned once moved into the workflow.
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
                        new GitHubGraphQl.PageInfo(false, null),
                        0);

        final Board before =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), accepted)
                        .read()
                        .board();

        assertThat(before.issues())
                .as("on the board in its own state, warned")
                .extracting(Issue::id, Issue::state, Issue::worker, Issue::warnings)
                .containsExactly(
                        Tuple.tuple(
                                "example-lib#3", "Accepted", Worker.MANUAL, List.of(MOVE_OFFERED)));
        assertThat(before.stories().get(0).issues())
                .as("under its story")
                .containsExactly("example-lib#3");
        assertThat(before.faults()).as("not left out").isEmpty();

        final ProjectItem moved =
                new ProjectItem(
                        accepted.id(),
                        false,
                        accepted.issue(),
                        List.of(option("F_status", "Backlog"), option("F_worker", "Manual")),
                        new GitHubGraphQl.PageInfo(false, null),
                        0);
        final Board after =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), moved)
                        .read()
                        .board();

        assertThat(after.issue("example-lib#3").map(Issue::state))
                .as("on the board in the state it was moved to")
                .contains("Backlog");
        assertThat(after.issue("example-lib#3").map(Issue::warnings))
                .as("no longer warned")
                .contains(List.of());
    }

    @Test
    void aSnapshotThroughItsCopyGivesTheSameBoardAndRevision() {
        // Harm: a board shown at start from its copy that differs from the one GitHub gave, or
        // whose revision refuses the first save made against it.
        final List<ProjectField> fields = new ArrayList<>(FIELDS);
        // A second field of one name: its values are still read by the field's id.
        fields.add(field("F_rank_new", FieldNames.RANK, "NUMBER"));
        // An item more than one page carries, asked again and still more, stays warned.
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
                                        new GitHubGraphQl.PageInfo(false, null),
                                        0))
                        .boardPage(onBoard(overflowing));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot read = reader.read(CONFIG);

        final BoardCopy copy = reader.toCopy(read, ChangeCursor.NONE, List.of());
        assertThat(copy).as("the copy of a snapshot read from GitHub").isNotNull();
        final Snapshot loaded = reader.fromCopy(copy, CONFIG);

        assertThat(read.board().issue("example-lib#4").orElseThrow().warnings())
                .as("the warning the copy must keep")
                .containsExactly(org.fuin.porelo.planner.store.Warning.pastOnePage());
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
                                org.fuin.porelo.planner.store.ProjectRepository.named(
                                        List.of("example-project")),
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
                        new IssueRefs(List.of(blockers), new PageInfo(false, null), 1)),
                plain.values(),
                plain.valuesPage(),
                0);
    }

    /**
     * An issue of the library, under the story, blocked by the issues named and by as many more as
     * {@code unseen} that GitHub counts but does not name.
     */
    private static ProjectItem hiding(
            final int number, final int unseen, final IssueRef... blockers) {
        return blocked(
                number,
                new IssueRefs(
                        List.of(blockers), new PageInfo(false, null), blockers.length + unseen));
    }

    /** An issue of the library, under the story, blocked as GitHub's list of blockers says. */
    private static ProjectItem blocked(final int number, final IssueRefs blockers) {
        final ProjectItem plain = waiting(number);
        final BoardIssue issue = java.util.Objects.requireNonNull(plain.issue());
        return new ProjectItem(
                plain.id(),
                false,
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
                        issue.labels(),
                        issue.parentId(),
                        issue.subIssues(),
                        blockers,
                        issue.labelsPage(),
                        null,
                        null,
                        issue.fieldOptions(),
                        0),
                plain.values(),
                plain.valuesPage(),
                0);
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
                        new GitHubGraphQl.PageInfo(false, null),
                        null,
                        null,
                        List.of(new GitHubGraphQl.IssueFieldOption("Priority", "Medium")),
                        0),
                null,
                List.of(),
                new GitHubGraphQl.PageInfo(false, null),
                0);
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
    void aBlockerGitHubCountsButDoesNotNameIsAWaitOnSomethingNotReadableThatHoldsTheIssue() {
        // Harm: GitHub leaves a blocker the person's token cannot read out of the names it lists,
        // so the issue reads as free and work starts on it while that blocker is still open.
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), hiding(3, 1, FAR))
                        .boardPage(elsewhere(FAR, "CLOSED"))
                        .read();

        final List<Wait> waits = snapshot.board().issue("example-lib#3").orElseThrow().waitsOn();
        assertThat(waits)
                .as("the named blocker done, the one not named a wait on something not readable")
                .containsExactly(
                        new Wait(
                                "other-org/elsewhere#7",
                                true,
                                java.net.URI.create(
                                        "https://github.com/other-org/elsewhere/issues/7")),
                        Wait.unreadable());
        assertThat(waits).as("the issue held back").anyMatch(Wait::holds);
        assertThat(waits.get(1).spoken()).isEqualTo("something not readable");
        assertThat(snapshot.board().issue("example-lib#3").orElseThrow().blockedBy())
                .as("no board blocker made up for it")
                .isEmpty();
    }

    @Test
    void aBlockerGitHubCountsButDoesNotNameGivesAnotherRevision() {
        // Harm: a blocker added that the person cannot read is never told to the window, which
        // still shows its issue free and saves against a board that no longer stands.
        final Snapshot named =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), hiding(3, 0, FAR))
                        .boardPage(elsewhere(FAR, "OPEN"))
                        .read();
        final Snapshot hidden =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), hiding(3, 1, FAR))
                        .boardPage(elsewhere(FAR, "OPEN"))
                        .read();

        assertThat(hidden.board().revision())
                .as("the revision once GitHub counts a blocker it does not name")
                .isNotEqualTo(named.board().revision());
    }

    @Test
    void aCopyKeepsABlockerGitHubCountsButDoesNotName() {
        // Harm: a board shown at start from its copy offers the issue, its unreadable wait lost.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), hiding(3, 2, FAR))
                        .boardPage(elsewhere(FAR, "CLOSED"));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot read = reader.read(CONFIG);

        final BoardCopy copy = reader.toCopy(read, ChangeCursor.NONE, List.of());
        assertThat(copy).as("the copy").isNotNull();
        final Snapshot loaded = reader.fromCopy(copy, CONFIG);

        assertThat(loaded.board().issue("example-lib#3").orElseThrow().waitsOn())
                .as("both waits not readable kept from the copy")
                .containsExactly(
                        new Wait(
                                "other-org/elsewhere#7",
                                true,
                                java.net.URI.create(
                                        "https://github.com/other-org/elsewhere/issues/7")),
                        Wait.unreadable(),
                        Wait.unreadable());
        assertThat(loaded.board().revision())
                .as("the copy's board the one read")
                .isEqualTo(read.board().revision());
    }

    @Test
    void aStartOnAnIssueWithABlockerGitHubCountsButDoesNotNameReadsItAsNotRead() {
        // Harm: a blocker the person cannot read, added after the board was read, is missed by the
        // start, which then starts work over it.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), waiting(3, FAR))
                        .boardPage(elsewhere(FAR, "CLOSED"));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot held = reader.read(CONFIG);
        github.boardPage(onBoard(hiding(3, 1, FAR)), elsewhere(FAR, "CLOSED"));

        final BoardReader.StartRead read = reader.waitsOf(CONFIG, held, Set.of(LIB_3));

        assertThat(read.unread()).as("the issue not known free").containsExactly(LIB_3);
        assertThat(read.blockers().get(LIB_3))
                .as("the blocker GitHub names still read")
                .extracting(IssueRef::id)
                .containsExactly("I_far_7");
    }

    @Test
    void twoReadsAfterSavesFetchedAgainstOneBoardBothStandWhenLaidOntoTheNewest() {
        // Harm: once saves run at once, the second save's read after it lays the board it was
        // made against over the first save's items, and the first save's moves are lost.
        final String lib4 = node(OWNER + "/example-lib", 4);
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(
                                null,
                                story("High", LIB_3, lib4),
                                libIssue(3, refs(false)),
                                libIssue(4, refs(false)))
                        .boardItems(inIdea(libIssue(3, refs(false))))
                        .boardItems(inIdea(libIssue(4, refs(false))));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot held = reader.read(CONFIG);
        final Reread first =
                reader.fetch(
                        CONFIG,
                        held,
                        new Touched.Ids(Set.of(LIB_3), Set.of(), Set.of(), Set.of()),
                        Map.of());
        final Reread second =
                reader.fetch(
                        CONFIG,
                        held,
                        new Touched.Ids(Set.of(lib4), Set.of(), Set.of(), Set.of()),
                        Map.of());

        final Snapshot after = reader.apply(reader.apply(held, first), second);

        assertThat(
                        java.util.stream.Stream.of("example-lib#3", "example-lib#4")
                                .map(id -> after.board().issue(id).orElseThrow().state()))
                .as("both issues as their own saves read them")
                .containsExactly("Idea", "Idea");
    }

    @Test
    void aReadByIdLaidOntoABoardReadSinceKeepsThatBoardsProjectSettings() {
        // Harm: a save's read after it, begun before a Reload read the project's priorities anew,
        // lays the older settings back onto the board, so a correction offers stale priorities.
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), libIssue(3, refs(false)))
                        .boardItems(inIdea(libIssue(3, refs(false))));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot held = reader.read(CONFIG);
        final Reread delta =
                reader.fetch(
                        CONFIG,
                        held,
                        new Touched.Ids(Set.of(LIB_3), Set.of(), Set.of(), Set.of()),
                        Map.of());
        final ProjectConfig reloaded = held.config().withPriorities(List.of("High", "Medium"));
        final Snapshot newest =
                new Snapshot(
                        held.board(),
                        held.items(),
                        reloaded,
                        held.raw(),
                        held.fieldFaults(),
                        held.fieldNames(),
                        held.restFieldIds(),
                        held.waits(),
                        held.unlisted());

        final Snapshot after = reader.apply(newest, delta);

        assertThat(after.config())
                .as("the project's settings after the read by id is laid on")
                .isEqualTo(reloaded);
        assertThat(after.board().issue("example-lib#3").orElseThrow().state())
                .as("the issue the read by id gave")
                .isEqualTo("Idea");
    }

    /** The item moved to Idea, its other values kept. */
    private static ProjectItem inIdea(final ProjectItem item) {
        final List<ItemValue> values = new java.util.ArrayList<>(item.values());
        values.replaceAll(
                value -> value.fieldId().equals("F_status") ? option("F_status", "Idea") : value);
        return new ProjectItem(
                item.id(), item.archived(), item.issue(), values, item.valuesPage(), 0);
    }

    @Test
    void theWaitNotReadableClearsOnceGitHubCountsNoMoreBlockersThanItNames() {
        // Harm: an issue stays held on a blocker GitHub no longer counts, so work never starts on
        // it after the person lost or regained sight of that blocker.
        final ProjectItem now = hiding(3, 0, FAR);
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), hiding(3, 1, FAR))
                        .boardPage(elsewhere(FAR, "CLOSED"))
                        .boardItems(now)
                        .boardPage(elsewhere(FAR, "CLOSED"));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot held = reader.read(CONFIG);
        assertThat(held.board().issue("example-lib#3").orElseThrow().waitsOn())
                .as("held on the blocker not named")
                .contains(Wait.unreadable());

        final Snapshot after =
                reader.reread(
                        CONFIG, held, new Touched.Ids(Set.of(LIB_3), Set.of(), Set.of(), Set.of()));

        assertThat(after.board().issue("example-lib#3").orElseThrow().waitsOn())
                .as("only the named blocker, done")
                .extracting(Wait::reference, Wait::done)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("other-org/elsewhere#7", true));
    }

    @Test
    void anItemWithASecondPageOfBlockersCountsNoneUnseenAndIsShownHeldPastOnePage() {
        // Harm: an item cut at one page of blockers is shown with waits not readable for the
        // blockers on the pages after, as though it were whole, or not held.
        final IssueRefs more = new IssueRefs(List.of(FAR), new PageInfo(true, "more"), 150);
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), blocked(3, more))
                        .boardItems(blocked(3, more))
                        .boardPage(elsewhere(FAR, "OPEN"))
                        .read();

        assertThat(ItemsBoardReader.unseen(more)).as("none counted unseen").isZero();
        assertThat(snapshot.board().issue("example-lib#3").orElseThrow().warnings())
                .as("the issue shown, held as read past one page")
                .containsExactly(org.fuin.porelo.planner.store.Warning.pastOnePage());
        assertThat(snapshot.board().faults()).as("no fault beside the warning").isEmpty();
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

    @Test
    void aBlockerWhoseStateGitHubNamesNeitherOpenNorClosedIsNotKnownDone() {
        // Harm: a state GitHub adds, or none, read as closed, so work starts over an open blocker.
        final Snapshot snapshot =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), waiting(3, FAR, HIDDEN))
                        .boardPage(elsewhere(FAR, "MERGED"), elsewhere(HIDDEN, "CLOSED"))
                        .read();

        assertThat(snapshot.board().issue("example-lib#3").orElseThrow().waitsOn())
                .as("only the closed one done")
                .extracting(Wait::done)
                .containsExactly(null, true);
    }

    @Test
    void aStartReadsTheIssuesOwnBlockersAndItsKnownWaitsInOneCall() {
        // Harm: a blocker added within one look is missed by the start; or the start pays a call
        // for each thing it checks.
        final ProjectItem three = waiting(3, FAR);
        final GitHub github =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), three)
                        .boardPage(elsewhere(FAR, "OPEN"));
        final ItemsBoardReader reader = github.reader(AccessRefusals.DEFAULT);
        final Snapshot held = reader.read(CONFIG);
        final ProjectItem now = waiting(3, FAR, HIDDEN);
        github.boardPage(onBoard(now), elsewhere(FAR, "CLOSED"));

        final BoardReader.StartRead read = reader.waitsOf(CONFIG, held, Set.of(LIB_3));

        assertThat(github.issueBatches)
                .as("the board's read, then one call for the issue and its known wait")
                .containsExactly(List.of("I_far_7"), List.of(LIB_3, "I_far_7"));
        assertThat(read.blockers().get(LIB_3))
                .as("the issue's blockers as GitHub names them now")
                .extracting(IssueRef::id)
                .containsExactly("I_far_7", "I_far_8");
        assertThat(read.waits())
                .as("the known wait read, the new one not known done")
                .containsEntry("I_far_7", new Snapshot.Waited("other-org/elsewhere", 7, true))
                .containsEntry("I_far_8", new Snapshot.Waited("other-org/private", 8, null));
        assertThat(read.unread()).as("nothing unread").isEmpty();
    }

    @Test
    void theWorkUnderAParentWithAWorkerThePlannerDoesNotKnowIsHeldOnTheBoardRead() {
        // Harm: a sub-issue of a parent a person gave a Worker the planner does not know is
        // started by an agent, because the board read lost the warning or the link to the parent.
        final String lib4 = node(OWNER + "/example-lib", 4);
        final ProjectItem parent =
                new ProjectItem(
                        "PVTI_lib_3",
                        false,
                        issue(
                                OWNER + "/example-lib",
                                3,
                                List.of(),
                                node(OWNER + "/example-project", 1),
                                refs(false, lib4)),
                        List.of(option("F_status", "Backlog"), option("F_worker", "Person")),
                        info(null),
                        0);
        final ProjectItem part =
                new ProjectItem(
                        "PVTI_lib_4",
                        false,
                        issue(OWNER + "/example-lib", 4, List.of(), LIB_3, refs(false)),
                        List.of(option("F_status", "Backlog")),
                        info(null),
                        0);

        final Board board =
                new GitHub()
                        .fieldsPage(FIELDS, null)
                        .itemsPage(null, story("High", LIB_3), parent, part)
                        .read()
                        .board();

        assertThat(board.issue("example-lib#4").orElseThrow().parent())
                .as("the part stands under its parent")
                .isEqualTo("example-lib#3");
        assertThat(board.holdsWork("example-lib#3"))
                .as("the parent is held for its worker")
                .contains("example-lib#3: " + Warning.unknownWorker().text());
        assertThat(board.holdsWork("example-lib#4"))
                .as("and the part with it, in the parent's words")
                .contains(
                        "example-lib#4: it stands under example-lib#3, which is held back."
                                + " example-lib#3: "
                                + Warning.unknownWorker().text());
    }
}

package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The edits by hand written to GitHub: field values, sub-issues, blockers and a comment. */
class FieldsBoardWriterEditsTest {

    private static final String TOKEN = "github_pat_edits_test_0123456789";

    private static final String OWNER = "example-org";

    private static final Revision SHOWN = new Revision("shown");

    private static final Workflow WORKFLOW =
            new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished"));

    private static final Map<String, String> FIELD_IDS = new LinkedHashMap<>();

    private static final Map<String, Map<String, String>> OPTION_IDS = new LinkedHashMap<>();

    static {
        for (final String field : FieldNames.ALL.keySet()) {
            FIELD_IDS.put(field, "F_" + field.replace(' ', '_'));
        }
        OPTION_IDS.put(FieldNames.STATUS, options(WORKFLOW.states()));
        OPTION_IDS.put(FieldNames.SIZE, options(IssueMapping.SIZES));
        OPTION_IDS.put(FieldNames.PRIORITY, options(Story.PRIORITIES));
    }

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef(OWNER, 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    org.fuin.porelo.planner.store.ProjectRepository.named(
                            List.of("example-project", "example-lib")),
                    FIELD_IDS,
                    OPTION_IDS,
                    "PVT_7");

    /** The story, example-project#9, with its sub-issues example-lib#1 and #2. */
    private static final String STORY = "example-project#9";

    private static final String ONE = "example-lib#1";

    private static final String TWO = "example-lib#2";

    private static final String THREE = "example-lib#3";

    private static Map<String, String> options(final List<String> names) {
        final Map<String, String> ids = new LinkedHashMap<>();
        names.forEach(name -> ids.put(name, "O_" + name));
        return ids;
    }

    /** One item, its node ids derived from its number. */
    private static ItemView item(
            final String id,
            final Map<String, String> fields,
            final @Nullable String parent,
            final List<String> subIssues,
            final List<String> blockedBy) {
        final String repository = id.substring(0, id.indexOf('#'));
        final int number = Integer.parseInt(id.substring(id.indexOf('#') + 1));
        final Map<String, String> all = new LinkedHashMap<>(Map.of(FieldNames.STATUS, "Idea"));
        all.putAll(fields);
        return new ItemView(
                        "PVTI_" + number,
                        node(id),
                        repository,
                        number,
                        "Title of " + id,
                        "",
                        true,
                        null,
                        all,
                        List.of(),
                        parent == null ? null : node(parent),
                        subIssues.stream().map(FieldsBoardWriterEditsTest::node).toList(),
                        blockedBy.stream().map(FieldsBoardWriterEditsTest::node).toList(),
                        URI.create(
                                "https://github.com/"
                                        + OWNER
                                        + "/"
                                        + repository
                                        + "/issues/"
                                        + number),
                        Instant.EPOCH,
                        false)
                .typed(id.equals(STORY) ? IssueMapping.STORY_TYPE : null);
    }

    private static String node(final String id) {
        return "I_" + id.replace('#', '_');
    }

    /** An issue's id in REST: 1000 and its number. */
    private static long databaseId(final ItemView item) {
        return 1000L + item.number();
    }

    private final ItemView story = item(STORY, Map.of(), null, List.of(ONE, TWO), List.of());

    private final ItemView one = item(ONE, Map.of(), STORY, List.of(), List.of());

    private final ItemView two =
            item(TWO, Map.of(FieldNames.SIZE, "M"), STORY, List.of(), List.of(ONE));

    private final ItemView three = item(THREE, Map.of(), null, List.of(), List.of());

    private Snapshot snapshot(final List<String> storyIssues, final ItemView... items) {
        final Map<String, ItemView> byId = new LinkedHashMap<>();
        final List<Issue> issues = new ArrayList<>();
        final Map<String, String> idOf = new LinkedHashMap<>();
        for (final ItemView each : items) {
            idOf.put(each.issueId(), each.repository() + "#" + each.number());
        }
        for (final ItemView each : items) {
            final String id = each.repository() + "#" + each.number();
            byId.put(id, each);
            if (!id.equals(STORY)) {
                issues.add(
                        new Issue(
                                id,
                                each.repository(),
                                each.title(),
                                "Idea",
                                each.fields().get(FieldNames.SIZE),
                                // A blocker no item holds is a wait, never a blocker of the board.
                                each.blockedByIds().stream()
                                        .map(idOf::get)
                                        .filter(java.util.Objects::nonNull)
                                        .toList(),
                                null,
                                each.link(),
                                ""));
            }
        }
        return new Snapshot(
                new Board(
                        SHOWN,
                        WORKFLOW,
                        List.of(
                                new Story(
                                        STORY,
                                        "A story",
                                        "Idea",
                                        "High",
                                        null,
                                        storyIssues,
                                        "",
                                        null)),
                        issues,
                        new Work(Map.of(), List.of()),
                        CONFIG.repositories()),
                byId,
                CONFIG);
    }

    private Snapshot board() {
        return snapshot(List.of(ONE, TWO), story, one, two, three);
    }

    /** GitHub's side: what it holds, and every write made. */
    private static final class GitHub implements ProjectWrites {
        private final Map<String, IssueOnBoard> held = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private final List<List<FieldValue>> mutations = new ArrayList<>();
        private final List<Update> updates = new ArrayList<>();
        private @Nullable RuntimeException updateFailure;
        private @Nullable RuntimeException linkFailure;
        private int linksBeforeFailure;

        GitHub holding(final ItemView... items) {
            for (final ItemView each : items) {
                held.put(each.issueId(), onBoard(each, labelsPastPage));
            }
            return this;
        }

        FieldsBoardWriter writer() {
            return new FieldsBoardWriter(
                    this,
                    origin -> TOKEN,
                    new ForgeAccessRefusals(URI.create("https://github.com")));
        }

        private static IssueOnBoard onBoard(final ItemView item, final boolean labelsPastPage) {
            final List<ItemValue> values = new ArrayList<>();
            item.fields()
                    .forEach(
                            (field, value) -> {
                                if (FieldNames.PRIORITY.equals(field)) {
                                    return;
                                }
                                final Map<String, String> options = OPTION_IDS.get(field);
                                values.add(
                                        options != null
                                                ? new ItemValue(
                                                        FIELD_IDS.get(field),
                                                        options.get(value),
                                                        value,
                                                        null,
                                                        null)
                                                : new ItemValue(
                                                        FIELD_IDS.get(field),
                                                        null,
                                                        null,
                                                        null,
                                                        Double.valueOf(value)));
                            });
            return new IssueOnBoard(
                    new BoardIssue(
                            item.issueId(),
                            databaseId(item),
                            item.number(),
                            item.title(),
                            item.body(),
                            "OPEN",
                            null,
                            item.updatedAt(),
                            OWNER + "/" + item.repository(),
                            item.labels(),
                            item.parentIssueId(),
                            refs(item.subIssueIds()),
                            refs(item.blockedByIds()),
                            new GitHubGraphQl.PageInfo(labelsPastPage, null),
                            item.issueType(),
                            null,
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

        private static IssueRefs refs(final List<String> nodes) {
            return new IssueRefs(
                    nodes.stream().map(n -> new IssueRef(n, 0L, 0, "")).toList(),
                    new PageInfo(false, null),
                    nodes.size());
        }

        @Override
        public GitHubGraphQl.IssueBoard issueBoard(
                final String token, final List<String> issueIds, final String projectId) {
            calls.add("issueBoard");
            return new GitHubGraphQl.IssueBoard(
                    issueIds.stream().map(held::get).toList(),
                    new GraphQlCost(1, 4999, Instant.EPOCH));
        }

        @Override
        public Batch<String> setFieldValues(
                final String token, final String projectId, final List<FieldValue> values) {
            calls.add("setFieldValues");
            mutations.add(List.copyOf(values));
            return new Batch<>(
                    values.stream().map(v -> Batch.Outcome.of(v.itemId())).toList(),
                    null,
                    GitHubExceptions.WITHOUT_A_WORD);
        }

        private void link(final String call) {
            final RuntimeException failure = linkFailure;
            if (failure != null && linksBeforeFailure-- == 0) {
                throw failure;
            }
            calls.add(call);
        }

        @Override
        public Batch<String> addProjectItems(
                final String token, final String projectId, final List<String> issueIds) {
            throw new AssertionError("an edit added an item to the project");
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
            throw new AssertionError("an edit made an issue");
        }

        @Override
        public FoundPage issuesCreatedSince(
                final String token,
                final String owner,
                final String repository,
                final Instant since,
                final int page) {
            throw new AssertionError("an edit looked for issues it made");
        }

        @Override
        public void addSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {
            link("addSubIssue " + repository + "#" + number + " " + subIssueId);
        }

        @Override
        public void removeSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {
            link("removeSubIssue " + repository + "#" + number + " " + subIssueId);
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
            link(
                    "reprioritizeSubIssue "
                            + repository
                            + "#"
                            + number
                            + " "
                            + subIssueId
                            + " after "
                            + afterId
                            + " before "
                            + beforeId);
        }

        @Override
        public void addBlockedBy(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long blockerId) {
            link("addBlockedBy " + repository + "#" + number + " " + blockerId);
        }

        @Override
        public void removeBlockedBy(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long blockerId) {
            link("removeBlockedBy " + repository + "#" + number + " " + blockerId);
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
            assertThat(opening).as("an edit by hand leaves the open state").isNull();
            final RuntimeException failure = updateFailure;
            if (failure != null) {
                throw failure;
            }
            if (issueFieldValues != null) {
                priorityUpdates.add(issueFieldValues);
            }
            calls.add("updateIssue " + repository + "#" + number);
            updates.add(new Update(title, body, labels));
            final IssueOnBoard now =
                    held.values().stream()
                            .filter(
                                    each ->
                                            each.issue().number() == number
                                                    && each.issue()
                                                            .repository()
                                                            .endsWith("/" + repository))
                            .findFirst()
                            .orElseThrow();
            if (labels == null) {
                return now.issue().labels();
            }
            // An account without the right to label has the labels left as they were.
            return labelsDropped ? now.issue().labels() : answers.apply(labels);
        }

        private boolean labelsDropped;

        /** The labels GitHub answers for those sent: those sent, unless a test says otherwise. */
        private java.util.function.UnaryOperator<List<String>> answers = sent -> sent;

        private boolean labelsPastPage;

        private final List<List<IssuesUpdateRequestIssueFieldValuesInner>> priorityUpdates =
                new ArrayList<>();

        @Override
        public List<IssueField> issueFields(final String token, final String owner) {
            return OrganizationFields.fields();
        }

        @Override
        public List<IssueFieldValue> issueFieldValues(
                final String token, final String owner, final String repository, final int number) {
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
            throw new AssertionError("an edit read comments");
        }

        @Override
        public void createIssueComment(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final String body) {
            assertThat(token).as("the comment written with the person's token").isEqualTo(TOKEN);
            link("createIssueComment " + repository + "#" + number + " " + body);
        }
    }

    /**
     * One update of an issue as GitHub was sent it.
     *
     * @param title the title sent, or null
     * @param body the body sent, or null
     * @param labels the labels sent, or null
     */
    private record Update(
            @Nullable String title, @Nullable String body, @Nullable List<String> labels) {
        Update(final @Nullable String title, final @Nullable String body) {
            this(title, body, null);
        }
    }

    /** An item with other labels, everything else as it was. */
    private static ItemView labelled(final ItemView item, final List<String> labels) {
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
                labels,
                item.parentIssueId(),
                item.subIssueIds(),
                item.blockedByIds(),
                item.link(),
                item.updatedAt(),
                item.archived());
    }

    /** An item with another title and body, everything else as it was. */
    private static ItemView text(final ItemView item, final String title, final String body) {
        return new ItemView(
                item.itemId(),
                item.issueId(),
                item.repository(),
                item.number(),
                title,
                body,
                item.open(),
                item.stateReason(),
                item.fields(),
                item.labels(),
                item.parentIssueId(),
                item.subIssueIds(),
                item.blockedByIds(),
                item.link(),
                item.updatedAt(),
                item.archived());
    }

    private GitHub github() {
        return new GitHub().holding(story, one, two, three);
    }

    @Test
    void fieldsAreWrittenInOneMutationAndAValueTheIssueHoldsIsLeft() {
        // A field written call by call is torn apart by a failure; a value written again for
        // nothing announces a change nobody made.
        final GitHub github = github();
        final boolean wrote =
                github.writer()
                        .setFields(
                                board(),
                                SHOWN,
                                TWO,
                                new Fields(
                                        Set.of(
                                                Fields.Field.SIZE,
                                                Fields.Field.ESTIMATE,
                                                Fields.Field.PRIORITY,
                                                Fields.Field.RANK),
                                        "M",
                                        90,
                                        "Low",
                                        null));

        assertThat(wrote).as("something was written").isTrue();
        assertThat(github.mutations).as("the mutations").hasSize(1);
        assertThat(github.mutations.get(0))
                .as("the estimate; the size held already, no rank to clear")
                .containsExactly(FieldValue.number("PVTI_2", "F_Estimate", 90));
        assertThat(github.priorityUpdates)
                .as("Priority is an issue field")
                .containsExactly(
                        List.of(
                                new IssuesUpdateRequestIssueFieldValuesInner(
                                        11L, "Low", null, null, null)));
    }

    @Test
    void aClearedSizeIsClearedAndFieldsEqualToTheIssuesWriteNothing() {
        // A null that left the size would keep a value the person took away.
        final GitHub github = github();
        github.writer()
                .setFields(
                        board(),
                        SHOWN,
                        TWO,
                        new Fields(Set.of(Fields.Field.SIZE), null, null, null, null));
        assertThat(github.mutations.get(0))
                .as("the size cleared")
                .containsExactly(FieldValue.clear("PVTI_2", "F_Size"));

        final GitHub same = github();
        assertThat(
                        same.writer()
                                .setFields(
                                        board(),
                                        SHOWN,
                                        TWO,
                                        new Fields(
                                                Set.of(Fields.Field.SIZE), "M", null, null, null)))
                .as("the size it holds")
                .isFalse();
        assertThat(same.calls).as("no call for a size it holds").isEmpty();
    }

    @Test
    void everyEditAgainstAnotherRevisionIsRefusedWithoutACall() {
        // An edit planned on an old board would overwrite what the board read since.
        final GitHub github = github();
        final Revision older = new Revision("older");
        for (final Runnable edit :
                List.<Runnable>of(
                        () ->
                                github.writer()
                                        .setFields(
                                                board(),
                                                older,
                                                ONE,
                                                new Fields(
                                                        Set.of(Fields.Field.SIZE),
                                                        "S",
                                                        null,
                                                        null,
                                                        null)),
                        () -> github.writer().setStoryIssues(board(), older, STORY, List.of(ONE)),
                        () -> github.writer().setBlockedBy(board(), older, ONE, List.of(THREE)))) {
            assertThat(catchThrowableOfType(StoreException.StaleRevision.class, edit::run))
                    .as("an edit against an older revision")
                    .isNotNull();
        }
        assertThat(github.calls).as("the calls made").isEmpty();
    }

    @Test
    void aStorysListTakesOutHangsInAndOrdersByTheChildrensDatabaseIds() {
        // GitHub names a sub-issue by its id in REST: a node id or a number would link another
        // issue, or none.
        final GitHub github = github();

        github.writer().setStoryIssues(board(), SHOWN, STORY, List.of(THREE, ONE));

        assertThat(github.calls)
                .as("the read, then out, in, and the new one put first")
                .containsExactly(
                        "issueBoard",
                        "removeSubIssue example-project#9 1002",
                        "addSubIssue example-project#9 1003",
                        "reprioritizeSubIssue example-project#9 1003 after null before 1001");
    }

    @Test
    void anIssueHeldUnderAlsoNeedsOrWithAParentOnGitHubIsRefusedWithoutACall() {
        // Taking out an issue the body names would leave it on the board; hanging in one with a
        // parent would take it from that parent.
        final ItemView alsoNeeded = item(THREE, Map.of(), null, List.of(), List.of());
        final GitHub github = github();
        final StoreException.NotSavable kept =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                github.writer()
                                        .setStoryIssues(
                                                snapshot(
                                                        List.of(ONE, TWO, THREE),
                                                        story,
                                                        one,
                                                        two,
                                                        alsoNeeded),
                                                SHOWN,
                                                STORY,
                                                List.of(ONE, TWO)));
        assertThat(kept).as("an Also needs issue taken out").isNotNull();

        final ItemView adopted = item(THREE, Map.of(), "example-project#8", List.of(), List.of());
        final StoreException.NotSavable parented =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                github.writer()
                                        .setStoryIssues(
                                                snapshot(
                                                        List.of(ONE, TWO),
                                                        story,
                                                        one,
                                                        two,
                                                        adopted),
                                                SHOWN,
                                                STORY,
                                                List.of(ONE, TWO, THREE)));
        assertThat(parented).as("an issue with a parent on GitHub hung in").isNotNull();
        assertThat(github.calls).as("the calls made").isEmpty();
    }

    @Test
    void aListMovingAnAlsoNeededIssueBeforeTheSubIssuesIsRefusedWithoutACall() {
        // GitHub orders sub-issues only and the board puts the body's issues after them, so the
        // list would be answered as saved while the story keeps its order.
        assertThat(refusedList(List.of(THREE, ONE, TWO)))
                .as("an Also needs issue moved before the sub-issues")
                .isEmpty();
    }

    @Test
    void aListHangingAnIssueInAfterAnAlsoNeededOneIsRefusedWithoutACall() {
        // The new sub-issue would stand before the body's issue on the board read back, in
        // another order than the one answered as saved.
        assertThat(refusedList(List.of(ONE, TWO, THREE, "example-lib#4")))
                .as("an issue hung in after an Also needs issue")
                .isEmpty();
    }

    /**
     * Sets the list of a story whose body names example-lib#3 under Also needs, expecting it
     * refused, and answers the calls made.
     */
    private List<String> refusedList(final List<String> issues) {
        final ItemView alsoNeeded = item(THREE, Map.of(), null, List.of(), List.of());
        final ItemView four = item("example-lib#4", Map.of(), null, List.of(), List.of());
        final GitHub github = github().holding(four);
        final Snapshot board =
                snapshot(List.of(ONE, TWO, THREE), story, one, two, alsoNeeded, four);
        assertThat(
                        catchThrowableOfType(
                                StoreException.NotSavable.class,
                                () -> github.writer().setStoryIssues(board, SHOWN, STORY, issues)))
                .as("the list refused as not savable")
                .isNotNull();
        return github.calls;
    }

    @Test
    void aChildAPersonChangedOnGitHubMeanwhileIsRefusedNamingItWithoutALink() {
        // A child given another parent on GitHub meanwhile would be taken from it unseen.
        final GitHub github = github();
        github.holding(item(THREE, Map.of(), "example-project#8", List.of(), List.of()));

        final StoreException.StaleRevision stale =
                catchThrowableOfType(
                        StoreException.StaleRevision.class,
                        () ->
                                github.writer()
                                        .setStoryIssues(
                                                board(), SHOWN, STORY, List.of(ONE, TWO, THREE)));

        assertThat(stale).as("a link over a changed child").isNotNull();
        assertThat(stale.changed()).as("the child named").isEqualTo(THREE);
        assertThat(github.calls).as("only the read").containsExactly("issueBoard");
    }

    /** An issue's body as the skeleton writes it, with a section of a person's own at its end. */
    private static final String BODY =
            "What it covers.\r\n\r\n**What must be true.** It works.\r\n\r\n"
                    + "**Why this size.** One module.\r\n\r\n## Plan\r\n\r\n- Old plan.\r\n\r\n"
                    + "## Findings\r\n\r\n- Found one.\r\n\r\n## Notes of my own\r\n\r\nKept.";

    private Snapshot texts(final ItemView issue) {
        return snapshot(List.of(ONE, TWO), story, issue, two, three);
    }

    private static TextEdit edit(
            final @Nullable String title, final TextEdit.Part part, final String text) {
        return new TextEdit(TextEdit.Kind.ISSUE, title, Map.of(part, text), Set.of());
    }

    @Test
    void aTitleOrABodyAPersonChangedOnGitHubSinceTheReadIsStaleAndNothingIsWritten() {
        // A person's correction on GitHub overwritten unseen by an edit planned on the old item.
        final ItemView read = text(one, "Title of " + ONE, BODY);
        for (final ItemView changed :
                List.of(
                        text(one, "A person's title", BODY),
                        text(one, "Title of " + ONE, BODY.replace("Kept.", "Changed.")))) {
            final GitHub github = github().holding(changed);

            final StoreException.StaleRevision stale =
                    catchThrowableOfType(
                            StoreException.StaleRevision.class,
                            () ->
                                    github.writer()
                                            .setText(
                                                    texts(read),
                                                    SHOWN,
                                                    ONE,
                                                    edit(
                                                            "A new title",
                                                            TextEdit.Part.PLAN,
                                                            "- New plan.")));

            assertThat(stale).as("an edit over a changed item").isNotNull();
            assertThat(stale.changed()).as("the item named").isEqualTo(ONE);
            assertThat(github.calls).as("only the read").containsExactly("issueBoard");
        }
    }

    @Test
    void anItemRetypedOnGitHubSinceTheReadIsStaleAndNothingIsWritten() {
        // Harm: a save planned on an item went ahead after a person gave it another issue type on
        // GitHub, as a story made a bug, so the save wrote to what the board no longer maps so.
        final ItemView read = text(one, "Title of " + ONE, BODY);
        final GitHub github = github().holding(read.typed("Bug"));

        final StoreException.StaleRevision stale =
                catchThrowableOfType(
                        StoreException.StaleRevision.class,
                        () ->
                                github.writer()
                                        .setText(
                                                texts(read),
                                                SHOWN,
                                                ONE,
                                                edit(
                                                        "A new title",
                                                        TextEdit.Part.PLAN,
                                                        "- New plan.")));

        assertThat(stale).as("an edit over a retyped item").isNotNull();
        assertThat(stale.changed()).as("the item named").isEqualTo(ONE);
        assertThat(github.calls).as("only the read").containsExactly("issueBoard");
    }

    @Test
    void anEditAgainstAnOldRevisionWritesNothingAndReadsNothing() {
        // An edit planned on an old board would write over what someone saved since.
        final GitHub github = github();

        assertThat(
                        catchThrowableOfType(
                                StoreException.StaleRevision.class,
                                () ->
                                        github.writer()
                                                .setText(
                                                        board(),
                                                        new Revision("older"),
                                                        ONE,
                                                        edit(null, TextEdit.Part.PLAN, "- New."))))
                .as("an edit against an old revision")
                .isNotNull();
        assertThat(github.calls).as("the calls made").isEmpty();
    }

    @Test
    void titleAndBodyGoInOneUpdateAndEverySectionNotNamedKeepsEveryByte() {
        // A section the person did not name changed by their edit loses what an agent or a person
        // wrote there; title and body in two calls are torn apart by a failure.
        final ItemView read = text(one, "Title of " + ONE, BODY);
        final GitHub github = github().holding(read);

        final boolean wrote =
                github.writer()
                        .setText(
                                texts(read),
                                SHOWN,
                                ONE,
                                new TextEdit(
                                        TextEdit.Kind.ISSUE,
                                        "A new title",
                                        Map.of(
                                                TextEdit.Part.WHAT_MUST_BE_TRUE,
                                                "It works well.",
                                                TextEdit.Part.PLAN,
                                                "- New plan."),
                                        Set.of()));

        assertThat(wrote).as("something was written").isTrue();
        assertThat(github.calls)
                .as("the read, then one update")
                .containsExactly("issueBoard", "updateIssue example-lib#1");
        assertThat(github.updates)
                .as("the title and the body, Findings and the own section byte for byte")
                .containsExactly(
                        new Update(
                                "A new title",
                                BODY.replace("It works.", "It works well.")
                                        .replace("- Old plan.", "- New plan.")));
    }

    @Test
    void topicLabelsGoWithTheTitleInOneUpdateAndThePersonsOwnLabelStays() {
        // Harm: a label of the person's own lost by a write of the topic labels, or a write sent
        // for labels the issue already carries.
        final ItemView read = labelled(one, List.of("needs-design", "usability"));
        final GitHub github = github().holding(read);

        final boolean wrote =
                github.writer()
                        .setText(
                                texts(read),
                                SHOWN,
                                ONE,
                                new TextEdit(
                                        TextEdit.Kind.ISSUE,
                                        "A new title",
                                        Map.of(),
                                        Set.of(),
                                        List.of("tests", "security")));

        assertThat(wrote).as("something was written").isTrue();
        assertThat(github.calls)
                .as("the read, then one update")
                .containsExactly("issueBoard", "updateIssue example-lib#1");
        assertThat(github.updates)
                .as("the title and every label the issue is to carry, its own first")
                .containsExactly(
                        new Update(
                                "A new title", null, List.of("needs-design", "security", "tests")));

        final GitHub same = github().holding(read);
        assertThat(
                        same.writer()
                                .setText(
                                        texts(read),
                                        SHOWN,
                                        ONE,
                                        new TextEdit(
                                                TextEdit.Kind.ISSUE,
                                                null,
                                                Map.of(),
                                                Set.of(),
                                                List.of("usability"))))
                .as("the topic labels it carries")
                .isFalse();
        assertThat(same.calls).as("no call").isEmpty();
    }

    @Test
    void labelsGitHubDroppedInSilenceAreNotKeptAndTheTitleWrittenWithThemStands() {
        // Harm: an account without the right to label sees its labels saved that GitHub left out.
        final ItemView read = labelled(one, List.of("needs-design"));
        final GitHub github = github().holding(read);
        github.labelsDropped = true;

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .setText(
                                                texts(read),
                                                SHOWN,
                                                ONE,
                                                new TextEdit(
                                                        TextEdit.Kind.ISSUE,
                                                        "A new title",
                                                        Map.of(),
                                                        Set.of(),
                                                        List.of("tests"))))
                .isInstanceOfSatisfying(
                        StoreException.LabelsNotKept.class,
                        refused -> {
                            assertThat(refused.subject()).isEqualTo("example-lib");
                            assertThat(refused.item()).isEqualTo(ONE);
                        });
        assertThat(github.updates)
                .as("the title was sent with the labels")
                .containsExactly(new Update("A new title", null, List.of("needs-design", "tests")));
    }

    /** A labels edit of issue ONE setting the topic labels given, nothing else. */
    private static TextEdit topics(final String... labels) {
        return new TextEdit(TextEdit.Kind.ISSUE, null, Map.of(), Set.of(), List.of(labels));
    }

    @Test
    void aLabelGitHubAnswersInAnotherCaseIsSaved() {
        // Harm: labels GitHub kept, matching a repository's label named in another case, were
        // answered as not kept.
        final ItemView read = labelled(one, List.of("needs-design"));
        final GitHub github = github().holding(read);
        github.answers =
                sent ->
                        sent.stream()
                                .map(label -> label.equals("tests") ? "Tests" : label)
                                .toList();

        assertThat(github.writer().setText(texts(read), SHOWN, ONE, topics("tests")))
                .as("saved")
                .isTrue();
        assertThat(github.updates)
                .containsExactly(new Update(null, null, List.of("needs-design", "tests")));
    }

    @Test
    void aTopicLabelCarriedInAnotherCaseCountsAsTheTopicLabelAndIsNeverSentTwice() {
        // Harm: a topic label carried as Tests could not be taken off, or a write sent Tests
        // and tests together, which GitHub takes as one label named twice.
        final ItemView read = labelled(one, List.of("Tests", "needs-design"));

        final GitHub same = github().holding(read);
        assertThat(same.writer().setText(texts(read), SHOWN, ONE, topics("tests")))
                .as("the topic label it carries, in another case")
                .isFalse();
        assertThat(same.calls).as("no call").isEmpty();

        final GitHub off = github().holding(read);
        assertThat(off.writer().setText(texts(read), SHOWN, ONE, topics())).isTrue();
        assertThat(off.updates)
                .as("the topic label taken off, the person's own kept")
                .containsExactly(new Update(null, null, List.of("needs-design")));

        final GitHub other = github().holding(read);
        assertThat(other.writer().setText(texts(read), SHOWN, ONE, topics("tests", "build")))
                .isTrue();
        assertThat(other.updates)
                .as("each topic label once")
                .containsExactly(new Update(null, null, List.of("needs-design", "tests", "build")));
    }

    @Test
    void aLabelOfThePersonsOwnGitHubDroppedIsNotKeptThoughTheTopicLabelsStand() {
        // Harm: a write that lost a label of the person's own was answered as saved.
        final ItemView read = labelled(one, List.of("needs-design", "customer"));
        final GitHub github = github().holding(read);
        github.answers = sent -> sent.stream().filter(label -> !label.equals("customer")).toList();

        assertThatThrownBy(() -> github.writer().setText(texts(read), SHOWN, ONE, topics("tests")))
                .isInstanceOf(StoreException.LabelsNotKept.class);
    }

    @Test
    void aLabelAddedOnGitHubSinceTheBoardWasReadStaysThroughAWriteOfTheTopicLabels() {
        // Harm: a label someone added on GitHub after the store's board was read, which the
        // window saw at the version it sends, was taken off by the next write of the topic
        // labels.
        final ItemView shown = labelled(one, List.of("usability"));
        final ItemView now = labelled(one, List.of("usability", "added-later"));
        final GitHub github = github().holding(now);

        assertThat(
                        github.writer()
                                .setText(
                                        texts(shown),
                                        org.fuin.porelo.planner.store.Basis.none()
                                                .with(ONE, ItemVersions.of(now)),
                                        ONE,
                                        topics("tests")))
                .isTrue();
        assertThat(github.updates)
                .as("every label GitHub holds now but the topic labels, then those asked")
                .containsExactly(new Update(null, null, List.of("added-later", "tests")));
    }

    @Test
    void anIssueWithMoreLabelsThanOneReadGivesIsNotRelabelledAndNothingIsWritten() {
        // Harm: the labels past the read's page are lost by a write of every label it carries.
        final ItemView read = labelled(one, List.of("needs-design"));
        final GitHub github = new GitHub();
        github.labelsPastPage = true;
        github.holding(story, read, two, three);

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .setText(
                                                texts(read),
                                                SHOWN,
                                                ONE,
                                                new TextEdit(
                                                        TextEdit.Kind.ISSUE,
                                                        "A new title",
                                                        Map.of(),
                                                        Set.of(),
                                                        List.of("tests"))))
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(github.calls).as("only the read").containsExactly("issueBoard");
    }

    @Test
    void aRemovedSectionGoesWithItsHeadingAndATextEqualToTheItemsWritesNothing() {
        // A removal that leaves its heading shows an empty section; a write of nothing announces a
        // change nobody made.
        final ItemView read = text(one, "Title of " + ONE, BODY);
        final GitHub github = github().holding(read);

        github.writer()
                .setText(
                        texts(read),
                        SHOWN,
                        ONE,
                        new TextEdit(
                                TextEdit.Kind.ISSUE,
                                null,
                                Map.of(),
                                Set.of(TextEdit.Part.FINDINGS)));
        assertThat(github.updates)
                .as("the body without Findings, the title left")
                .containsExactly(
                        new Update(
                                null, BODY.replace("## Findings\r\n\r\n- Found one.\r\n\r\n", "")));

        final GitHub same = github().holding(read);
        assertThat(
                        same.writer()
                                .setText(
                                        texts(read),
                                        SHOWN,
                                        ONE,
                                        edit("Title of " + ONE, TextEdit.Part.PLAN, "- Old plan.")))
                .as("a title and a plan the issue holds")
                .isFalse();
        assertThat(same.calls).as("no call").isEmpty();
    }

    @Test
    void aStorysGainIsWrittenAndItsTestAndAlsoNeedsStayAsTheyWere() {
        // A story's gain written over its test or its list of issues loses what the story needs.
        final String body =
                "**What a user gains.** A gain.\n\n**The test.** A test.\n\n"
                        + "## Also needs\n\n- example-lib#3\n";
        final ItemView read = text(story, "Title of " + STORY, body);
        final GitHub github = github().holding(read);

        github.writer()
                .setText(
                        snapshot(List.of(ONE, TWO), read, one, two, three),
                        SHOWN,
                        STORY,
                        new TextEdit(
                                TextEdit.Kind.STORY,
                                null,
                                Map.of(TextEdit.Part.GAIN, "More."),
                                Set.of()));

        assertThat(github.updates)
                .as("the gain written, the rest as it was")
                .containsExactly(new Update(null, body.replace("A gain.", "More.")));
    }

    @Test
    void aTextThatWouldSplitTheBodyOrGrowItPastGitHubsLimitIsRefusedBeforeAnyCall() {
        // A heading or an open block injected through an edit makes every later read find a
        // section nobody wrote or every later stage result skip the body; a body past the limit
        // is refused by GitHub after the check.
        final ItemView read = text(one, "Title of " + ONE, BODY);
        for (final TextEdit edit :
                List.of(
                        edit(null, TextEdit.Part.PLAN, "- a\n## Findings\n- forged"),
                        edit(null, TextEdit.Part.WHY_THIS_SIZE, "One.\n**What must be true.** x"),
                        edit(null, TextEdit.Part.PLAN, "- a\n```\nopen"),
                        edit(null, TextEdit.Part.PLAN, "- a <!-- open"),
                        edit(null, TextEdit.Part.ACCEPTANCE, "- a\n<details>\n- open"),
                        edit(
                                null,
                                TextEdit.Part.FINDINGS,
                                "x".repeat(BodySections.LONGEST - BODY.length() + 20)))) {
            final GitHub github = github().holding(read);

            final TextEdit.Refused refused =
                    catchThrowableOfType(
                            TextEdit.Refused.class,
                            () -> github.writer().setText(texts(read), SHOWN, ONE, edit));

            assertThat(refused).as("the edit %s", edit.written().keySet()).isNotNull();
            assertThat(refused.item()).as("the item named").isEqualTo(ONE);
            assertThat(refused.getMessage()).as("the text not repeated").doesNotContain("forged");
            assertThat(github.calls).as("no call").isEmpty();
        }
    }

    @Test
    void aFenceOpenedOnALeadingPartsFirstLineCannotHideAHeadingFromTheCheck() {
        // Harm: a fence that stops being one once the lead stands before it lets a heading in the
        // text become a section of the body, so a story's issues or an issue's plan are forged.
        final String storyBody = "**What a user gains.** A gain.\n\n**The test.** A test.\n";
        final ItemView storyRead = text(story, "Title of " + STORY, storyBody);
        final ItemView issueRead = text(one, "Title of " + ONE, BODY);
        final String forged = "```\n## Also needs\n- example-lib#3\n```java\ncode\n```";
        final String planned = "```\n## Plan\n- forged\n```java\ncode\n```";
        for (final Object[] call :
                List.of(
                        new Object[] {
                            storyRead,
                            STORY,
                            new TextEdit(
                                    TextEdit.Kind.STORY,
                                    null,
                                    Map.of(TextEdit.Part.GAIN, forged),
                                    Set.of())
                        },
                        new Object[] {
                            issueRead, ONE, edit(null, TextEdit.Part.WHAT_MUST_BE_TRUE, planned)
                        })) {
            final ItemView read = (ItemView) call[0];
            final GitHub github = github().holding(read);
            final Snapshot board =
                    read == storyRead
                            ? snapshot(List.of(ONE, TWO), storyRead, one, two, three)
                            : texts(read);

            assertThat(
                            catchThrowableOfType(
                                    TextEdit.Refused.class,
                                    () ->
                                            github.writer()
                                                    .setText(
                                                            board,
                                                            SHOWN,
                                                            (String) call[1],
                                                            (TextEdit) call[2])))
                    .as("the text of %s", call[1])
                    .isNotNull();
            assertThat(github.calls).as("no call").isEmpty();
        }
    }

    @Test
    void aFenceOpenedAboveTheFirstSectionAndClosedInOneIsAmbiguousAndCoversIsNotWritten() {
        // Harm: a fence left open in what an issue covers, paired with one closing in a section,
        // makes the whole body read as covers, so an edit of covers overwrites every part.
        final String body =
                "Covers.\n```\n\n**What must be true.** Goal.\n\n## Plan\n\n- Old plan.\n```\n";
        final ItemView read = text(one, "Title of " + ONE, body);
        final GitHub github = github().holding(read);

        assertThat(
                        catchThrowableOfType(
                                TextEdit.Refused.class,
                                () ->
                                        github.writer()
                                                .setText(
                                                        texts(read),
                                                        SHOWN,
                                                        ONE,
                                                        edit(
                                                                null,
                                                                TextEdit.Part.COVERS,
                                                                "New covers."))))
                .as("an edit of covers over a fence that hides the body")
                .isNotNull();
        assertThat(github.calls).as("no call").isEmpty();
    }

    @Test
    void anUpdateGitHubRefusesIsNotSavableInFixedWordsWithoutGitHubsOrThePersonsText() {
        // Harm: GitHub's words, which may repeat the person's title or body, reaching the answer
        // and the log, and a refused write answered as GitHub out of reach.
        final ItemView read = text(one, "Title of " + ONE, BODY);
        final GitHub github = github().holding(read);
        github.updateFailure =
                GitHubExceptions.refused(422, "Validation failed: secret-title-words " + TOKEN);

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                github.writer()
                                        .setText(
                                                texts(read),
                                                SHOWN,
                                                ONE,
                                                edit(
                                                        "secret-title-words",
                                                        TextEdit.Part.PLAN,
                                                        "- New plan.")));

        assertThat(refused).as("a refused update").isNotNull();
        assertThat(refused.getMessage())
                .as("the fixed words")
                .isEqualTo("GitHub refused the call with status 422");
        assertThat(refused.subject()).as("the item named").isEqualTo(ONE);
    }

    @Test
    void whatMustBeTrueOfSeveralParagraphsIsWrittenAsItsSectionAndOneParagraphAsItsLead() {
        // Harm: a goal of several paragraphs refused, or written as a lead beside the section it
        // had, so the body holds two goals and every later write refuses it.
        final ItemView lead = text(one, "Title of " + ONE, BODY);
        final GitHub github = github().holding(lead);

        github.writer()
                .setText(
                        texts(lead),
                        SHOWN,
                        ONE,
                        edit(null, TextEdit.Part.WHAT_MUST_BE_TRUE, "First.\n\nSecond."));

        final String sectioned =
                "What it covers.\r\n\r\n**Why this size.** One module.\r\n\r\n"
                        + "## What must be true\r\n\r\nFirst.\r\n\r\nSecond.\r\n\r\n"
                        + "## Plan\r\n\r\n- Old plan.\r\n\r\n"
                        + "## Findings\r\n\r\n- Found one.\r\n\r\n## Notes of my own\r\n\r\nKept.";
        assertThat(github.updates)
                .as("the lead taken out, the section written above Plan, the rest as it was")
                .containsExactly(new Update(null, sectioned));

        final ItemView section = text(one, "Title of " + ONE, sectioned);
        final GitHub again = github().holding(section);
        again.writer()
                .setText(
                        texts(section),
                        SHOWN,
                        ONE,
                        edit(null, TextEdit.Part.WHAT_MUST_BE_TRUE, "Third.\n\nFourth."));
        assertThat(again.updates)
                .as("the section written over in its place")
                .containsExactly(
                        new Update(
                                null,
                                sectioned.replace(
                                        "First.\r\n\r\nSecond.", "Third.\r\n\r\nFourth.")));

        final GitHub back = github().holding(section);
        back.writer()
                .setText(
                        texts(section),
                        SHOWN,
                        ONE,
                        edit(null, TextEdit.Part.WHAT_MUST_BE_TRUE, "It works."));
        assertThat(back.updates)
                .as("one paragraph written as the lead again, the section gone")
                .containsExactly(
                        new Update(
                                null,
                                "What it covers.\r\n\r\n**Why this size.** One module.\r\n\r\n"
                                        + "**What must be true.** It works.\r\n\r\n"
                                        + "## Plan\r\n\r\n- Old plan.\r\n\r\n"
                                        + "## Findings\r\n\r\n- Found one.\r\n\r\n"
                                        + "## Notes of my own\r\n\r\nKept."));
    }

    @Test
    void blockersAreAddedAndRemovedByTheirDatabaseIds() {
        // A blocker named by anything but its id in REST would block on another issue, or none.
        final GitHub github = github();

        github.writer().setBlockedBy(board(), SHOWN, TWO, List.of(THREE));

        assertThat(github.calls)
                .as("the read, the blocker taken out, the new one added")
                .containsExactly(
                        "issueBoard",
                        "removeBlockedBy example-lib#2 1001",
                        "addBlockedBy example-lib#2 1003");
        assertThat(github.writer().setBlockedBy(board(), SHOWN, TWO, List.of(ONE)))
                .as("the blockers it has")
                .isFalse();
    }

    @Test
    void anEditOfTheBoardsBlockersLeavesAWaitOutsideTheBoardAsItIs() {
        // Harm: an edit of an issue's blockers silently drops a person's link to an issue of
        // another repository, so its issue is offered before that issue is done.
        final ItemView waiting =
                item(TWO, Map.of(FieldNames.SIZE, "M"), STORY, List.of(), List.of(ONE, "far#7"));
        final Snapshot board = snapshot(List.of(ONE, TWO), story, one, waiting, three);
        final GitHub github = new GitHub().holding(story, one, waiting, three);

        github.writer().setBlockedBy(board, SHOWN, TWO, List.of(THREE));
        github.writer().setBlockedBy(board, SHOWN, TWO, List.of());

        assertThat(github.calls)
                .as("only the board's blocker taken out and the new one added, each time")
                .containsExactly(
                        "issueBoard",
                        "removeBlockedBy example-lib#2 1001",
                        "addBlockedBy example-lib#2 1003",
                        "issueBoard",
                        "removeBlockedBy example-lib#2 1001");
    }

    @Test
    void aLinkGitHubRefusesIsNotSavableAndOneAfterAnotherIsPartlySaved() {
        // GitHub's own words may repeat what was sent, and a refusal after a written link told
        // as nothing written would have the window retry what stands.
        final GitHub first = github();
        first.linkFailure = GitHubExceptions.refused(422, "Validation Failed");
        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () -> first.writer().setBlockedBy(board(), SHOWN, TWO, List.of(THREE)));
        assertThat(refused).as("the first link refused").isNotNull();
        assertThat(refused.getMessage())
                .as("in fixed words")
                .isEqualTo("GitHub refused the call with status 422");

        final GitHub second = github();
        second.linkFailure = GitHubExceptions.refused(422, "Validation Failed");
        second.linksBeforeFailure = 1;
        final StoreException.PartlySaved partly =
                catchThrowableOfType(
                        StoreException.PartlySaved.class,
                        () -> second.writer().setBlockedBy(board(), SHOWN, TWO, List.of(THREE)));
        assertThat(partly).as("the second link refused after the first").isNotNull();
        assertThat(partly.pushed()).as("the repository written").containsExactly("example-lib");
    }

    @Test
    void aCommentIsWrittenWithThePersonsTokenAndOneUnansweredIsPartlySaved() {
        // A comment GitHub may have taken told as refused would be written twice on a retry.
        final GitHub github = github();
        github.writer().comment(board(), ONE, "A note.");
        assertThat(github.calls)
                .as("the comment, with no read")
                .containsExactly("createIssueComment example-lib#1 A note.");

        final GitHub silent = github();
        silent.linkFailure = GitHubExceptions.unanswered("timed out");
        assertThat(
                        catchThrowableOfType(
                                StoreException.PartlySaved.class,
                                () -> silent.writer().comment(board(), ONE, "A note.")))
                .as("a comment GitHub did not answer")
                .isNotNull();
    }
}

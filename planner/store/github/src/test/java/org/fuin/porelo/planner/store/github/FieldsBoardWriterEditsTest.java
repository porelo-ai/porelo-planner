package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
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
                    List.of("example-project", "example-lib"),
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
                id.equals(STORY) ? List.of("story") : List.of(),
                parent == null ? null : node(parent),
                subIssues.stream().map(FieldsBoardWriterEditsTest::node).toList(),
                blockedBy.stream().map(FieldsBoardWriterEditsTest::node).toList(),
                null,
                URI.create("https://github.com/" + OWNER + "/" + repository + "/issues/" + number),
                Instant.EPOCH,
                false);
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
                                each.blockedByIds().stream().map(idOf::get).toList(),
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
                                        false,
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
        private @Nullable RuntimeException linkFailure;
        private int linksBeforeFailure;

        GitHub holding(final ItemView... items) {
            for (final ItemView each : items) {
                held.put(each.issueId(), onBoard(each));
            }
            return this;
        }

        FieldsBoardWriter writer() {
            return new FieldsBoardWriter(
                    this,
                    origin -> TOKEN,
                    new ForgeAccessRefusals(URI.create("https://github.com")));
        }

        private static IssueOnBoard onBoard(final ItemView item) {
            final List<ItemValue> values = new ArrayList<>();
            item.fields()
                    .forEach(
                            (field, value) -> {
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
                            refs(item.blockedByIds())),
                    item.itemId(),
                    values);
        }

        private static IssueRefs refs(final List<String> nodes) {
            return new IssueRefs(
                    nodes.stream().map(n -> new IssueRef(n, 0L, 0, "")).toList(),
                    new PageInfo(false, null));
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
                    values.stream().map(v -> Batch.Outcome.of(v.itemId())).toList(), null);
        }

        @Override
        public GitHubGraphQl.FieldOptions setFieldOptions(
                final String token,
                final String fieldId,
                final List<GitHubGraphQl.NewOption> options) {
            throw new AssertionError("an edit replaced a field's options");
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
        public void updateIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final @Nullable String body,
                final @Nullable Opening opening) {
            throw new AssertionError("an edit wrote a body or an open state");
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
                .as("the estimate and the priority; the size held already, no rank to clear")
                .containsExactlyInAnyOrder(
                        FieldValue.number("PVTI_2", "F_Estimate", 90),
                        FieldValue.option("PVTI_2", "F_Priority", "O_Low"));
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

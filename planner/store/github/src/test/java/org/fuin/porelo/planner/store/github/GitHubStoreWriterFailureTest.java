package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.forge.github.model.IssuesCreateRequestIssueFieldValuesInner;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A save the real writer fails, halfway or after its writes, against GitHub's calls: the board is
 * kept where it can be read again by id, with the parts written held, and forgotten otherwise.
 */
class GitHubStoreWriterFailureTest {

    private static final String TOKEN = "invented-token-writer-0123456789";

    private static final String OWNER = "example-org";

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Ready", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Ready", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef(OWNER, 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    List.of("example-project"),
                    Map.of(),
                    Map.of());

    private static final GraphQlCost COST = new GraphQlCost(1, 4999, Instant.EPOCH);

    private static final GitHubGraphQl.PageInfo NONE = new GitHubGraphQl.PageInfo(false, null);

    private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");

    /** More issues than one call of field values carries, so a move of all takes two. */
    private static final int ISSUES = GitHubGraphQl.MAX_BATCH + 1;

    private static final Map<String, String> OPTIONS =
            Map.of("Idea", "O_idea", "Ready", "O_ready", "Finished", "O_finished");

    /**
     * GitHub's project of issues without a story: its item list may lag what an issue read by id
     * shows, as GitHub's lists show a save a moment after it.
     */
    private static final class GitHub implements ProjectQueries {

        /**
         * Each issue's Status option by its issue node, as a read by id and as the list give it.
         */
        private final Map<String, String> byId = new LinkedHashMap<>();

        private final Map<String, String> listed = new LinkedHashMap<>();

        private final List<String> calls = new ArrayList<>();

        /** The calls of field values answered so far; each from [failingValues] on fails. */
        private int values;

        private int failingValues = -1;

        private @Nullable RuntimeException createFailure;

        /** Whether the reads by id fail, and how many more reads by id fail. */
        private boolean readsFail;

        private int readsFailing;

        /** Whether field values written fail the reads by id after them. */
        private boolean writesFailReads;

        /** How many reads by id fail after the next field values written. */
        private int readsFailingAfterValues;

        GitHub() {
            for (int n = 1; n <= ISSUES; n++) {
                byId.put("I_" + n, "O_idea");
                listed.put("I_" + n, "O_idea");
            }
        }

        @Override
        public GitHubGraphQl.ProjectFields projectFields(
                final String token, final String owner, final int number, final String after) {
            final List<GitHubGraphQl.ProjectField> fields = new ArrayList<>();
            fields.add(
                    new GitHubGraphQl.ProjectField(
                            "F_status",
                            FieldNames.STATUS,
                            "SINGLE_SELECT",
                            WORKFLOW.states().stream()
                                    .map(
                                            state ->
                                                    new GitHubGraphQl.FieldOption(
                                                            OPTIONS.get(state), state, "GRAY", ""))
                                    .toList(),
                            11L));
            final Map<String, List<String>> options =
                    Map.of(
                            FieldNames.WORKER, List.of("Agent", "Manual"),
                            FieldNames.SIZE, IssueMapping.SIZES,
                            FieldNames.PRIORITY, Story.PRIORITIES);
            options.forEach(
                    (name, values) ->
                            fields.add(
                                    new GitHubGraphQl.ProjectField(
                                            "F_" + name,
                                            name,
                                            "SINGLE_SELECT",
                                            values.stream()
                                                    .map(
                                                            value ->
                                                                    new GitHubGraphQl.FieldOption(
                                                                            "O_" + value,
                                                                            value,
                                                                            "GRAY",
                                                                            ""))
                                                    .toList(),
                                            12L)));
            fields.add(
                    new GitHubGraphQl.ProjectField(
                            "F_rank", FieldNames.RANK, "NUMBER", List.of(), 13L));
            return new GitHubGraphQl.ProjectFields(
                    "PVT_7", "Plan", Instant.EPOCH, fields, NONE, COST);
        }

        @Override
        public GitHubGraphQl.ProjectItems projectItems(
                final String token, final String owner, final int number, final String after) {
            calls.add("projectItems");
            final List<GitHubGraphQl.ProjectItem> items = new ArrayList<>();
            listed.forEach((issue, option) -> items.add(item(issue, option)));
            return new GitHubGraphQl.ProjectItems(items, NONE, COST, items.size());
        }

        @Override
        public GitHubGraphQl.IssueBoard issueBoard(
                final String token, final List<String> issueIds, final String projectId) {
            calls.add("issueBoard");
            if (readsFail || readsFailing > 0) {
                readsFailing--;
                throw GitHubExceptions.refused(502, "Bad gateway " + TOKEN);
            }
            final List<GitHubGraphQl.@Nullable IssueOnBoard> on = new ArrayList<>();
            for (final String id : issueIds) {
                final String option = byId.get(id);
                if (option == null) {
                    on.add(null);
                    continue;
                }
                final GitHubGraphQl.ProjectItem item = item(id, option);
                on.add(
                        new GitHubGraphQl.IssueOnBoard(
                                item.issue(), item.id(), item.values(), NONE));
            }
            return new GitHubGraphQl.IssueBoard(on, COST);
        }

        Batch<String> setFieldValues(final List<?> written) {
            calls.add("setFieldValues");
            if (failingValues >= 0 && values++ >= failingValues) {
                throw GitHubExceptions.refused(502, "Bad gateway " + TOKEN);
            }
            for (final Object value : written) {
                final GitHubGraphQl.FieldValue field = (GitHubGraphQl.FieldValue) value;
                if ("F_status".equals(field.fieldId())) {
                    byId.put(field.itemId().replace("PVTI_", "I_"), field.optionId());
                }
            }
            readsFail = writesFailReads;
            readsFailing += readsFailingAfterValues;
            readsFailingAfterValues = 0;
            return new Batch<>(
                    written.stream()
                            .map(
                                    value ->
                                            Batch.Outcome.of(
                                                    ((GitHubGraphQl.FieldValue) value).itemId()))
                            .toList(),
                    null,
                    GitHubExceptions.WITHOUT_A_WORD);
        }

        ProjectWrites.Created createIssue(
                final List<IssuesCreateRequestIssueFieldValuesInner> sent) {
            calls.add("createIssue");
            if (createFailure != null) {
                throw createFailure;
            }
            final int number = byId.size() + 1;
            byId.put("I_" + number, "O_idea");
            return new ProjectWrites.Created(
                    "I_" + number, 100L + number, number, null, OrganizationFields.answered(sent));
        }

        private static GitHubGraphQl.ProjectItem item(final String issueId, final String option) {
            final int number = Integer.parseInt(issueId.substring(2));
            final String state =
                    OPTIONS.entrySet().stream()
                            .filter(entry -> entry.getValue().equals(option))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElseThrow();
            final GitHubGraphQl.IssueRefs none = new GitHubGraphQl.IssueRefs(List.of(), NONE, 0);
            return new GitHubGraphQl.ProjectItem(
                    "PVTI_" + number,
                    false,
                    new GitHubGraphQl.BoardIssue(
                            issueId,
                            100L + number,
                            number,
                            "Issue " + number,
                            "A body",
                            "OPEN",
                            null,
                            AT,
                            OWNER + "/example-project",
                            List.of(),
                            null,
                            none,
                            none,
                            NONE,
                            null,
                            null,
                            List.of(new GitHubGraphQl.IssueFieldOption("Priority", "Medium"))),
                    List.of(new GitHubGraphQl.ItemValue("F_status", option, state, null, null)),
                    NONE);
        }
    }

    /** GitHub's writes as the writer sends them, onto the GitHub given. */
    private static ProjectWrites writes(final GitHub github) {
        return (ProjectWrites)
                Proxy.newProxyInstance(
                        ProjectWrites.class.getClassLoader(),
                        new Class<?>[] {ProjectWrites.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "issueBoard" ->
                                            github.issueBoard(
                                                    (String) args[0],
                                                    castList(args[1]),
                                                    (String) args[2]);
                                    case "setFieldValues" ->
                                            github.setFieldValues((List<?>) args[2]);
                                    case "createIssue" -> github.createIssue(castList(args[6]));
                                    case "issueFields" -> OrganizationFields.fields();
                                    case "issueFieldValues" -> List.of();
                                    case "addProjectItems" ->
                                            new Batch<>(
                                                    GitHubStoreWriterFailureTest.<String>castList(
                                                                    args[2])
                                                            .stream()
                                                            .map(
                                                                    issue ->
                                                                            Batch.Outcome.of(
                                                                                    issue.replace(
                                                                                            "I_",
                                                                                            "PVTI_")))
                                                            .toList(),
                                                    null,
                                                    GitHubExceptions.WITHOUT_A_WORD);
                                    case "issuesCreatedSince" ->
                                            new ProjectWrites.FoundPage(List.of(), false);
                                    default -> null;
                                });
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(final Object list) {
        return (List<T>) list;
    }

    private static GitHubStore store(final GitHub github) {
        final StoryStates states = issueStates -> issueStates.isEmpty() ? null : "Idea";
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        github,
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        states,
                        () -> AT,
                        ItemsBoardReader.DEADLINE,
                        length -> {});
        final Touched touched = new Touched();
        final FieldsBoardWriter writer =
                new FieldsBoardWriter(
                        touched.recording(writes(github)),
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        () -> AT,
                        duration -> {},
                        LimitFactors.DEFAULT);
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(reader, states)
                        .writer(writer)
                        // The repositories' issues unchanged, as the probe tells.
                        .probe(current -> true),
                origin -> TOKEN,
                Duration.ofSeconds(2),
                touched,
                Runnable::run);
    }

    /** A move of every issue on the board to Ready. */
    private static Writes allReady(final Board board) {
        final Map<String, String> moves = new LinkedHashMap<>();
        board.issues().forEach(issue -> moves.put(issue.id(), "Ready"));
        return new Writes(moves, Map.of());
    }

    private static long count(final GitHub github, final String call) {
        return github.calls.stream().filter(call::equals).count();
    }

    private static NewIssue newIssue() {
        return new NewIssue(
                "example-project", "New", "A body", null, Worker.AGENT, null, null, null, null);
    }

    @Test
    void aPartGitHubSavedBeforeA502StaysOnTheBoardWhileItsListLags() {
        // Harm: the moves of the first call of field values, which GitHub holds, fall back to
        // their old states at the next whole read, and the person thinks them lost.
        final GitHub github = new GitHub();
        final GitHubStore store = store(github);
        final Board board = store.refresh();
        github.failingValues = 1;

        assertThatThrownBy(() -> store.save(board.revision(), allReady(board)))
                .as("a save whose second call of field values GitHub answers 502")
                .isInstanceOf(StoreException.PartlySaved.class);
        final Board read = store.refresh();

        assertThat(read.issues())
                .as("the issues after a whole read whose list lags the save")
                .extracting(Issue::state)
                .containsExactlyElementsOf(
                        java.util.stream.Stream.concat(
                                        java.util.Collections.nCopies(
                                                GitHubGraphQl.MAX_BATCH, "Ready")
                                                .stream(),
                                        java.util.stream.Stream.of("Idea"))
                                .toList());
    }

    @Test
    void aCreateGitHubAnswers502KeepsTheBoardAndReadsNothingAgain() {
        // Harm: one 502 on the call that makes an issue drops the board, and every call after it
        // waits behind a whole read of the project.
        final GitHub github = new GitHub();
        final GitHubStore store = store(github);
        final Board board = store.refresh();
        github.createFailure = GitHubExceptions.refused(502, "Bad gateway " + TOKEN);
        github.calls.clear();

        assertThatThrownBy(() -> store.createIssue(board.revision(), newIssue()))
                .as("a create GitHub answered 502")
                .isInstanceOf(StoreException.class);

        assertThat(count(github, "createIssue")).as("the issue asked to be made").isOne();
        assertThat(store.board().revision()).as("the board held").isEqualTo(board.revision());
        assertThat(count(github, "projectItems")).as("whole reads").isZero();
    }

    @Test
    void aSaveWhoseReadAfterItsWritesFailsForgetsTheBoard() {
        // Harm: a board that lacks what the save wrote is served as current.
        final GitHub github = new GitHub();
        final GitHubStore store = store(github);
        final Board board = store.refresh();
        github.writesFailReads = true;
        final Writes one = new Writes(Map.of("example-project#1", "Ready"), Map.of());

        assertThatThrownBy(() -> store.save(board.revision(), one))
                .as("a save whose read after its writes fails")
                .isInstanceOf(StoreException.class);
        github.readsFail = false;
        github.writesFailReads = false;
        github.calls.clear();
        final Board read = store.board();

        assertThat(count(github, "projectItems")).as("whole reads").isOne();
        assertThat(read.issue("example-project#1").orElseThrow().state())
                .as("the issue moved, as GitHub's list shows it")
                .isEqualTo("Idea");
    }

    @Test
    void aCreateWhoseReadAfterItFailsKeepsTheBoardReadAgainById() {
        // Harm: a create whose first read after it fails drops the board, every call waits behind
        // a whole read, and the issue made is missing until that read ends.
        final GitHub github = new GitHub();
        final GitHubStore store = store(github);
        final Board board = store.refresh();
        // The read after the create's field values fails, sent once more too; the read again by
        // id is answered.
        github.readsFailingAfterValues = 2;
        github.calls.clear();
        final String made = "example-project#" + (ISSUES + 1);

        assertThatThrownBy(() -> store.createIssue(board.revision(), newIssue()))
                .as("a create whose read after it fails")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.getMessage())
                                        .as("the issue made, named")
                                        .contains(made));

        assertThat(count(github, "projectItems")).as("whole reads").isZero();
        assertThat(store.board().issue(made)).as("the issue made, on the board held").isPresent();
        assertThat(count(github, "projectItems")).as("whole reads for the board").isZero();
    }
}

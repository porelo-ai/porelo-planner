package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class FieldsBoardWriterTest {

    private static final String TOKEN = "github_pat_writer_test_0123456789";

    private static final String OWNER = "example-org";

    private static final Revision SHOWN = new Revision("shown");

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
                    List.of("example-project", "example-lib"),
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
        fields.put(FieldNames.ID, id);
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
                null,
                URI.create("https://github.com/" + OWNER + "/" + repository + "/issues/" + number),
                Instant.EPOCH,
                false);
    }

    private static Issue issue(final ItemView item) {
        return new Issue(
                item.fields().get(FieldNames.ID),
                item.repository(),
                item.title(),
                item.fields().get(FieldNames.STATUS),
                null,
                List.of(),
                null,
                item.link(),
                item.body());
    }

    private static Snapshot snapshot(final ItemView... items) {
        final Map<String, ItemView> byId = new LinkedHashMap<>();
        final List<Issue> issues = new ArrayList<>();
        for (final ItemView item : items) {
            byId.put(item.fields().get(FieldNames.ID), item);
            issues.add(issue(item));
        }
        return new Snapshot(
                new Board(
                        SHOWN,
                        WORKFLOW,
                        List.of(),
                        issues,
                        new Work(Map.of(), List.of()),
                        CONFIG.repositories()),
                byId,
                CONFIG);
    }

    /** An issue as GitHub's read by id gives it, with the field values the item holds. */
    private static IssueOnBoard onBoard(final ItemView item) {
        final List<ItemValue> values = new ArrayList<>();
        item.fields()
                .forEach(
                        (field, value) -> {
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
        final IssueRefs none = new IssueRefs(List.<IssueRef>of(), new PageInfo(false, null));
        return new IssueOnBoard(
                new BoardIssue(
                        item.issueId(),
                        1L,
                        item.number(),
                        item.title(),
                        item.body(),
                        item.open() ? "OPEN" : "CLOSED",
                        null,
                        item.updatedAt(),
                        OWNER + "/" + item.repository(),
                        List.of(),
                        null,
                        none,
                        none),
                item.itemId(),
                values);
    }

    /** GitHub's side of a write: what it holds, what it answers, and every call made. */
    private static final class GitHub implements ProjectWrites {
        private final Map<String, IssueOnBoard> held = new LinkedHashMap<>();
        private final Deque<Function<List<FieldValue>, Batch<String>>> batches = new ArrayDeque<>();
        private final List<List<FieldValue>> mutations = new ArrayList<>();
        private final List<String> updates = new ArrayList<>();
        private final Map<Integer, String> bodies = new LinkedHashMap<>();
        private final List<String> comments = new ArrayList<>();
        private final List<String> standing = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();

        GitHub holding(final ItemView... items) {
            for (final ItemView item : items) {
                held.put(item.issueId(), onBoard(item));
            }
            return this;
        }

        GitHub answering(final Function<List<FieldValue>, Batch<String>> answer) {
            batches.add(answer);
            return this;
        }

        FieldsBoardWriter writer() {
            return new FieldsBoardWriter(
                    this,
                    origin -> TOKEN,
                    new ForgeAccessRefusals(URI.create("https://github.com")));
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
        public Batch<String> setFieldValues(
                final String token, final String projectId, final List<FieldValue> values) {
            calls.add("setFieldValues");
            mutations.add(List.copyOf(values));
            final Function<List<FieldValue>, Batch<String>> answer = batches.poll();
            return answer != null ? answer.apply(values) : saved(values);
        }

        @Override
        public void updateIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final @Nullable String body,
                final @Nullable Opening opening) {
            calls.add("updateIssue");
            updates.add(repository + "#" + number + " " + opening);
            if (body != null) {
                bodies.put(number, body);
            }
        }

        @Override
        public CommentsPage issueComments(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final int page) {
            calls.add("issueComments");
            return new CommentsPage(page == 1 ? standing : List.of(), false);
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
            comments.add(body);
        }
    }

    private static Batch<String> saved(final List<FieldValue> values) {
        return new Batch<>(values.stream().map(v -> Batch.Outcome.of(v.itemId())).toList(), null);
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
    void aMoveOfTwoIssuesInTwoRepositoriesIsOneMutation() {
        // Harm: a move written call by call, slow and torn apart by a failure in its middle.
        final ItemView one = item("PL1", "example-project", 1, true, "", state("Idea"));
        final ItemView two = item("LB2", "example-lib", 2, true, "", state("Idea"));
        final GitHub github = new GitHub().holding(one, two);

        github.writer()
                .save(
                        snapshot(one, two),
                        SHOWN,
                        new Writes(
                                Map.of("PL1", "Backlog", "LB2", "Backlog"), Map.of(), List.of()));

        assertThat(github.mutations).as("the mutations sent").hasSize(1);
        assertThat(github.mutations.get(0))
                .as("the values of the one mutation")
                .containsExactlyInAnyOrder(
                        FieldValue.option("PVTI_PL1", "F_Status", "O_Backlog"),
                        FieldValue.option("PVTI_LB2", "F_Status", "O_Backlog"));
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
                                                new Writes(
                                                        Map.of("PL1", "Backlog"),
                                                        Map.of(),
                                                        List.of())))
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
                                                new Writes(
                                                        Map.of("PL1", "Backlog"),
                                                        Map.of(),
                                                        List.of())))
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
                                                new Writes(moves, Map.of(), List.of())))
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
        assertThat(github.mutations).as("the mutations sent").hasSize(2);
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
                                                null));

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .save(
                                                snapshot(one, two),
                                                SHOWN,
                                                new Writes(
                                                        ordered("PL1", "Backlog", "LB2", "Backlog"),
                                                        Map.of(),
                                                        List.of())))
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
                        new Writes(
                                ordered("PL1", "Rejected", "PL2", "Backlog"), Map.of(), List.of()));

        assertThat(github.updates)
                .as("the open states written")
                .containsExactly(
                        "example-project#1 Opening[open=false, notPlanned=true]",
                        "example-project#2 Opening[open=true, notPlanned=false]");
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
                                                        List.of(),
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
    void aStageWhoseCommentStandsAlreadyIsNeitherWrittenNorAddedAgain() {
        // Harm: a stage handed in twice counted twice in the issue's sums and shown twice.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.standing.add(StageComment.write(stage("task-1:2", "implement")));

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        assertThat(github.comments).as("the stage comments written").isEmpty();
        assertThat(github.mutations.get(0))
                .as("the values written")
                .extracting(FieldValue::fieldId)
                .doesNotContain("F_Minutes", "F_Stages");
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
}

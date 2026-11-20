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
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

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

    /** The id a test's item was made with, which its item id carries. */
    private static String idOf(final ItemView item) {
        return item.itemId().substring("PVTI_".length());
    }

    private static Issue issue(final ItemView item) {
        return new Issue(
                idOf(item),
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
            byId.put(idOf(item), item);
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
        private final Map<String, List<Found>> made = new LinkedHashMap<>();
        private final Deque<String> creating = new ArrayDeque<>();
        private final List<String> added = new ArrayList<>();
        private final List<String> subIssues = new ArrayList<>();

        /** The empty pages of comments before the one that holds {@code standing}. */
        private int pagesBefore;

        /** Whether every page of comments says another follows. */
        private boolean endless;

        /** What happens on GitHub while the comments are read, such as a person's edit. */
        private Runnable whileCommentsAreRead = () -> {};

        private @Nullable RuntimeException updateFailure;

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
                final List<String> labels) {
            final int number = 100 + made.values().stream().mapToInt(List::size).sum();
            final Created created =
                    new Created("I_" + repository + "_" + number, 9000L + number, number, labels);
            made.computeIfAbsent(repository, r -> new ArrayList<>())
                    // GitHub keeps a title without the spaces around it.
                    .add(new Found(created, title.strip(), body));
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
            return new FieldsBoardWriter(
                    this,
                    origin -> TOKEN,
                    new ForgeAccessRefusals(URI.create("https://github.com")),
                    () -> NOW);
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
            added.addAll(issueIds);
            return new Batch<>(
                    issueIds.stream().map(id -> Batch.Outcome.of("PVTI_" + id)).toList(), null);
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
        public GitHubGraphQl.FieldOptions setFieldOptions(
                final String token,
                final String fieldId,
                final List<GitHubGraphQl.NewOption> options) {
            throw new AssertionError("a save replaced a field's options, clearing every value");
        }

        @Override
        public Created createIssue(
                final String token,
                final String owner,
                final String repository,
                final String title,
                final String body,
                final List<String> labels) {
            calls.add("createIssue");
            assertThat(token).as("the issue made with the person's token").isEqualTo(TOKEN);
            assertThat(owner).as("the owner of the repository").isEqualTo(OWNER);
            final String answer = creating.isEmpty() ? "answer" : creating.poll();
            if (answer.equals("refused")) {
                throw GitHubExceptions.refused(422, "Validation failed for " + TOKEN);
            }
            if (answer.equals("lost")) {
                throw GitHubExceptions.unanswered("timed out");
            }
            final Created created =
                    make(
                            repository,
                            title,
                            body,
                            answer.startsWith("unlabeled") ? List.of() : labels);
            if (answer.endsWith("unanswered")) {
                throw GitHubExceptions.unanswered("timed out");
            }
            if (answer.equals("failed")) {
                throw GitHubExceptions.refused(502, "bad gateway");
            }
            return created;
        }

        @Override
        public void addSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {
            calls.add("addSubIssue");
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
            final List<Found> mine = made.getOrDefault(repository, List.of());
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
        public void updateIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final @Nullable String title,
                final @Nullable String body,
                final @Nullable Opening opening) {
            assertThat(title).as("only an edit of the text sends a title").isNull();
            calls.add("updateIssue");
            if (updateFailure != null) {
                throw updateFailure;
            }
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
            whileCommentsAreRead.run();
            if (endless || page <= pagesBefore) {
                return new CommentsPage(List.of(), true);
            }
            return new CommentsPage(page == pagesBefore + 1 ? standing : List.of(), false);
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
                .doesNotContain("F_Minutes", "F_VM_minutes", "F_Tokens", "F_Stages");
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
        return new IssueOnBoard(edit.apply(read.issue()), read.itemId(), read.values());
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
                blockedBy);
    }

    private static IssueRefs refs(final boolean more, final String... ids) {
        final List<IssueRef> nodes = new ArrayList<>();
        for (final String id : ids) {
            nodes.add(new IssueRef(id, 1L, 1, OWNER + "/example-project"));
        }
        return new IssueRefs(nodes, new PageInfo(more, null));
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
                item.comments(),
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
                                                new Writes(
                                                        Map.of("PL1", "Backlog"),
                                                        Map.of(),
                                                        List.of())))
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
                new IssueOnBoard(read.issue(), "PVTI_again", read.values()),
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
                        new Writes(
                                ordered("PL1", "Rejected", "PL2", "Finished"),
                                Map.of(),
                                List.of()));

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
                                                new Writes(
                                                        Map.of("PL1", "Backlog"),
                                                        Map.of(),
                                                        List.of())))
                .as("a first call GitHub took without answering")
                .isInstanceOf(StoreException.PartlySaved.class);
    }

    @Test
    void aStageCommentOnALaterPageIsFoundAndNeitherWrittenNorAddedAgain() {
        // Harm: a stage handed in twice on an issue with many comments counted and shown twice.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.pagesBefore = 1;
        github.standing.add(StageComment.write(stage("task-1:2", "implement")));

        github.writer().saveResult(snapshot(one), analysis(), stage("task-1:2", "implement"));

        assertThat(github.comments).as("the stage comments written").isEmpty();
        assertThat(github.mutations.get(0))
                .as("the values written")
                .extracting(FieldValue::fieldId)
                .doesNotContain("F_Minutes", "F_VM_minutes", "F_Tokens", "F_Stages");
    }

    @Test
    void anIssueWithMoreCommentsThanAreReadIsRefusedWithoutWriting() {
        // Harm: a stage whose comment stands beyond the pages read counted twice, or an issue's
        // comments read without end.
        final ItemView one = item("PL1", "example-project", 1, true, BODY, state("Analysis"));
        final GitHub github = new GitHub().holding(one);
        github.endless = true;

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .saveResult(
                                                snapshot(one),
                                                analysis(),
                                                stage("task-1:2", "implement")))
                .as("a result for an issue with more comments than are read")
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(github.calls)
                .as("the pages of comments read")
                .filteredOn("issueComments"::equals)
                .hasSize(FieldsBoardWriter.MOST_COMMENT_PAGES);
        assertThat(github.mutations).as("the mutations sent").isEmpty();
        assertThat(github.updates).as("the issues updated").isEmpty();
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
                .as("the calls made")
                .containsExactly("createIssue", "addProjectItems", "setFieldValues");
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
                        FieldValue.option("PVTI_I_example-lib_100", "F_Priority", "O_Medium"),
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
        assertThat(story.issue().labels()).as("the story's labels").containsExactly("story");
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
        assertThat(github.mutations.get(0))
                .as("the story follows its issues' state; each issue starts in Idea")
                .containsExactly(
                        FieldValue.option("PVTI_I_example-project_100", "F_Priority", "O_High"),
                        FieldValue.number("PVTI_I_example-project_100", "F_Rank", 1),
                        FieldValue.option("PVTI_I_example-lib_101", "F_Status", "O_Idea"),
                        FieldValue.option("PVTI_I_example-lib_101", "F_Size", "O_S"),
                        FieldValue.option("PVTI_I_example-lib_101", "F_Worker", "O_Agent"),
                        FieldValue.option("PVTI_I_example-lib_101", "F_Priority", "O_High"),
                        FieldValue.number("PVTI_I_example-lib_101", "F_Rank", 1),
                        FieldValue.option("PVTI_I_example-project_102", "F_Status", "O_Idea"),
                        FieldValue.option("PVTI_I_example-project_102", "F_Size", "O_S"),
                        FieldValue.option("PVTI_I_example-project_102", "F_Worker", "O_Agent"),
                        FieldValue.option("PVTI_I_example-project_102", "F_Priority", "O_High"),
                        FieldValue.number("PVTI_I_example-project_102", "F_Rank", 2));
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
        github.make("example-lib", "A new thing", "**What must be true.** Another.\n", List.of());

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
                        "createIssue",
                        "issuesCreatedSince",
                        "createIssue",
                        "addProjectItems",
                        "setFieldValues");
    }

    @Test
    void twoIssuesFoundAfterAnUnansweredCallStopTheWriteNamingBoth() {
        // Harm: one of two issues alike taken at a guess, the other left beside it unnamed.
        final GitHub github = new GitHub().creating("unanswered");
        github.make("example-lib", "A new thing", WRITTEN, List.of());
        github.make("example-lib", "Something else", WRITTEN, List.of());
        github.make("example-lib", "Something more", WRITTEN, List.of());

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
                                    .contains("example-lib#100, example-lib#103");
                        });
        assertThat(github.added).as("nothing added to the project").isEmpty();
    }

    @Test
    void aStoryWhoseLabelGitHubDroppedStopsTheWriteNamingIt() {
        // Harm: issues made under a story GitHub holds as a plain issue, which the board shows
        // as no story.
        final GitHub github = new GitHub().creating("unlabeled");

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIdea(
                                                snapshot(),
                                                SHOWN,
                                                idea(newIssue("example-lib", "First"))))
                .as("an idea whose story GitHub made without its label")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.getMessage())
                                        .as("the story named")
                                        .contains("example-project#100"));
        assertThat(github.calls).as("the calls made").containsExactly("createIssue");
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
        // Harm: the person's earlier issue of the same words taken as the new one, so the new
        // one GitHub made stands beside it off the project.
        final GitHub github = new GitHub().creating("unanswered");
        github.make("example-lib", "A new thing", WRITTEN, List.of());
        final ItemView earlier = item("LB9", "example-lib", 100, true, WRITTEN, state("Idea"));

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

    @Test
    void aStoryFoundAfterAnUnansweredCallWithoutItsLabelStopsTheWrite() {
        // Harm: issues made under a story found again that GitHub holds as a plain issue.
        final GitHub github = new GitHub().creating("unlabeled-unanswered");

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .createIdea(
                                                snapshot(),
                                                SHOWN,
                                                idea(newIssue("example-lib", "First"))))
                .as("an idea whose story was found again without its label")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.getMessage())
                                        .as("the story named")
                                        .contains("example-project#100"));
        assertThat(github.calls)
                .as("the calls made")
                .containsExactly("createIssue", "issuesCreatedSince");
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
        assertThat(github.calls).as("the calls made").containsExactly("createIssue");
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
}

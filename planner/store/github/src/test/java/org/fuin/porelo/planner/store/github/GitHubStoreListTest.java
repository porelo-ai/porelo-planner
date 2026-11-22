package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The GitHub store's add of an issue made outside the project, over a fake GitHub that holds the
 * project's items and the repositories' open issues: the board read after the add, and the add
 * refused once a look moved the board.
 */
class GitHubStoreListTest {

    private static final String TOKEN = "invented-token-list-0123456789";

    private static final String OWNER = "example-org";

    private static final String OWN = "example-project";

    private static final String LIB = "example-lib";

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef(OWNER, 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    List.of(OWN, LIB),
                    Map.of(),
                    Map.of());

    private static final GraphQlCost COST = new GraphQlCost(1, 4999, Instant.EPOCH);

    private static final GitHubGraphQl.PageInfo LAST = new GitHubGraphQl.PageInfo(false, null);

    private static final String MADE = OpenIssues.nodeOf(LIB, 6);

    private final OpenIssues pages = new OpenIssues();

    /** The project's items: a story of the own repository and an issue of the other. */
    private final List<GitHubGraphQl.ProjectItem> items =
            new ArrayList<>(
                    List.of(
                            item("PVTI_1", issue(OWN, 1, "A story", IssueMapping.STORY_TYPE)),
                            item("PVTI_2", issue(LIB, 2, "On the board", null))));

    /** The writes the store made, by name. */
    private final List<String> calls = new ArrayList<>();

    private final ProjectQueries queries =
            new ProjectQueries() {
                @Override
                public GitHubGraphQl.ProjectFields projectFields(
                        final String token,
                        final String owner,
                        final int number,
                        final @Nullable String after) {
                    return new GitHubGraphQl.ProjectFields(
                            "PVT_7", "Plan", Instant.EPOCH, fields(), LAST, COST);
                }

                @Override
                public GitHubGraphQl.ProjectItems projectItems(
                        final String token,
                        final String owner,
                        final int number,
                        final @Nullable String after) {
                    return new GitHubGraphQl.ProjectItems(
                            List.copyOf(items), LAST, COST, items.size());
                }

                @Override
                public GitHubGraphQl.IssueBoard issueBoard(
                        final String token, final List<String> issueIds, final String projectId) {
                    return read(issueIds);
                }
            };

    private GitHubGraphQl.IssueBoard read(final List<String> issueIds) {
        final List<GitHubGraphQl.@Nullable IssueOnBoard> on = new ArrayList<>();
        for (final String id : issueIds) {
            final GitHubGraphQl.ProjectItem item =
                    items.stream()
                            .filter(i -> i.issue() != null && i.issue().id().equals(id))
                            .findFirst()
                            .orElse(null);
            if (item != null) {
                on.add(
                        new GitHubGraphQl.IssueOnBoard(
                                item.issue(), item.id(), item.values(), LAST));
            } else if (MADE.equals(id)) {
                on.add(
                        new GitHubGraphQl.IssueOnBoard(
                                issue(LIB, 6, "Made by hand", null), null, List.of(), LAST));
            } else {
                on.add(null);
            }
        }
        return new GitHubGraphQl.IssueBoard(on, COST);
    }

    /** GitHub's writes: an add puts the issue on the project, a value lands on its item. */
    @SuppressWarnings("unchecked")
    private ProjectWrites writes() {
        return (ProjectWrites)
                Proxy.newProxyInstance(
                        ProjectWrites.class.getClassLoader(),
                        new Class<?>[] {ProjectWrites.class},
                        (proxy, method, args) -> {
                            calls.add(method.getName());
                            return switch (method.getName()) {
                                case "issueBoard" -> read((List<String>) args[1]);
                                case "addProjectItems" -> {
                                    // An added item holds no value until the planner writes one.
                                    items.add(
                                            new GitHubGraphQl.ProjectItem(
                                                    "PVTI_6",
                                                    false,
                                                    issue(LIB, 6, "Made by hand", null),
                                                    List.of(),
                                                    LAST));
                                    yield new Batch<>(List.of(Batch.Outcome.of("PVTI_6")), null);
                                }
                                case "setFieldValues" -> {
                                    final List<GitHubGraphQl.FieldValue> values =
                                            (List<GitHubGraphQl.FieldValue>) args[2];
                                    values.forEach(this::land);
                                    yield new Batch<>(
                                            values.stream()
                                                    .map(v -> Batch.Outcome.of(v.itemId()))
                                                    .toList(),
                                            null);
                                }
                                default ->
                                        throw new AssertionError(
                                                "the add called " + method.getName());
                            };
                        });
    }

    /** Writes one value onto its item, as GitHub holds it afterwards. */
    private void land(final GitHubGraphQl.FieldValue value) {
        for (int at = 0; at < items.size(); at++) {
            final GitHubGraphQl.ProjectItem item = items.get(at);
            if (item.id().equals(value.itemId())) {
                final List<GitHubGraphQl.ItemValue> kept = new ArrayList<>(item.values());
                kept.removeIf(v -> v.fieldId().equals(value.fieldId()));
                final String option = value.optionId();
                kept.add(
                        new GitHubGraphQl.ItemValue(
                                value.fieldId(),
                                option,
                                option == null ? null : option.substring("O_".length()),
                                value.text(),
                                value.number()));
                items.set(
                        at,
                        new GitHubGraphQl.ProjectItem(
                                item.id(), item.archived(), item.issue(), kept, LAST));
            }
        }
    }

    private static GitHubGraphQl.BoardIssue issue(
            final String repository,
            final int number,
            final String title,
            final @Nullable String type) {
        final GitHubGraphQl.IssueRefs none = new GitHubGraphQl.IssueRefs(List.of(), LAST);
        return new GitHubGraphQl.BoardIssue(
                OpenIssues.nodeOf(repository, number),
                900L + number,
                number,
                title,
                "A body",
                "OPEN",
                null,
                Instant.parse("2026-11-20T10:00:00Z"),
                OWNER + "/" + repository,
                List.of(),
                null,
                none,
                none,
                LAST,
                type);
    }

    private static GitHubGraphQl.ProjectItem item(
            final String id, final GitHubGraphQl.BoardIssue issue) {
        final List<GitHubGraphQl.ItemValue> values = new ArrayList<>();
        values.add(
                new GitHubGraphQl.ItemValue(
                        "F_" + FieldNames.PRIORITY, "O_High", "High", null, null));
        if (!IssueMapping.STORY_TYPE.equals(issue.issueType())) {
            values.add(
                    new GitHubGraphQl.ItemValue(
                            "F_" + FieldNames.STATUS, "O_Idea", "Idea", null, null));
        }
        return new GitHubGraphQl.ProjectItem(id, false, issue, values, LAST);
    }

    /** Every field the store uses, of its kind and with its options. */
    private static List<GitHubGraphQl.ProjectField> fields() {
        final Map<String, List<String>> options =
                Map.of(
                        FieldNames.STATUS, WORKFLOW.states(),
                        FieldNames.PRIORITY, org.fuin.porelo.planner.store.Story.PRIORITIES,
                        FieldNames.SIZE, IssueMapping.SIZES,
                        FieldNames.WORKER, List.of("Agent", "Manual"),
                        FieldNames.RISK, List.of("harm", "ordinary", "small"));
        final List<GitHubGraphQl.ProjectField> fields = new ArrayList<>();
        FieldNames.ALL.forEach(
                (name, kind) ->
                        fields.add(
                                new GitHubGraphQl.ProjectField(
                                        "F_" + name,
                                        name,
                                        kind.name(),
                                        options.getOrDefault(name, List.of()).stream()
                                                .map(
                                                        option ->
                                                                new GitHubGraphQl.FieldOption(
                                                                        "O_" + option,
                                                                        option,
                                                                        "GRAY",
                                                                        ""))
                                                .toList(),
                                        1L)));
        return fields;
    }

    private GitHubStore store() {
        pages.holds(LIB, List.of(OpenIssues.issue(LIB, 6, "Made by hand", null)));
        final Touched touched = new Touched();
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        queries,
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        issueStates -> issueStates.isEmpty() ? null : "Idea",
                        pages.clock(),
                        ItemsBoardReader.DEADLINE,
                        length -> {},
                        pages);
        final FieldsBoardWriter writer =
                new FieldsBoardWriter(
                        touched.recording(writes()),
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        () -> Instant.EPOCH,
                        duration -> {});
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(reader, states -> null).writer(writer),
                origin -> TOKEN,
                Duration.ofSeconds(5),
                touched);
    }

    private static List<String> offered(final Board board) {
        return board.unlisted().stream().map(UnlistedIssue::id).toList();
    }

    @Test
    void theAddedIssueStandsOnTheBoardInTheFirstStateAndIsOfferedNoLonger() {
        // Harm: the add was answered as saved, yet the board read after it still offered the
        // issue, so the person added it twice, or showed it in no list at all.
        final GitHubStore store = store();
        final Board before = store.board();
        assertThat(offered(before)).as("offered before").containsExactly(LIB + "#6");

        final Board after = store.listIssue(before.revision(), LIB + "#6");

        assertThat(after.issue(LIB + "#6"))
                .as("the added issue on the board")
                .get()
                .extracting(Issue::state, Issue::worker)
                .containsExactly("Idea", Worker.MANUAL);
        assertThat(offered(after)).as("offered after").isEmpty();
        assertThat(calls)
                .as("the writes")
                .containsExactly("issueBoard", "addProjectItems", "setFieldValues");
    }

    @Test
    void anAddAgainstABoardALookMovedIsRefusedBeforeAnyCall() {
        // Harm: a look between the caller's check and the save moved the board, and the add went
        // ahead on it, writing over what a person changed on GitHub since.
        final GitHubStore store = store();
        final Board before = store.board();
        items.set(1, item("PVTI_2", issue(LIB, 2, "Retitled on GitHub", null)));
        store.refresh();

        assertThatThrownBy(() -> store.listIssue(before.revision(), LIB + "#6"))
                .as("an add against the board before the look")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(calls).as("no call").isEmpty();
    }
}

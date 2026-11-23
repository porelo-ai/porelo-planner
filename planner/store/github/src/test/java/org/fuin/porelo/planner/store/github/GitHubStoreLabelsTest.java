package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.forge.github.Page;
import org.fuin.porelo.forge.github.model.Label;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.RepositoryWarning;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TopicLabels;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The GitHub store's check of each repository's labels against the topic labels, over a fake GitHub
 * whose own repository holds GitHub's eight defaults, one topic label with another color and one
 * label of the project's own: the warning, the correction's calls, a second run, and when the
 * labels are read.
 */
class GitHubStoreLabelsTest {

    private static final String TOKEN = "invented-token-labels-0123456789";

    private static final String OWNER = "example-org";

    private static final String OWN = "example-project";

    private static final String LIB = "example-lib";

    private static final URI FORGE = URI.create("https://github.com");

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    FORGE,
                    new ProjectRef(OWNER, 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    org.fuin.porelo.planner.store.ProjectRepository.named(List.of(OWN, LIB)),
                    Map.of(),
                    Map.of());

    private static final GraphQlCost COST = new GraphQlCost(1, 4999, Instant.EPOCH);

    private static final GitHubGraphQl.PageInfo LAST = new GitHubGraphQl.PageInfo(false, null);

    /** The labels a fake page carries, fewer than a repository holds, so a read must page. */
    private static final int FAKE_PAGE = 4;

    /** Each repository's labels as the fake GitHub holds them, by name. */
    private final Map<String, Map<String, Label>> held = new LinkedHashMap<>();

    /** The pages of labels read, as repository and page. */
    private final List<String> reads = new ArrayList<>();

    /** The writes made, as action, repository and label. */
    private final List<String> writes = new ArrayList<>();

    /** The tokens each forge call was given. */
    private final List<String> tokensGiven = new ArrayList<>();

    /** The origins a token was asked for. */
    private final List<URI> origins = new ArrayList<>();

    /** A failure the next write meets, by label name. */
    private final Map<String, GitHubException> failing = new LinkedHashMap<>();

    /** A failure the next read of a repository meets, by repository. */
    private final Map<String, RuntimeException> unreadable = new LinkedHashMap<>();

    GitHubStoreLabelsTest() {
        final Map<String, Label> own = new LinkedHashMap<>();
        TopicLabels.DEFAULTS.forEach(name -> own.put(name, label(name, "ededed", "GitHub's")));
        own.put("tests", label("tests", "ff0000", "Tests and what runs them"));
        own.put("customer", label("customer", "123456", "Asked for by a customer"));
        held.put(OWN, own);
        final Map<String, Label> lib = new LinkedHashMap<>();
        TopicLabels.ALL.forEach(
                topic ->
                        lib.put(
                                topic.name(),
                                label(
                                        topic.name(),
                                        topic.color().toUpperCase(),
                                        topic.description())));
        held.put(LIB, lib);
    }

    private static Label label(final String name, final String color, final String description) {
        return new Label(
                1L,
                "LA_" + name,
                URI.create("https://example.invalid/l"),
                name,
                description,
                color,
                false,
                null,
                null);
    }

    private final RepositoryLabels.Forge forge =
            new RepositoryLabels.Forge() {
                @Override
                public Page<Label> labels(
                        final String token,
                        final String owner,
                        final String repository,
                        final int page) {
                    tokensGiven.add(token);
                    reads.add(repository + "/" + page);
                    final RuntimeException fails = unreadable.get(repository);
                    if (fails != null) {
                        throw fails;
                    }
                    final List<Label> all = List.copyOf(held.get(repository).values());
                    final int from = Math.min(all.size(), (page - 1) * FAKE_PAGE);
                    final int to = Math.min(all.size(), from + FAKE_PAGE);
                    return new Page<>(all.subList(from, to), to < all.size());
                }

                @Override
                public void delete(
                        final String token,
                        final String owner,
                        final String repository,
                        final String name) {
                    write(token, "delete", repository, name);
                    held.get(repository).remove(name);
                }

                @Override
                public void create(
                        final String token,
                        final String owner,
                        final String repository,
                        final String name,
                        final String color,
                        final String description) {
                    write(token, "create", repository, name);
                    held.get(repository).put(name, label(name, color, description));
                }

                @Override
                public void update(
                        final String token,
                        final String owner,
                        final String repository,
                        final String name,
                        final String color,
                        final String description) {
                    write(token, "update", repository, name);
                    held.get(repository).put(name, label(name, color, description));
                }
            };

    private void write(
            final String token, final String action, final String repository, final String name) {
        tokensGiven.add(token);
        final GitHubException fails = failing.remove(name);
        if (fails != null) {
            throw fails;
        }
        writes.add(action + " " + repository + " " + name);
    }

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
                    return new GitHubGraphQl.ProjectItems(List.of(), LAST, COST, 0);
                }

                @Override
                public GitHubGraphQl.IssueBoard issueBoard(
                        final String token, final List<String> issueIds, final String projectId) {
                    return new GitHubGraphQl.IssueBoard(List.of(), COST);
                }
            };

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

    private GitHubStore store(final RepositoryLabels labels) {
        final OpenIssues pages = new OpenIssues();
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
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(reader, states -> null).labels(labels),
                origin -> {
                    origins.add(origin);
                    return TOKEN;
                },
                Duration.ofSeconds(5),
                new Touched());
    }

    private GitHubStore store() {
        return store(new RepositoryLabels(forge));
    }

    private static RepositoryWarning warningOf(final Board board, final String repository) {
        return board.repositoryWarning(repository, RepositoryWarning.TOPIC_LABELS).orElseThrow();
    }

    @Test
    void theWarningNamesTheDefaultsTheMissingAndTheDifferingButNeverTheOwnLabel() {
        // Harm: a repository with GitHub's defaults and a topic label of another color showed no
        // warning, or one that offered to delete the project's own label.
        final Board board = store().board();

        final RepositoryWarning warning = warningOf(board, OWN);
        assertThat(warning.defaults()).as("defaults").isEqualTo(TopicLabels.DEFAULTS);
        assertThat(warning.missing())
                .as("missing")
                .containsExactly(
                        "documentation",
                        "security",
                        "build",
                        "dependencies",
                        "performance",
                        "usability");
        assertThat(warning.differing()).as("differing").containsExactly("tests");
        assertThat(warning.correction()).as("the correction offered").isNotNull();
        assertThat(warning.calls())
                .as("no call names the own label")
                .extracting(RepositoryWarning.Call::label)
                .doesNotContain("customer");
        assertThat(board.repositoryWarning(LIB, RepositoryWarning.TOPIC_LABELS))
                .as("a repository whose labels are right, its color in upper case")
                .isEmpty();
        assertThat(reads)
                .as("each repository read to its last page")
                .containsExactly(OWN + "/1", OWN + "/2", OWN + "/3", LIB + "/1", LIB + "/2");
    }

    @Test
    void theCorrectionDeletesCreatesAndUpdatesInOrderKeepsTheOwnLabelAndASecondRunWritesNothing() {
        // Harm: the correction left a default or the differing color behind, deleted the
        // project's own label, or a second run wrote the same labels again.
        final GitHubStore store = store();
        final List<RepositoryWarning.Call> calls = warningOf(store.board(), OWN).calls();

        final Board after = store.correctLabels(OWN, calls);

        final List<String> expected = new ArrayList<>();
        TopicLabels.DEFAULTS.forEach(name -> expected.add("delete " + OWN + " " + name));
        List.of("documentation", "security", "build", "dependencies", "performance", "usability")
                .forEach(name -> expected.add("create " + OWN + " " + name));
        expected.add("update " + OWN + " tests");
        assertThat(writes).as("the calls, in the preview's order").isEqualTo(expected);
        assertThat(held.get(OWN)).as("the own label kept").containsKey("customer");
        assertThat(held.get(OWN).get("tests").color()).as("the color set").isEqualTo("fbca04");
        assertThat(after.repositoryWarnings()).as("no warning after the correction").isEmpty();

        writes.clear();
        final Board again = store.refresh();
        assertThat(again.repositoryWarnings()).as("no warning at the next refresh").isEmpty();
        assertThat(writes).as("no write by the read").isEmpty();
    }

    @Test
    void aPlainReadKeepsTheLabelsReadAndOnlyARefreshReadsThemAgain() {
        // Harm: every read of the board asked GitHub for each repository's labels, spending the
        // person's rate limit on what changes only by hand; or a refresh never saw a change.
        final GitHubStore store = store();
        store.board();
        final int first = reads.size();
        held.get(LIB).put("bug", label("bug", "d73a4a", "Something isn't working"));

        assertThat(store.board().repositoryWarning(LIB, RepositoryWarning.TOPIC_LABELS))
                .as("a plain read: the labels as kept")
                .isEmpty();
        assertThat(reads).as("no label read by a plain read").hasSize(first);

        final Board refreshed = store.refresh();
        assertThat(warningOf(refreshed, LIB).defaults())
                .as("after a refresh")
                .containsExactly("bug");
        assertThat(reads).as("read again by the refresh").hasSizeGreaterThan(first);
    }

    @Test
    void oneRepositorysLabelsReadAnewShowNoCallForALabelGoneSinceTheLastRead() {
        // Harm: a correction computed from labels a colleague changed since they were read
        // calls on a label already deleted, or reads every repository for one.
        final GitHubStore store = store();
        store.board();
        held.get(OWN).remove("wontfix");
        reads.clear();

        final Board board = store.rereadLabels(OWN);

        assertThat(warningOf(board, OWN).calls())
                .as("no call names the label gone")
                .extracting(call -> call.action().spelled() + " " + call.label())
                .contains("Delete bug")
                .doesNotContain("Delete wontfix");
        assertThat(reads)
                .as("the one repository read to its last page")
                .containsExactly(OWN + "/1", OWN + "/2", OWN + "/3");

        reads.clear();
        assertThat(store.rereadLabels("elsewhere").repositoryWarnings())
                .as("a repository not the project's")
                .hasSize(1);
        assertThat(reads).as("nothing read for it").isEmpty();
    }

    @Test
    void oneRepositorysLabelsThatCannotBeReadAnewAreRefusedByStatusAndNoLongerWarnedOf() {
        // Harm: a correction computed from labels kept from before, though GitHub could not say
        // what they are now, or GitHub's words reaching the window.
        final GitHubStore store = store();
        store.board();
        unreadable.put(OWN, GitHubExceptions.refused(404, "Not Found: secret words"));

        assertThatThrownBy(() -> store.rereadLabels(OWN))
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessage(
                        "The labels of repository "
                                + OWN
                                + " could not be read: GitHub answered status 404");
        assertThat(store.board().repositoryWarning(OWN, RepositoryWarning.TOPIC_LABELS))
                .as("no warning from the labels read before")
                .isEmpty();
    }

    @Test
    void aRefusedCallIsNamedByItsStatusAloneAndTheCallsBeforeItStayMade() {
        // Harm: GitHub's own words reached the window, or the calls after a refusal went on.
        final GitHubStore store = store();
        final List<RepositoryWarning.Call> calls = warningOf(store.board(), OWN).calls();
        failing.put("security", GitHubExceptions.refused(422, "Validation Failed: secret words"));

        assertThatThrownBy(() -> store.correctLabels(OWN, calls))
                .as("a call GitHub refused")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("GitHub refused the call with status 422")
                .hasMessageNotContaining("secret words");
        assertThat(writes)
                .as("the calls before the refusal, none after")
                .hasSize(TopicLabels.DEFAULTS.size() + 1)
                .last()
                .isEqualTo("create " + OWN + " documentation");
        assertThat(warningOf(store.board(), OWN).defaults())
                .as("the defaults deleted are no longer warned of")
                .isEmpty();
    }

    @Test
    void aRepositoryWhoseLabelsCannotBeReadIsAFaultNamingTheStatusAlone() {
        // Harm: a repository whose labels GitHub refused showed GitHub's words, or a warning
        // built from nothing.
        unreadable.put(LIB, GitHubExceptions.refused(404, "Not Found: secret words"));

        final Board board = store().board();

        assertThat(board.faults())
                .as("the fault")
                .contains(
                        "The labels of repository "
                                + LIB
                                + " could not be read: GitHub answered status 404")
                .noneMatch(fault -> fault.contains("secret words"));
        assertThat(board.repositoryWarning(LIB, RepositoryWarning.TOPIC_LABELS))
                .as("no warning for it")
                .isEmpty();
        assertThat(board.repositoryWarning(OWN, RepositoryWarning.TOPIC_LABELS))
                .as("the other read")
                .isPresent();
    }

    @Test
    void aListLongerThanThePlannerReadsIsAFault() {
        // Harm: a repository answering more pages without end held the read forever.
        final Map<String, Label> many = held.get(LIB);
        for (int i = 0; i < RepositoryLabels.MOST_PAGES * FAKE_PAGE; i++) {
            many.put("own-" + i, label("own-" + i, "123456", ""));
        }

        final Board board = store().board();

        assertThat(board.faults())
                .as("the fault")
                .contains(
                        "The labels of repository "
                                + LIB
                                + " could not be read: it has more than 3000 labels");
        assertThat(reads.stream().filter(read -> read.startsWith(LIB + "/")))
                .as("pages read")
                .hasSize(RepositoryLabels.MOST_PAGES);
    }

    @Test
    void theTokenIsAskedForGitHubAloneAndGoesOnlyToItsCalls() {
        // Harm: the person's token was asked for another origin, or sent to a call not GitHub's.
        final GitHubStore store = store();
        store.correctLabels(OWN, warningOf(store.board(), OWN).calls());

        assertThat(origins).as("the origins asked").isNotEmpty().containsOnly(FORGE);
        assertThat(tokensGiven).as("the tokens given").containsOnly(TOKEN);
    }

    @Test
    void aStoreWithoutLabelsWarnsOfNoneAndOffersNoCorrection() {
        // Harm: a store that reads no labels showed a warning, or made a call it was never set up
        // to make.
        final GitHubStore store = store(RepositoryLabels.NONE);

        assertThat(store.keepsRepositoryLabels()).as("kept").isFalse();
        assertThat(store.board().repositoryWarnings()).as("warnings").isEmpty();
        assertThatThrownBy(() -> store.correctLabels(OWN, List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(reads).as("no label read").isEmpty();
    }
}

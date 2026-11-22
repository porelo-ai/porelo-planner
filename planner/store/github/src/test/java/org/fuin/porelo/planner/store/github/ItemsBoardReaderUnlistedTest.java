package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.TrackedUnlisted;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ItemsBoardReaderUnlistedTest {

    private static final String TOKEN = "invented-token-unlisted-0123456789";

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

    private final OpenIssues pages = new OpenIssues();

    /** The project's items: a story of the own repository and an archived issue of the other. */
    private final List<GitHubGraphQl.ProjectItem> items =
            new ArrayList<>(
                    List.of(
                            item(OWN, 1, false, IssueMapping.STORY_TYPE),
                            item(LIB, 2, true, null)));

    /** How many pages of open issues had been asked for when the items were first read. */
    private @Nullable Integer asksBeforeItems;

    /** GitHub's project: its one field and the items above, never a write. */
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
                    if (asksBeforeItems == null) {
                        asksBeforeItems = pages.asks.size();
                    }
                    return new GitHubGraphQl.ProjectItems(
                            List.copyOf(items), LAST, COST, items.size());
                }

                @Override
                public GitHubGraphQl.IssueBoard issueBoard(
                        final String token, final List<String> issueIds, final String projectId) {
                    final List<GitHubGraphQl.@Nullable IssueOnBoard> on = new ArrayList<>();
                    for (final String id : issueIds) {
                        on.add(
                                items.stream()
                                        .filter(
                                                item ->
                                                        item.issue() != null
                                                                && item.issue().id().equals(id))
                                        .findFirst()
                                        .map(
                                                item ->
                                                        new GitHubGraphQl.IssueOnBoard(
                                                                item.issue(),
                                                                item.id(),
                                                                item.values(),
                                                                LAST))
                                        .orElse(null));
                    }
                    return new IssueBoardAnswer(on).board();
                }
            };

    /** Builds GitHub's answer to a read of issues by their ids. */
    private record IssueBoardAnswer(List<GitHubGraphQl.@Nullable IssueOnBoard> on) {
        GitHubGraphQl.IssueBoard board() {
            return new GitHubGraphQl.IssueBoard(on, COST);
        }
    }

    /** Every field the store uses, of its kind and with its options, so none is a fault. */
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

    private static GitHubGraphQl.ProjectItem item(
            final String repository,
            final int number,
            final boolean archived,
            final @Nullable String type) {
        final GitHubGraphQl.IssueRefs none = new GitHubGraphQl.IssueRefs(List.of(), LAST);
        return new GitHubGraphQl.ProjectItem(
                "PVTI_" + repository + "_" + number,
                archived,
                new GitHubGraphQl.BoardIssue(
                        OpenIssues.nodeOf(repository, number),
                        (long) number,
                        number,
                        "Item " + number,
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
                        type),
                List.of(
                        new GitHubGraphQl.ItemValue(
                                "F_" + FieldNames.STATUS, "O_Idea", "Idea", null, null),
                        new GitHubGraphQl.ItemValue(
                                "F_" + FieldNames.PRIORITY, "O_High", "High", null, null)),
                LAST);
    }

    private ItemsBoardReader reader() {
        return reader(AccessRefusals.DEFAULT);
    }

    private ItemsBoardReader reader(final AccessRefusals refusals) {
        return new ItemsBoardReader(
                queries,
                origin -> TOKEN,
                refusals,
                issueStates -> issueStates.isEmpty() ? null : "Idea",
                pages.clock(),
                ItemsBoardReader.DEADLINE,
                length -> {},
                pages);
    }

    private static List<String> ids(final Board board) {
        return board.unlisted().stream().map(UnlistedIssue::id).toList();
    }

    private static Issue open(final String repository, final int number) {
        return OpenIssues.issue(repository, number, "Made by hand " + number, null);
    }

    @Test
    void anOpenIssueNoItemHoldsIsListedAndTheProjectsItemsAreNot() {
        // Harm: a hand-made issue is never offered, or an issue already on the project, archived
        // or not, or a pull request is offered as if it were not.
        pages.holds(OWN, List.of(open(OWN, 5), open(OWN, 1)));
        pages.holds(LIB, List.of(open(LIB, 6), open(LIB, 2), OpenIssues.pullRequest(LIB, 7)));

        final Board board = reader().read(CONFIG).board();

        assertThat(ids(board))
                .as("the issues outside the project")
                .containsExactly(OWN + "#5", LIB + "#6");
        assertThat(board.faults()).as("the board's faults").isEmpty();
    }

    @Test
    void everyRepositoryOfTheProjectIsListed() {
        // Harm: the issues made by hand in any repository but the own are never offered.
        pages.holds(OWN, List.of(open(OWN, 5)));
        pages.holds(LIB, List.of(open(LIB, 6)));

        reader().read(CONFIG);

        assertThat(pages.asks.stream().map(OpenIssues.Ask::repository).distinct().toList())
                .as("the repositories listed")
                .containsExactly(OWN, LIB);
    }

    @Test
    void theOpenIssuesAreListedBeforeTheProjectsItems() {
        // Harm: an issue added to the project between the two reads is shown as outside it.
        pages.holds(OWN, List.of(open(OWN, 5)));

        reader().read(CONFIG);

        assertThat(asksBeforeItems)
                .as("the pages asked for before the items were read")
                .isEqualTo(2);
    }

    @Test
    void onlyTheTypeStoryInTheOwnRepositoryMarksAStoryAndOneElsewhereIsNotOffered() {
        // Harm: a story offered as an issue, or an issue as a story, against the issue type.
        pages.holds(
                OWN,
                List.of(
                        OpenIssues.issue(OWN, 11, "Typed", "Story"),
                        OpenIssues.issue(OWN, 12, "Lowercase", "story"),
                        OpenIssues.issue(OWN, 13, "Another type", "Bug"),
                        OpenIssues.issue(OWN, 14, "No type", null),
                        OpenIssues.issue(OWN, 15, "Labeled", null, false, List.of("story"))));
        pages.holds(LIB, List.of(OpenIssues.issue(LIB, 16, "Typed elsewhere", "Story")));

        final Board board = reader().read(CONFIG).board();

        assertThat(board.unlisted())
                .as("the kinds")
                .extracting(UnlistedIssue::id, UnlistedIssue::kind)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(OWN + "#11", UnlistedIssue.Kind.STORY),
                        org.assertj.core.groups.Tuple.tuple(OWN + "#12", UnlistedIssue.Kind.ISSUE),
                        org.assertj.core.groups.Tuple.tuple(OWN + "#13", UnlistedIssue.Kind.ISSUE),
                        org.assertj.core.groups.Tuple.tuple(OWN + "#14", UnlistedIssue.Kind.ISSUE),
                        org.assertj.core.groups.Tuple.tuple(OWN + "#15", UnlistedIssue.Kind.ISSUE));
        // Harm: a story of another repository was offered as an issue whose Add is always refused,
        // while the board leaves such a story out with a fault.
        assertThat(board.faults())
                .as("the story of another repository, left out")
                .contains(
                        "The open issue "
                                + LIB
                                + "#16 outside the project is a story outside the project's own"
                                + " repository and is left out");
    }

    @Test
    void theLinkIsBuiltFromTheReferenceNeverFromGitHubsAddress() {
        // Harm: a link that leads elsewhere than the issue its title names.
        pages.holds(LIB, List.of(open(LIB, 6)));

        final Board board = reader().read(CONFIG).board();

        assertThat(board.unlisted())
                .as("the issue's link")
                .singleElement()
                .extracting(UnlistedIssue::link)
                .isEqualTo(URI.create("https://github.com/example-org/example-lib/issues/6"));
    }

    @Test
    void anIssueBearingTheReferenceOfAnotherIsAFaultAndTheBoardIsServed() {
        // Harm: one odd entry of a repository's list fails the whole board, or one id names two
        // issues.
        pages.holds(
                OWN,
                List.of(
                        OpenIssues.issue(
                                "I_other", OWN, 1, "Secret plan", null, false, List.of())));
        pages.holds(
                LIB,
                List.of(
                        OpenIssues.issue("I_first", LIB, 8, "Secret plan", null, false, List.of()),
                        OpenIssues.issue(
                                "I_second", LIB, 8, "Secret plan", null, false, List.of())));

        final Board board = reader().read(CONFIG).board();

        assertThat(ids(board)).as("the issues outside the project").containsExactly(LIB + "#8");
        assertThat(board.faults())
                .as("a fault for the issue on the board and one for the second entry")
                .hasSize(2)
                .noneMatch(fault -> fault.contains("Secret plan"));
        assertThat(board.story(OWN + "#1")).as("the story on the board").isPresent();
    }

    @Test
    void aCopysIssueOutsideTheProjectsRepositoriesIsAFaultAndTheBoardIsServed() {
        // Harm: a copy that names an odd repository fails the board at every start.
        pages.holds(LIB, List.of(open(LIB, 6)));
        final ItemsBoardReader first = reader();
        final BoardCopy copy =
                withUnlisted(
                        first.toCopy(first.read(CONFIG), ChangeCursor.NONE, List.of()),
                        new TrackedUnlisted("I_upper", "Example-Lib", 3, "Secret plan", null),
                        new TrackedUnlisted("I_far", "elsewhere", 4, "Secret plan", null),
                        new TrackedUnlisted("I_zero", LIB, 0, "Secret plan", null));

        final Snapshot loaded = reader().fromCopy(copy, CONFIG);

        assertThat(loaded).as("the board from the copy").isNotNull();
        assertThat(ids(loaded.board()))
                .as("the issues outside the project")
                .containsExactly(LIB + "#6");
        assertThat(loaded.board().faults())
                .as("a fault for each odd entry, never its title")
                .hasSize(3)
                .noneMatch(fault -> fault.contains("Secret plan"));
    }

    @Test
    void aCopysAuthorOfNoLoginsFormAndAnUnknownAssociationAreLeftOutAndTheIssueStaysOffered() {
        // Harm: a copy on disk holding a text that is no login, or an association GitHub never
        // gives, failed the board at every start, or reached the window as the issue's author.
        final ItemsBoardReader first = reader();
        final BoardCopy copy =
                withUnlisted(
                        first.toCopy(first.read(CONFIG), ChangeCursor.NONE, List.of()),
                        new TrackedUnlisted(
                                "I_odd", LIB, 9, "Made by hand", null, "<b>x</b>", "ADMIN"));

        final Snapshot loaded = reader().fromCopy(copy, CONFIG);

        assertThat(loaded).as("the board from the copy").isNotNull();
        assertThat(loaded.board().unlisted())
                .as("the issue offered without either")
                .singleElement()
                .satisfies(
                        issue -> {
                            assertThat(issue.id()).isEqualTo(LIB + "#9");
                            assertThat(issue.author()).as("the author").isNull();
                            assertThat(issue.authorAssociation()).as("the association").isNull();
                        });
    }

    @Test
    void aCopyKeepsTheListAndItsRevision() {
        // Harm: the issues outside the project are gone after a restart, or the board read from
        // the copy is told to the window as changed.
        pages.holds(OWN, List.of(open(OWN, 5)));
        pages.holds(LIB, List.of(open(LIB, 6)));
        final ItemsBoardReader first = reader();
        final Snapshot read = first.read(CONFIG);

        final Snapshot loaded =
                reader().fromCopy(first.toCopy(read, ChangeCursor.NONE, List.of()), CONFIG);

        assertThat(loaded).as("the board from the copy").isNotNull();
        assertThat(loaded.board()).as("the board from the copy").isEqualTo(read.board());
    }

    @Test
    void aForbiddenRepositoryAfterARestartKeepsTheCopysList() {
        // Harm: the first read after a restart empties the list of a repository it may not list.
        pages.holds(OWN, List.of(open(OWN, 5)));
        pages.holds(LIB, List.of(open(LIB, 6)));
        final ItemsBoardReader first = reader();
        final BoardCopy copy = first.toCopy(first.read(CONFIG), ChangeCursor.NONE, List.of());
        final ItemsBoardReader restarted = reader();
        restarted.fromCopy(copy, CONFIG);
        pages.fails(LIB, page -> OpenIssues.refused(403));

        final Board board = restarted.read(CONFIG).board();

        assertThat(ids(board)).as("the lists").containsExactly(OWN + "#5", LIB + "#6");
        assertThat(board.faults())
                .as("the fault naming the repository")
                .singleElement()
                .asString()
                .contains(LIB);
    }

    @Test
    void aForbiddenRepositoryIsAFaultAndTheBoardIsServed() {
        // Harm: one repository the token may not list fails the whole board.
        pages.holds(OWN, List.of(open(OWN, 5)));
        pages.fails(LIB, page -> OpenIssues.refused(403));

        final Board board =
                reader(new ForgeAccessRefusals(URI.create("https://github.com")))
                        .read(CONFIG)
                        .board();

        assertThat(ids(board)).as("the issues outside the project").containsExactly(OWN + "#5");
        assertThat(board.faults()).as("the fault").singleElement().asString().contains(LIB, "403");
    }

    @Test
    void aSpentRateLimitRefusesTheRead() {
        // Harm: a spent rate limit read as one repository's refusal, so reads go on against it.
        pages.holds(OWN, List.of(open(OWN, 5)));
        pages.fails(
                LIB,
                page -> GitHubExceptions.rateLimited(403, "API rate limit", Duration.ofMinutes(5)));

        assertThatThrownBy(() -> reader().read(CONFIG))
                .as("the read refused")
                .isInstanceOf(StoreException.Unreachable.class);
    }

    @Test
    void aSpentBudgetLeavesTheProjectsOwnReadItsWholeDeadline() {
        // Harm: a slow list of issues outside the project makes the project's own board
        // unreadable.
        pages.holds(OWN, List.of(open(OWN, 5)));
        pages.holds(LIB, List.of(open(LIB, 6)));
        pages.perAsk = ItemsBoardReader.DEADLINE.plusSeconds(5);

        final Board board = reader().read(CONFIG).board();

        assertThat(ids(board)).as("the issues read in time").containsExactly(OWN + "#5");
        assertThat(board.story(OWN + "#1")).as("the project's story").isPresent();
        assertThat(board.faults())
                .as("the one fault of the budget")
                .singleElement()
                .asString()
                .contains(LIB);
    }

    @Test
    void aFailureOnTheWayPastTheListsDeadlineIsNotSentAgainAndLeavesTheProjectItsRead() {
        // Harm: a failed page sent once more after the lists' deadline holds the board, or fails
        // the project's own read.
        pages.holds(LIB, List.of(open(LIB, 6)));
        pages.fails(OWN, page -> OpenIssues.refused(502));
        pages.perAsk = RepositoryIssues.DEADLINE.plusSeconds(5);

        final Board board = reader().read(CONFIG).board();

        assertThat(pages.asksOf(OWN)).as("the asks of the failing page").hasSize(1);
        assertThat(board.story(OWN + "#1")).as("the project's story").isPresent();
        assertThat(board.faults())
                .as("the one fault of the budget")
                .singleElement()
                .asString()
                .contains(OWN, LIB);
    }

    @Test
    void aSlowGitHubAloneChangesNoRevision() {
        // Harm: every slow read tells the window of a change and refuses its next write as
        // stale, as the repositories the budget did not reach differ from read to read.
        pages.holds(OWN, List.of(open(OWN, 5)));
        pages.holds(LIB, List.of(open(LIB, 6)));
        final ItemsBoardReader reader = reader();
        reader.read(CONFIG);
        pages.perAsk = RepositoryIssues.DEADLINE.plusSeconds(5);

        final Board first = reader.read(CONFIG).board();
        final Board second = reader.read(CONFIG).board();

        assertThat(first.faults())
                .as("the faults of the two slow reads")
                .isNotEqualTo(second.faults());
        assertThat(second.revision())
                .as("the revision of the second slow read")
                .isEqualTo(first.revision());
    }

    @Test
    void aNewIssueOrANewTitleOutsideTheProjectChangesTheRevision() {
        // Harm: the window never hears of a hand-made issue, or writes over a board it holds
        // stale.
        pages.holds(OWN, List.of(open(OWN, 5)));
        final ItemsBoardReader reader = reader();
        final Board before = reader.read(CONFIG).board();
        assertThat(reader.read(CONFIG).board().revision())
                .as("the revision of an unchanged board")
                .isEqualTo(before.revision());

        pages.holds(OWN, List.of(open(OWN, 5), open(OWN, 9)));
        final Board added = reader.read(CONFIG).board();
        pages.holds(OWN, List.of(OpenIssues.issue(OWN, 5, "A new title", null), open(OWN, 9)));
        final Board retitled = reader.read(CONFIG).board();

        assertThat(added.revision())
                .as("the revision with a new issue")
                .isNotEqualTo(before.revision());
        assertThat(retitled.revision())
                .as("the revision with a new title")
                .isNotEqualTo(added.revision());
    }

    @Test
    void anIssueAddedToTheProjectLeavesTheListAtTheReadAfterTheSave() {
        // Harm: an issue just added to the project is shown both on it and outside it.
        pages.holds(OWN, List.of(open(OWN, 5)));
        final ItemsBoardReader reader = reader();
        final Snapshot held = reader.read(CONFIG);
        items.add(item(OWN, 5, false, null));

        final Snapshot after =
                reader.reread(
                        CONFIG,
                        held,
                        new Touched.Ids(
                                java.util.Set.of(OpenIssues.nodeOf(OWN, 5)),
                                java.util.Set.of(),
                                java.util.Set.of(),
                                java.util.Set.of()));

        assertThat(after.board().unlisted()).as("the issues outside the project").isEmpty();
        assertThat(after.board().faults()).as("the board's faults").isEmpty();
    }

    private static BoardCopy withUnlisted(
            final @Nullable BoardCopy copy, final TrackedUnlisted... more) {
        assertThat(copy).as("the copy").isNotNull();
        final List<TrackedUnlisted> unlisted = new ArrayList<>(copy.unlisted());
        unlisted.addAll(List.of(more));
        return new BoardCopy(
                copy.project(),
                copy.filesCommit(),
                copy.repositories(),
                copy.takenAt(),
                copy.board(),
                copy.items(),
                copy.cursor(),
                copy.held(),
                copy.waits(),
                unlisted);
    }
}

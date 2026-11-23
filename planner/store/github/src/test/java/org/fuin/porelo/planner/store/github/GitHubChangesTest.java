package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.forge.github.Conditional;
import org.fuin.porelo.forge.github.CursorPage;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.forge.github.ProjectItemSummary;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class GitHubChangesTest {

    private static final String TOKEN = "invented-token-0123456789";

    private static final String OWNER = "example-org";

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
                    org.fuin.porelo.planner.store.ProjectRepository.named(
                            List.of("example-project")),
                    Map.of(),
                    Map.of(),
                    "PVT_7");

    private static final Instant THEN = Instant.parse("2026-11-21T10:00:00Z");

    private static final Instant LATER = Instant.parse("2026-11-21T11:00:00Z");

    private static final Instant DATE = Instant.parse("2026-11-21T12:00:00Z");

    /**
     * The status field: its node id, as the copy keeps it, and its numeric id, as REST takes it.
     */
    private static final String STATUS = "PVTSSF_status";

    private static final long STATUS_ID = 11L;

    /** The fields' numeric ids, as the board's copy keeps them. */
    private static final Map<String, Long> FIELDS = Map.of(STATUS, STATUS_ID);

    /** One ask of a page of the item list, as the look sent it. */
    private record Ask(@Nullable String after, @Nullable String etag, List<Long> fieldIds) {}

    /** GitHub's item list and its by-id read, answering as each test sets them. */
    private static final class GitHub {
        private final Map<String, CursorPage<ProjectItemSummary>> pages = new LinkedHashMap<>();
        private final Map<String, String> tags = new LinkedHashMap<>();
        private final List<Ask> asks = new ArrayList<>();
        private final List<List<String>> byId = new ArrayList<>();
        private final Map<String, String> itemsOfIssues = new LinkedHashMap<>();

        /** The page after a cursor, with its tag now; a tag sent that matches is answered 304. */
        GitHub page(
                final @Nullable String after,
                final String etag,
                final @Nullable String next,
                final ProjectItemSummary... items) {
            pages.put(String.valueOf(after), new CursorPage<>(List.of(items), next));
            tags.put(String.valueOf(after), etag);
            return this;
        }

        /** An issue whose item GitHub holds in the project, though the list lacks it. */
        GitHub onProject(final String issueId, final String itemId) {
            itemsOfIssues.put(issueId, itemId);
            return this;
        }

        GitHubChanges changes() {
            final GitHubChanges.ItemPages list =
                    (token, owner, number, after, perPage, fieldIds, etag) -> {
                        assertThat(token).isEqualTo(TOKEN);
                        asks.add(new Ask(after, etag, fieldIds));
                        final String key = String.valueOf(after);
                        final String tag = Objects.requireNonNull(tags.get(key), key);
                        if (tag.equals(etag)) {
                            return new Conditional<>(null, tag, false, null, DATE);
                        }
                        return new Conditional<>(
                                Objects.requireNonNull(pages.get(key)), tag, true, null, DATE);
                    };
            final ProjectQueries queries =
                    (ProjectQueries)
                            Proxy.newProxyInstance(
                                    ProjectQueries.class.getClassLoader(),
                                    new Class<?>[] {ProjectQueries.class},
                                    (proxy, method, args) -> {
                                        if (!method.getName().equals("issueBoard")) {
                                            throw new AssertionError(
                                                    "A look called " + method.getName());
                                        }
                                        @SuppressWarnings("unchecked")
                                        final List<String> ids = (List<String>) args[1];
                                        byId.add(ids);
                                        final List<GitHubGraphQl.@Nullable IssueOnBoard> on =
                                                new ArrayList<>();
                                        for (final String id : ids) {
                                            final String item = itemsOfIssues.get(id);
                                            on.add(
                                                    item == null
                                                            ? null
                                                            : new GitHubGraphQl.IssueOnBoard(
                                                                    boardIssue(id),
                                                                    item,
                                                                    List.of(),
                                                                    new GitHubGraphQl.PageInfo(
                                                                            false, null)));
                                        }
                                        return new GitHubGraphQl.IssueBoard(
                                                on, new GraphQlCost(1, 4999, Instant.EPOCH));
                                    });
            return new GitHubChanges(list, queries);
        }
    }

    private static GitHubGraphQl.BoardIssue boardIssue(final String id) {
        final GitHubGraphQl.PageInfo none = new GitHubGraphQl.PageInfo(false, null);
        return new GitHubGraphQl.BoardIssue(
                id,
                1L,
                1,
                "A title",
                "A body",
                "OPEN",
                null,
                THEN,
                OWNER + "/example-project",
                List.of(),
                null,
                new GitHubGraphQl.IssueRefs(List.of(), none, 0),
                new GitHubGraphQl.IssueRefs(List.of(), none, 0),
                none,
                null,
                null,
                List.of(new GitHubGraphQl.IssueFieldOption("Priority", "Medium")));
    }

    /** An item as the copy holds it: open, in status Idea, with no links. */
    private static TrackedItem held(final String id) {
        return held(id, false, THEN, 0, "opt-idea");
    }

    private static TrackedItem held(
            final String id,
            final boolean archived,
            final Instant issueTime,
            final int subIssues,
            final String option) {
        final List<TrackedItem.Link> subs = new ArrayList<>();
        for (int i = 0; i < subIssues; i++) {
            subs.add(new TrackedItem.Link("I_sub" + i, 100L + i, 100 + i, "example-project"));
        }
        return new TrackedItem(
                id,
                archived,
                new TrackedItem.Issue(
                        "I_" + id,
                        1L,
                        1,
                        "A title",
                        "A body",
                        "OPEN",
                        null,
                        issueTime,
                        OWNER + "/example-project",
                        List.of(),
                        null,
                        new TrackedItem.Links(subs, false, null, subs.size()),
                        new TrackedItem.Links(List.of(), false, null, 0)),
                List.of(new TrackedItem.Value(STATUS, option, "Idea", null, null)),
                THEN,
                false);
    }

    /** An item as the list shows it, as {@link #held(String)} holds it. */
    private static ProjectItemSummary listed(final String id) {
        return listed(id, false, THEN, THEN, 0, "opt-idea");
    }

    private static ProjectItemSummary listed(
            final String id,
            final boolean archived,
            final Instant itemTime,
            final Instant issueTime,
            final int subIssues,
            final String option) {
        return new ProjectItemSummary(
                id,
                itemTime,
                archived,
                "Issue",
                new ProjectItemSummary.IssueSummary(
                        "I_" + id, OWNER + "/example-project", 1, issueTime, "open", subIssues, 0),
                List.of(
                        new ProjectItemSummary.ValueSummary(
                                STATUS_ID, "single_select", option, null, null)));
    }

    private static Map<String, TrackedItem> known(final TrackedItem... items) {
        final Map<String, TrackedItem> known = new LinkedHashMap<>();
        Arrays.stream(items).forEach(item -> known.put(item.id(), item));
        return known;
    }

    /** The cursor of the last look: two pages, the first full. */
    private static ChangeCursor twoPages() {
        return new ChangeCursor(
                THEN,
                List.of(
                        new ChangeCursor.Page(null, "\"a\"", true, List.of("PVTI_1", "PVTI_2")),
                        new ChangeCursor.Page("c2", "\"b\"", false, List.of("PVTI_3"))));
    }

    /** GitHub answering the two pages as {@link #twoPages()} saw them, unless a test says else. */
    private static GitHub unchanged() {
        return new GitHub()
                .page(null, "\"a\"", "c2", listed("PVTI_1"), listed("PVTI_2"))
                .page("c2", "\"b\"", null, listed("PVTI_3"));
    }

    private static final Map<String, TrackedItem> KNOWN =
            known(held("PVTI_1"), held("PVTI_2"), held("PVTI_3"));

    @Test
    void aLookAtAnUnchangedBoardNamesNothingAndReadsNothingByGraphQl() {
        // Harm: every start reads the whole board although nothing changed since the copy.
        final GitHub github = unchanged();

        final TrackerChanges.Changed changed =
                github.changes().since(CONFIG, TOKEN, twoPages(), KNOWN, FIELDS).orElseThrow();

        assertThat(changed.changed()).isEmpty();
        assertThat(changed.added()).isEmpty();
        assertThat(changed.gone()).isEmpty();
        assertThat(github.asks)
                .as("each page asked with its tag")
                .extracting(Ask::etag)
                .containsExactly("\"a\"", "\"b\"");
        assertThat(github.asks.get(0).fieldIds()).containsExactly(STATUS_ID);
        assertThat(github.byId).as("reads by GraphQL").isEmpty();
        assertThat(changed.next().asOf()).as("GitHub's own time").isEqualTo(DATE);
        assertThat(changed.next().pages())
                .extracting(ChangeCursor.Page::itemIds)
                .containsExactly(List.of("PVTI_1", "PVTI_2"), List.of("PVTI_3"));
    }

    @Test
    void aLookTellsTheItemsListedAfterEachPageAPage304AnsweredIncluded() {
        // Harm: a look of about 16 pages shows "0 of all" for its whole run.
        final GitHub github =
                unchanged().page(null, "\"a2\"", "c2", listed("PVTI_1"), listed("PVTI_2"));
        final List<Integer> told = new ArrayList<>();

        github.changes().since(CONFIG, TOKEN, twoPages(), KNOWN, FIELDS, told::add).orElseThrow();

        assertThat(told).as("the items listed after each page").containsExactly(2, 3);
    }

    @Test
    void aFieldValueChangedOnGitHubNamesExactlyThatItem() {
        // Harm: a status moved on GitHub never shows on a board started from its copy.
        final GitHub github =
                unchanged()
                        .page(
                                null,
                                "\"a2\"",
                                "c2",
                                listed("PVTI_1"),
                                listed("PVTI_2", false, THEN, THEN, 0, "opt-done"));

        final TrackerChanges.Changed changed =
                github.changes().since(CONFIG, TOKEN, twoPages(), KNOWN, FIELDS).orElseThrow();

        assertThat(changed.changed()).containsExactly("PVTI_2");
        assertThat(changed.added()).isEmpty();
        assertThat(changed.gone()).isEmpty();
    }

    @Test
    void aTitleArchiveOrCountChangeNamesExactlyThatItem() {
        // Harm: a retitled, archived or newly linked issue keeps its old face on the board.
        final GitHub github =
                unchanged()
                        .page(
                                null,
                                "\"a2\"",
                                "c2",
                                // A new title moves the issue's time.
                                listed("PVTI_1", false, THEN, LATER, 0, "opt-idea"),
                                // A sub-issue linked moves only the count.
                                listed("PVTI_2", false, THEN, THEN, 1, "opt-idea"))
                        .page(
                                "c2",
                                "\"b2\"",
                                null,
                                listed("PVTI_3", true, LATER, THEN, 0, "opt-idea"));

        final TrackerChanges.Changed changed =
                github.changes().since(CONFIG, TOKEN, twoPages(), KNOWN, FIELDS).orElseThrow();

        assertThat(changed.changed()).containsExactlyInAnyOrder("PVTI_1", "PVTI_2", "PVTI_3");
        assertThat(changed.added()).isEmpty();
    }

    @Test
    void aParentChangedOnGitHubNamesTheParentWhoseCountMoved() {
        // Harm: an issue moved to another story stays under its old story on the board.
        final Map<String, TrackedItem> known =
                known(held("PVTI_1", false, THEN, 1, "opt-idea"), held("PVTI_2"), held("PVTI_3"));
        final GitHub github =
                unchanged()
                        .page(
                                null,
                                "\"a2\"",
                                "c2",
                                listed("PVTI_1", false, THEN, THEN, 0, "opt-idea"),
                                listed("PVTI_2", false, THEN, THEN, 1, "opt-idea"));

        final TrackerChanges.Changed changed =
                github.changes().since(CONFIG, TOKEN, twoPages(), known, FIELDS).orElseThrow();

        assertThat(changed.changed()).containsExactlyInAnyOrder("PVTI_1", "PVTI_2");
    }

    @Test
    void anItemInsertedInsideAFullPageHasThePagesAfterItAskedWhole() {
        // Harm: an item inserted within a full page pushes its last item onto the next page,
        // whose old tag GitHub may still answer 304, and the look stands on a page it never saw.
        final List<ProjectItemSummary> full = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        final Map<String, TrackedItem> known = new LinkedHashMap<>();
        for (int at = 1; at <= GitHubChanges.PAGE_SIZE; at++) {
            full.add(listed("PVTI_" + at));
            ids.add("PVTI_" + at);
            known.put("PVTI_" + at, held("PVTI_" + at));
        }
        known.put("PVTI_101", held("PVTI_101"));
        final ChangeCursor before =
                new ChangeCursor(
                        THEN,
                        List.of(
                                new ChangeCursor.Page(null, "\"a\"", true, ids),
                                new ChangeCursor.Page("c2", "\"b\"", false, List.of("PVTI_101"))));
        // The page as full as before and beginning as before, an item inserted second.
        final List<ProjectItemSummary> shifted = new ArrayList<>(full.subList(0, 99));
        shifted.add(1, listed("PVTI_new"));
        final GitHub github =
                new GitHub()
                        .page(null, "\"a2\"", "c2", shifted.toArray(ProjectItemSummary[]::new))
                        .page("c2", "\"b\"", null, listed("PVTI_100"), listed("PVTI_101"));
        final GitHubChanges changes = github.changes();

        final TrackerChanges.Changed changed =
                changes.since(CONFIG, TOKEN, before, known, FIELDS).orElseThrow();

        assertThat(github.asks)
                .extracting(Ask::etag)
                .as("the second page asked without its tag")
                .containsExactly("\"a\"", null);
        assertThat(changed.added()).containsExactly("PVTI_new");
        assertThat(changed.gone()).as("the item pushed onto the next page").isEmpty();
        assertThat(changed.next().pages().get(1).itemIds()).containsExactly("PVTI_100", "PVTI_101");
        assertThat(changes.issueOf("PVTI_new")).as("the new item's issue").isEqualTo("I_PVTI_new");
    }

    @Test
    void theIssuesOfItemsNeitherHeldNorListedAreLetGo() {
        // Harm: every item a look ever saw stays remembered for the life of the planner.
        final GitHub github =
                unchanged().page("c2", "\"b2\"", null, listed("PVTI_3"), listed("PVTI_6"));
        final GitHubChanges changes = github.changes();
        changes.since(CONFIG, TOKEN, twoPages(), KNOWN, FIELDS).orElseThrow();
        assertThat(changes.issueOf("PVTI_6")).as("the new item after its look").isNotNull();

        github.page("c2", "\"b3\"", null, listed("PVTI_3"));
        changes.since(CONFIG, TOKEN, twoPages(), KNOWN, FIELDS).orElseThrow();

        assertThat(changes.issueOf("PVTI_6")).as("the item neither held nor listed").isNull();
        assertThat(changes.issueOf("PVTI_3")).as("an item held").isEqualTo("I_PVTI_3");
    }

    @Test
    void aBoardWhoseFieldsCannotAllBeNamedInOneAskIsReadWhole() {
        // Harm: a planner field left out of the ask changes on GitHub and is never seen.
        final Map<String, Long> many = new LinkedHashMap<>();
        for (long id = 1; id <= org.fuin.porelo.forge.github.GitHubClient.MAX_FIELDS + 1; id++) {
            many.put("PVTF_" + id, id);
        }
        final GitHub github = unchanged();

        assertThat(github.changes().since(CONFIG, TOKEN, twoPages(), KNOWN, many))
                .as("the look with more fields than one ask names")
                .isEmpty();
        assertThat(github.changes().since(CONFIG, TOKEN, twoPages(), KNOWN, null))
                .as("the look with a field that has no numeric id")
                .isEmpty();
        assertThat(github.asks).as("asks of the list").isEmpty();
    }

    @Test
    void whatTheListShowedOfAnItemIsToldForThePagesAnsweredAnew() {
        // Harm: an item archived on GitHub is read again by id, which does not tell it, and
        // stays on the board.
        final GitHub github =
                unchanged()
                        .page(
                                "c2",
                                "\"b2\"",
                                null,
                                listed("PVTI_3", true, LATER, THEN, 0, "opt-idea"));
        final GitHubChanges changes = github.changes();

        changes.since(CONFIG, TOKEN, twoPages(), KNOWN, FIELDS).orElseThrow();

        assertThat(changes.listed("PVTI_3"))
                .as("the item on the page answered anew")
                .isEqualTo(new ItemIssues.Listed(true, LATER, THEN));
        assertThat(changes.listed("PVTI_1")).as("an item on a page answered 304").isNull();
    }

    @Test
    void anItemMissingFromTheListIsKeptWhileGitHubHoldsItAndGoneOnceItDoesNot() {
        // Harm: an issue just added drops off the board because GitHub's list lags it.
        final GitHub github =
                unchanged().page("c2", "\"b2\"", null).onProject("I_PVTI_3", "PVTI_3");
        final Map<String, TrackedItem> known =
                known(held("PVTI_1"), held("PVTI_2"), held("PVTI_3"), held("PVTI_5"));

        final TrackerChanges.Changed changed =
                github.changes().since(CONFIG, TOKEN, twoPages(), known, FIELDS).orElseThrow();

        assertThat(changed.gone()).containsExactly("PVTI_5");
        assertThat(changed.changed()).isEmpty();
        assertThat(github.byId).containsExactly(List.of("I_PVTI_3", "I_PVTI_5"));
    }

    @Test
    void aNewItemIsNamedWithTheIssueItHolds() {
        // Harm: an item new on the board cannot be read, since GitHub reads issues by id only.
        final GitHub github =
                unchanged().page("c2", "\"b2\"", null, listed("PVTI_3"), listed("PVTI_6"));
        final GitHubChanges changes = github.changes();

        final TrackerChanges.Changed changed =
                changes.since(CONFIG, TOKEN, twoPages(), KNOWN, FIELDS).orElseThrow();

        assertThat(changed.added()).containsExactly("PVTI_6");
        assertThat(changes.issueOf("PVTI_6")).isEqualTo("I_PVTI_6");
    }

    @Test
    void anItemWhoseLinksTheCopyHoldsInPartIsReadAgain() {
        // Harm: a story with more sub-issues than a page is taken as whole from its copy.
        final TrackedItem partial = held("PVTI_1");
        final TrackedItem.Issue issue = Objects.requireNonNull(partial.issue());
        final TrackedItem more =
                new TrackedItem(
                        partial.id(),
                        false,
                        new TrackedItem.Issue(
                                issue.id(),
                                issue.key(),
                                issue.number(),
                                issue.title(),
                                issue.body(),
                                issue.state(),
                                issue.stateReason(),
                                issue.updatedAt(),
                                issue.repository(),
                                issue.labels(),
                                issue.parentId(),
                                new TrackedItem.Links(List.of(), true, "next", 1),
                                issue.blockedBy()),
                        partial.values(),
                        partial.updatedAt(),
                        false);
        final GitHub github =
                unchanged().page(null, "\"a2\"", "c2", listed("PVTI_1"), listed("PVTI_2"));

        final TrackerChanges.Changed changed =
                github.changes()
                        .since(
                                CONFIG,
                                TOKEN,
                                twoPages(),
                                known(more, held("PVTI_2"), held("PVTI_3")),
                                FIELDS)
                        .orElseThrow();

        assertThat(changed.changed()).containsExactly("PVTI_1");
    }

    @Test
    void aCursorWithoutGitHubsTimeAsksEveryPageWhole() {
        // Harm: tags not taken from GitHub's answers stand for a board they never described.
        final GitHub github = unchanged();
        final ChangeCursor undated = new ChangeCursor(null, twoPages().pages());

        github.changes().since(CONFIG, TOKEN, undated, KNOWN, FIELDS).orElseThrow();

        assertThat(github.asks).extracting(Ask::etag).containsExactly(null, null);
    }

    @Test
    void anItemWhoseLabelsOrValuesTheCopyHoldsInPartIsReadAgain() {
        // Harm: an item with more labels or values than a page is taken as whole from its copy.
        final TrackedItem whole = held("PVTI_1");
        final TrackedItem partial =
                new TrackedItem(
                        whole.id(),
                        whole.archived(),
                        whole.issue(),
                        whole.values(),
                        whole.updatedAt(),
                        true);
        final GitHub github =
                unchanged().page(null, "\"a2\"", "c2", listed("PVTI_1"), listed("PVTI_2"));

        final TrackerChanges.Changed changed =
                github.changes()
                        .since(
                                CONFIG,
                                TOKEN,
                                twoPages(),
                                known(partial, held("PVTI_2"), held("PVTI_3")),
                                FIELDS)
                        .orElseThrow();

        assertThat(changed.changed()).containsExactly("PVTI_1");
    }

    @Test
    void aClosedBlockerCountsAlikeInGraphQlsListAndRestsCountSoNoItemIsNamedAtEveryLook() {
        // Harm: an issue blocked by a closed issue is read again at every look, for ever.
        final GitHubGraphQl.PageInfo none = new GitHubGraphQl.PageInfo(false, null);
        final GitHubGraphQl.BoardIssue open = boardIssue("I_PVTI_1");
        // GraphQL's blockedBy lists the closed blocker beside the open one.
        final GitHubGraphQl.IssueRefs blockers =
                new GitHubGraphQl.IssueRefs(
                        List.of(
                                new GitHubGraphQl.IssueRef(
                                        "I_open", 21L, 21, OWNER + "/example-project"),
                                new GitHubGraphQl.IssueRef(
                                        "I_closed", 22L, 22, OWNER + "/example-project")),
                        none,
                        2);
        final GitHubGraphQl.ProjectItem read =
                new GitHubGraphQl.ProjectItem(
                        "PVTI_1",
                        false,
                        new GitHubGraphQl.BoardIssue(
                                open.id(),
                                open.databaseId(),
                                open.number(),
                                open.title(),
                                open.body(),
                                open.state(),
                                open.stateReason(),
                                open.updatedAt(),
                                open.repository(),
                                open.labels(),
                                open.parentId(),
                                open.subIssues(),
                                blockers,
                                none,
                                null,
                                null,
                                open.fieldOptions()),
                        List.of(
                                new GitHubGraphQl.ItemValue(
                                        STATUS, "opt-idea", "Idea", null, null)),
                        none);
        // REST's total_blocked_by counts closed blockers too.
        final ProjectItemSummary listed =
                new ProjectItemSummary(
                        "PVTI_1",
                        THEN,
                        false,
                        "Issue",
                        new ProjectItemSummary.IssueSummary(
                                "I_PVTI_1", OWNER + "/example-project", 1, THEN, "open", 0, 2),
                        List.of(
                                new ProjectItemSummary.ValueSummary(
                                        STATUS_ID, "single_select", "opt-idea", null, null)));
        final GitHub github = unchanged().page(null, "\"a2\"", "c2", listed, listed("PVTI_2"));

        final TrackerChanges.Changed changed =
                github.changes()
                        .since(
                                CONFIG,
                                TOKEN,
                                twoPages(),
                                known(
                                        BoardCopyMapping.tracked(read),
                                        held("PVTI_2"),
                                        held("PVTI_3")),
                                FIELDS)
                        .orElseThrow();

        assertThat(changed.changed()).isEmpty();
    }

    @Test
    void anOlderCopyWithoutGitHubsCountOfBlockersHasTheItemReadAgainWhenRestCountsOneMore() {
        // Harm: a board started from a copy made before the count was kept never learns of a
        // blocker the person cannot read, and offers its issue.
        final GitHubGraphQl.PageInfo none = new GitHubGraphQl.PageInfo(false, null);
        final GitHubGraphQl.BoardIssue open = boardIssue("I_PVTI_1");
        // An older copy kept as the count the blockers it names.
        final GitHubGraphQl.IssueRefs blockers =
                new GitHubGraphQl.IssueRefs(
                        List.of(
                                new GitHubGraphQl.IssueRef(
                                        "I_open", 21L, 21, OWNER + "/example-project")),
                        none,
                        1);
        final GitHubGraphQl.ProjectItem read =
                new GitHubGraphQl.ProjectItem(
                        "PVTI_1",
                        false,
                        new GitHubGraphQl.BoardIssue(
                                open.id(),
                                open.databaseId(),
                                open.number(),
                                open.title(),
                                open.body(),
                                open.state(),
                                open.stateReason(),
                                open.updatedAt(),
                                open.repository(),
                                open.labels(),
                                open.parentId(),
                                open.subIssues(),
                                blockers,
                                none,
                                null,
                                null,
                                open.fieldOptions()),
                        List.of(
                                new GitHubGraphQl.ItemValue(
                                        STATUS, "opt-idea", "Idea", null, null)),
                        none);
        final ProjectItemSummary listed =
                new ProjectItemSummary(
                        "PVTI_1",
                        THEN,
                        false,
                        "Issue",
                        new ProjectItemSummary.IssueSummary(
                                "I_PVTI_1", OWNER + "/example-project", 1, THEN, "open", 0, 2),
                        List.of(
                                new ProjectItemSummary.ValueSummary(
                                        STATUS_ID, "single_select", "opt-idea", null, null)));
        final GitHub github = unchanged().page(null, "\"a2\"", "c2", listed, listed("PVTI_2"));

        final TrackerChanges.Changed changed =
                github.changes()
                        .since(
                                CONFIG,
                                TOKEN,
                                twoPages(),
                                known(
                                        BoardCopyMapping.tracked(read),
                                        held("PVTI_2"),
                                        held("PVTI_3")),
                                FIELDS)
                        .orElseThrow();

        assertThat(changed.changed()).as("the item read again").contains("PVTI_1");
    }

    @Test
    void aBlockerGraphQlCountsButDoesNotNameCountsAlikeInRestsCountSoNoItemIsNamedAtEveryLook() {
        // Harm: an issue blocked by an issue the person cannot read is read again at every look,
        // as the copy holds only the blockers GraphQL names against REST's count of all of them.
        final GitHubGraphQl.PageInfo none = new GitHubGraphQl.PageInfo(false, null);
        final GitHubGraphQl.BoardIssue open = boardIssue("I_PVTI_1");
        // GraphQL names the blocker the person reads and counts the one it leaves out.
        final GitHubGraphQl.IssueRefs blockers =
                new GitHubGraphQl.IssueRefs(
                        List.of(
                                new GitHubGraphQl.IssueRef(
                                        "I_open", 21L, 21, OWNER + "/example-project")),
                        none,
                        2);
        final GitHubGraphQl.ProjectItem read =
                new GitHubGraphQl.ProjectItem(
                        "PVTI_1",
                        false,
                        new GitHubGraphQl.BoardIssue(
                                open.id(),
                                open.databaseId(),
                                open.number(),
                                open.title(),
                                open.body(),
                                open.state(),
                                open.stateReason(),
                                open.updatedAt(),
                                open.repository(),
                                open.labels(),
                                open.parentId(),
                                open.subIssues(),
                                blockers,
                                none,
                                null,
                                null,
                                open.fieldOptions()),
                        List.of(
                                new GitHubGraphQl.ItemValue(
                                        STATUS, "opt-idea", "Idea", null, null)),
                        none);
        final ProjectItemSummary listed =
                new ProjectItemSummary(
                        "PVTI_1",
                        THEN,
                        false,
                        "Issue",
                        new ProjectItemSummary.IssueSummary(
                                "I_PVTI_1", OWNER + "/example-project", 1, THEN, "open", 0, 2),
                        List.of(
                                new ProjectItemSummary.ValueSummary(
                                        STATUS_ID, "single_select", "opt-idea", null, null)));
        final GitHub github = unchanged().page(null, "\"a2\"", "c2", listed, listed("PVTI_2"));

        final TrackerChanges.Changed changed =
                github.changes()
                        .since(
                                CONFIG,
                                TOKEN,
                                twoPages(),
                                known(
                                        BoardCopyMapping.tracked(read),
                                        held("PVTI_2"),
                                        held("PVTI_3")),
                                FIELDS)
                        .orElseThrow();

        assertThat(changed.changed()).isEmpty();
    }

    /** An item whose blockers GitHub counts as {@code total}, listing the two given. */
    private static GitHubGraphQl.ProjectItem blockedItem(final boolean more, final int total) {
        final GitHubGraphQl.PageInfo none = new GitHubGraphQl.PageInfo(false, null);
        final GitHubGraphQl.BoardIssue open = boardIssue("I_PVTI_1");
        return new GitHubGraphQl.ProjectItem(
                "PVTI_1",
                false,
                new GitHubGraphQl.BoardIssue(
                        open.id(),
                        open.databaseId(),
                        open.number(),
                        open.title(),
                        open.body(),
                        open.state(),
                        open.stateReason(),
                        open.updatedAt(),
                        open.repository(),
                        open.labels(),
                        open.parentId(),
                        open.subIssues(),
                        new GitHubGraphQl.IssueRefs(
                                List.of(
                                        new GitHubGraphQl.IssueRef(
                                                "I_open", 21L, 21, OWNER + "/example-project"),
                                        new GitHubGraphQl.IssueRef(
                                                "I_other", 22L, 22, OWNER + "/example-project")),
                                new GitHubGraphQl.PageInfo(more, more ? "c" : null),
                                total),
                        none,
                        null,
                        null,
                        open.fieldOptions()),
                List.of(new GitHubGraphQl.ItemValue(STATUS, "opt-idea", "Idea", null, null)),
                none);
    }

    @Test
    void aCopyKeepsGitHubsCountOfALongListThroughItsWayBackAndOut() {
        // Harm: a board started from its copy holds the count of the issues it lists in place
        // of GitHub's, so a look compares GitHub's count with the wrong one.
        final TrackedItem copied = BoardCopyMapping.tracked(blockedItem(true, 3));

        final TrackedItem again = BoardCopyMapping.tracked(BoardCopyMapping.item(copied));

        assertThat(copied.issue().blockedBy().total()).as("the count kept").isEqualTo(3);
        assertThat(again.issue().blockedBy().total())
                .as("the count kept through the copy and back")
                .isEqualTo(3);
        assertThat(again.issue().blockedBy().links()).as("the two listed").hasSize(2);
    }

    @Test
    void aWholeListWhoseCountGitHubGivesAboveItsListedIssuesIsNotNamedAtEveryLook() {
        // Harm: an issue whose blockers GitHub counts beyond those it lists, such as one the
        // person cannot read, is read again at every look, for ever.
        final ProjectItemSummary listed =
                new ProjectItemSummary(
                        "PVTI_1",
                        THEN,
                        false,
                        "Issue",
                        new ProjectItemSummary.IssueSummary(
                                "I_PVTI_1", OWNER + "/example-project", 1, THEN, "open", 0, 3),
                        List.of(
                                new ProjectItemSummary.ValueSummary(
                                        STATUS_ID, "single_select", "opt-idea", null, null)));
        final GitHub github = unchanged().page(null, "\"a2\"", "c2", listed, listed("PVTI_2"));

        final TrackerChanges.Changed changed =
                github.changes()
                        .since(
                                CONFIG,
                                TOKEN,
                                twoPages(),
                                known(
                                        BoardCopyMapping.tracked(blockedItem(false, 3)),
                                        held("PVTI_2"),
                                        held("PVTI_3")),
                                FIELDS)
                        .orElseThrow();

        assertThat(changed.changed()).isEmpty();
    }
}

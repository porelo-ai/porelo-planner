package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The GitHub writer's add of an issue made outside the project: what it reads right before the
 * write, what it refuses before anything is written, the one save it writes, and what a failure on
 * the way says was saved.
 */
class UnlistedAddTest {

    private static final String TOKEN = "invented-token-add-0123456789";

    private static final String OWNER = "example-org";

    private static final String OWN = "example-project";

    private static final String LIB = "example-lib";

    private static final Revision SHOWN = new Revision("r1");

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Backlog", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Backlog", "Finished"))),
                    List.of("Finished"));

    private static final Map<String, String> FIELD_IDS = new LinkedHashMap<>();

    private static final Map<String, Map<String, String>> OPTION_IDS = new LinkedHashMap<>();

    static {
        for (final String field : FieldNames.ALL.keySet()) {
            FIELD_IDS.put(field, "F_" + field.replace(' ', '_'));
        }
        OPTION_IDS.put(FieldNames.STATUS, options(WORKFLOW.states()));
        OPTION_IDS.put(FieldNames.WORKER, options(List.of("Agent", "Manual")));
        OPTION_IDS.put(FieldNames.PRIORITY, options(Story.PRIORITIES));
    }

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef(OWNER, 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    List.of(OWN, LIB),
                    FIELD_IDS,
                    OPTION_IDS,
                    "PVT_7");

    private static final GitHubGraphQl.PageInfo LAST = new GitHubGraphQl.PageInfo(false, null);

    private static Map<String, String> options(final List<String> names) {
        final Map<String, String> ids = new LinkedHashMap<>();
        names.forEach(name -> ids.put(name, "O_" + name));
        return ids;
    }

    /** GitHub's side of the add: the issue as a read by id gives it, and every call made. */
    private static final class GitHub {
        final List<String> calls = new ArrayList<>();
        final List<GitHubGraphQl.FieldValue> values = new ArrayList<>();
        @Nullable String itemId;
        @Nullable String type;
        @Nullable String parentId;
        String state = "OPEN";
        String repository = OWNER + "/" + LIB;
        List<String> labels = List.of();
        List<GitHubGraphQl.IssueRef> blockers = List.of();
        boolean gone;
        boolean moreBlockers;
        Function<List<String>, Batch<String>> add =
                ids ->
                        new Batch<>(
                                List.of(Batch.Outcome.of("PVTI_added")),
                                null,
                                GitHubExceptions.WITHOUT_A_WORD);
        Function<List<GitHubGraphQl.FieldValue>, Batch<String>> set =
                list ->
                        new Batch<>(
                                list.stream().map(v -> Batch.Outcome.of(v.itemId())).toList(),
                                null,
                                GitHubExceptions.WITHOUT_A_WORD);

        GitHubGraphQl.IssueBoard read(final List<String> ids) {
            final List<GitHubGraphQl.@Nullable IssueOnBoard> on = new ArrayList<>();
            for (final String id : ids) {
                on.add(
                        gone
                                ? null
                                : new GitHubGraphQl.IssueOnBoard(
                                        new GitHubGraphQl.BoardIssue(
                                                id,
                                                906L,
                                                6,
                                                "Made by hand",
                                                "A body",
                                                state,
                                                null,
                                                Instant.EPOCH,
                                                repository,
                                                labels,
                                                parentId,
                                                new GitHubGraphQl.IssueRefs(List.of(), LAST, 0),
                                                new GitHubGraphQl.IssueRefs(
                                                        blockers,
                                                        moreBlockers
                                                                ? new GitHubGraphQl.PageInfo(
                                                                        true, "next")
                                                                : LAST,
                                                        blockers.size()),
                                                LAST,
                                                type,
                                                null),
                                        itemId,
                                        List.of(),
                                        LAST));
            }
            return new GitHubGraphQl.IssueBoard(on, new GraphQlCost(1, 4999, Instant.EPOCH));
        }

        @SuppressWarnings("unchecked")
        ProjectWrites writes() {
            return (ProjectWrites)
                    Proxy.newProxyInstance(
                            ProjectWrites.class.getClassLoader(),
                            new Class<?>[] {ProjectWrites.class},
                            (proxy, method, args) -> {
                                calls.add(method.getName());
                                assertThat(args[0]).as("the person's token").isEqualTo(TOKEN);
                                return switch (method.getName()) {
                                    case "issueBoard" -> read((List<String>) args[1]);
                                    case "addProjectItems" -> add.apply((List<String>) args[2]);
                                    case "setFieldValues" -> {
                                        final List<GitHubGraphQl.FieldValue> sent =
                                                (List<GitHubGraphQl.FieldValue>) args[2];
                                        values.addAll(sent);
                                        yield set.apply(sent);
                                    }
                                    default ->
                                            throw new AssertionError(
                                                    "an add called " + method.getName());
                                };
                            });
        }

        FieldsBoardWriter writer() {
            return new FieldsBoardWriter(
                    writes(),
                    origin -> TOKEN,
                    AccessRefusals.DEFAULT,
                    () -> Instant.EPOCH,
                    d -> {},
                    LimitFactors.DEFAULT);
        }

        /** The values written, as field name to option or number. */
        Map<String, String> written() {
            final Map<String, String> byField = new LinkedHashMap<>();
            for (final GitHubGraphQl.FieldValue value : values) {
                final String field =
                        FIELD_IDS.entrySet().stream()
                                .filter(e -> e.getValue().equals(value.fieldId()))
                                .findFirst()
                                .orElseThrow()
                                .getKey();
                byField.put(
                        field,
                        value.optionId() != null
                                ? value.optionId().substring("O_".length())
                                : String.valueOf(value.number()));
            }
            return byField;
        }
    }

    /** An item of the project, of the own repository unless said otherwise. */
    private static ItemView item(
            final String repository, final int number, final @Nullable String type) {
        return new ItemView(
                        "PVTI_" + repository + "_" + number,
                        "I_" + repository + "_" + number,
                        repository,
                        number,
                        "On the board",
                        "",
                        true,
                        null,
                        Map.of(FieldNames.PRIORITY, "Medium", FieldNames.RANK, "4"),
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        URI.create(
                                "https://github.com/"
                                        + OWNER
                                        + "/"
                                        + repository
                                        + "/issues/"
                                        + number),
                        Instant.EPOCH,
                        false)
                .typed(type);
    }

    private static UnlistedIssue offered(final String repository, final int number) {
        return new UnlistedIssue(
                repository + "#" + number,
                repository,
                "Made by hand",
                UnlistedIssue.Kind.ISSUE,
                URI.create("https://github.com/" + OWNER + "/" + repository + "/issues/" + number));
    }

    /**
     * A snapshot whose board offers what it names, its repositories' lists holding those and the
     * issues given, and whose items are those given.
     */
    private static Snapshot snapshot(
            final List<UnlistedIssue> offer,
            final List<Snapshot.Unlisted> listed,
            final ItemView... items) {
        final List<Issue> placed = new ArrayList<>();
        for (final ItemView item : items) {
            if (!IssueMapping.STORY_TYPE.equals(item.issueType())) {
                placed.add(placed(item.repository() + "#" + item.number(), null));
            }
        }
        return snapshot(offer, listed, placed, items);
    }

    /** An issue as the board places it, under the parent given. */
    private static Issue placed(final String id, final @Nullable String parent) {
        return new Issue(
                id,
                id.substring(0, id.indexOf('#')),
                "On the board",
                "Idea",
                null,
                List.of(),
                parent,
                null,
                "");
    }

    /** The item given, blocked by the issue of the node id given. */
    private static ItemView blockedBy(final ItemView item, final String nodeId) {
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
                item.labels(),
                item.parentIssueId(),
                item.subIssueIds(),
                List.of(nodeId),
                item.link(),
                item.updatedAt(),
                item.archived(),
                item.blockers(),
                item.unseenBlockers(),
                item.issueType());
    }

    /**
     * A snapshot whose board offers what it names and places the issues given, its repositories'
     * lists holding those and the issues given, and whose items are those given.
     */
    private static Snapshot snapshot(
            final List<UnlistedIssue> offer,
            final List<Snapshot.Unlisted> listed,
            final List<Issue> placed,
            final ItemView... items) {
        final List<Story> stories = new ArrayList<>();
        for (final ItemView item : items) {
            if (IssueMapping.STORY_TYPE.equals(item.issueType())) {
                stories.add(
                        new Story(
                                item.repository() + "#" + item.number(),
                                "A story",
                                "Idea",
                                false,
                                "Medium",
                                null,
                                List.of(),
                                "",
                                null));
            }
        }
        final Map<String, ItemView> byId = new LinkedHashMap<>();
        for (final ItemView item : items) {
            byId.put(item.repository() + "#" + item.number(), item);
        }
        return new Snapshot(
                new Board(
                        SHOWN,
                        WORKFLOW,
                        stories,
                        placed,
                        new Work(Map.of(), List.of()),
                        CONFIG.repositories(),
                        List.of(),
                        OWNER,
                        offer),
                byId,
                CONFIG,
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                listed);
    }

    /** The board offering example-lib#6, an issue of no type, with the items given. */
    private static Snapshot offering(final ItemView... items) {
        return snapshot(
                List.of(offered(LIB, 6)),
                List.of(new Snapshot.Unlisted("I_lib_6", LIB, 6, "Made by hand", null)),
                items);
    }

    @Test
    void anIssueIsAddedAndGivenTheFirstStateWithItsValuesInOneSave() {
        // Harm: the issue lands on the project without a Status, so the board shows it outside the
        // workflow and offers it no longer, or an agent takes work nobody chose for one.
        final GitHub github = new GitHub();
        final ItemView ranked = item(LIB, 2, null);

        github.writer().addUnlistedIssue(offering(ranked), SHOWN, LIB + "#6");

        assertThat(github.calls)
                .as("read right before the write, then added and given its values")
                .containsExactly("issueBoard", "addProjectItems", "setFieldValues");
        assertThat(github.written())
                .as("the values of the added issue")
                .containsExactly(
                        Map.entry(FieldNames.STATUS, "Idea"),
                        Map.entry(FieldNames.WORKER, "Manual"),
                        Map.entry(FieldNames.PRIORITY, "Medium"),
                        Map.entry(FieldNames.RANK, "5.0"));
        assertThat(github.values)
                .as("every value on the added item")
                .allMatch(value -> value.itemId().equals("PVTI_added"));
    }

    @Test
    void anIssueLabeledStoryWithoutTheTypeIsAddedAsAnIssue() {
        // Harm: a label made an added issue a story without a Status, though the board knows a
        // story by its issue type alone and shows it as an issue outside the workflow.
        final GitHub github = new GitHub();
        github.labels = List.of("story");

        github.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6");

        assertThat(github.written())
                .as("an issue's first state")
                .containsEntry(FieldNames.STATUS, "Idea");
    }

    @Test
    void aStoryTypedOnlyOnGitHubIsAddedWithoutAStatusSoItFollowsItsIssues() {
        // Harm: a story made by hand is added as an issue held in Idea, never following its
        // issues; the offer read before named it an issue, GitHub's issue now names it a story.
        final GitHub github = new GitHub();
        github.type = IssueMapping.STORY_TYPE;
        github.repository = OWNER + "/" + OWN;
        final Snapshot current =
                snapshot(
                        List.of(offered(OWN, 6)),
                        List.of(new Snapshot.Unlisted("I_own_6", OWN, 6, "Made by hand", null)),
                        item(OWN, 1, IssueMapping.STORY_TYPE));

        github.writer().addUnlistedIssue(current, SHOWN, OWN + "#6");

        assertThat(github.written())
                .as("a story's values: its priority and its rank among the stories")
                .containsExactly(
                        Map.entry(FieldNames.PRIORITY, "Medium"),
                        Map.entry(FieldNames.RANK, "5.0"));
    }

    @Test
    void anIssueTypedStoryOutsideTheOwnRepositoryIsRefusedBeforeAnyWrite() {
        // Harm: the issue is added, then the board shows it only as a warned issue no work starts
        // on, as a story outside the own repository, while the window is told it was added.
        final GitHub github = new GitHub();
        github.type = IssueMapping.STORY_TYPE;

        assertThatThrownBy(() -> github.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6"))
                .as("a story the board cannot hold")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining(
                        "Story outside the project's own repository, so the board would show the"
                                + " issue only as a warned issue no work starts on"
                                + " (StoryOutsideOwnRepository).");
        assertThat(github.calls).as("only the read").containsExactly("issueBoard");
    }

    @Test
    void anIssueAPersonAddedOnGitHubMeanwhileIsRefusedAndItsValuesKept() {
        // Harm: a person added the issue on GitHub's board and moved it on since the planner's
        // read; the add wrote Idea, a Priority and a Rank over the person's values.
        final GitHub github = new GitHub();
        github.itemId = "PVTI_theirs";

        assertThatThrownBy(() -> github.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6"))
                .as("an issue on the project already")
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale -> assertThat(stale.changed()).isEqualTo(LIB + "#6"));
        assertThat(github.calls).as("only the read").containsExactly("issueBoard");
    }

    @Test
    void anIssueGoneClosedOrMovedSinceTheOfferIsRefusedBeforeAnyWrite() {
        // Harm: an offer kept from a list GitHub refused adds a closed issue in an open state, or
        // a transferred one that the board then leaves out of another repository.
        final GitHub gone = new GitHub();
        gone.gone = true;
        assertThatThrownBy(() -> gone.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6"))
                .as("an issue GitHub no longer holds")
                .isInstanceOf(StoreException.NoSuchIssue.class);
        assertThat(gone.calls).as("only the read").containsExactly("issueBoard");

        final GitHub closed = new GitHub();
        closed.state = "CLOSED";
        assertThatThrownBy(() -> closed.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6"))
                .as("a closed issue")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(closed.calls).as("only the read").containsExactly("issueBoard");

        final GitHub moved = new GitHub();
        moved.repository = "another-org/" + LIB;
        assertThatThrownBy(() -> moved.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6"))
                .as("an issue moved to another repository")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(moved.calls).as("only the read").containsExactly("issueBoard");
    }

    @Test
    void anIssueUnderAParentTheBoardDoesNotHoldIsRefusedNamingTheParent() {
        // Harm: the issue is added, then the board shows it only as a warned issue for its parent
        // outside the project, while the window was told it was saved.
        final GitHub github = new GitHub();
        github.parentId = "I_lib_9";
        final Snapshot current =
                snapshot(
                        List.of(offered(LIB, 6), offered(LIB, 9)),
                        List.of(
                                new Snapshot.Unlisted("I_lib_6", LIB, 6, "Made by hand", null),
                                new Snapshot.Unlisted("I_lib_9", LIB, 9, "Its parent", null)));

        assertThatThrownBy(() -> github.writer().addUnlistedIssue(current, SHOWN, LIB + "#6"))
                .as("an issue under a parent outside the project")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessage(
                        "Add its parent example-lib#9 to the project first; it is no item of it, so"
                                + " the board would show the issue only as a warned issue no work"
                                + " starts on (ParentOutsideProject).");
        assertThat(github.calls).as("only the read").containsExactly("issueBoard");
    }

    @Test
    void anIssueUnderAnIssueOfAnotherRepositoryIsRefusedBeforeAnyWrite() {
        // Harm: the issue is added, then the board shows it only as a warned issue for standing in
        // another repository than its parent issue.
        final GitHub github = new GitHub();
        github.parentId = "I_" + OWN + "_3";

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .addUnlistedIssue(
                                                offering(item(OWN, 3, null)), SHOWN, LIB + "#6"))
                .as("an issue under an issue of another repository")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("another repository");
        assertThat(github.calls).as("only the read").containsExactly("issueBoard");
    }

    @Test
    void anIssueBlockedByAStoryIsRefusedBeforeAnyWrite() {
        // Harm: the issue is added, then the board shows it only as a warned issue for waiting on a
        // story.
        final GitHub github = new GitHub();
        github.blockers =
                List.of(new GitHubGraphQl.IssueRef("I_" + OWN + "_1", 1L, 1, OWNER + "/" + OWN));

        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .addUnlistedIssue(
                                                offering(item(OWN, 1, IssueMapping.STORY_TYPE)),
                                                SHOWN,
                                                LIB + "#6"))
                .as("an issue blocked by a story")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("blocked by a story");
        assertThat(github.calls).as("only the read").containsExactly("issueBoard");
    }

    @Test
    void anIssueTheBoardHeldUnderTheLockNoLongerOffersIsRefusedBeforeAnyCall() {
        // Harm: a look between the caller's check and the save moved the issue onto the project,
        // and the add went ahead on the moved board, writing over a person's values.
        final GitHub github = new GitHub();
        final Snapshot moved =
                snapshot(
                        List.of(),
                        List.of(new Snapshot.Unlisted("I_lib_6", LIB, 6, "Made by hand", null)));

        assertThatThrownBy(() -> github.writer().addUnlistedIssue(moved, SHOWN, LIB + "#6"))
                .as("an issue the board does not offer")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThatThrownBy(
                        () ->
                                github.writer()
                                        .addUnlistedIssue(
                                                offering(), new Revision("r0"), LIB + "#6"))
                .as("a revision the board no longer holds")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(github.calls).as("no call").isEmpty();
    }

    @Test
    void aValueWriteThatFailsAfterTheAddIsPartlySavedNamingTheIssue() {
        // Harm: the add stood on the project, but a failed value write was reported as nothing
        // saved, so the window offered the issue again as if it were not added.
        final GitHub thrown = new GitHub();
        thrown.set =
                list -> {
                    throw GitHubExceptions.refused(502, "bad gateway");
                };
        assertThatThrownBy(() -> thrown.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6"))
                .as("a value write GitHub failed")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly ->
                                assertThat(partly.getMessage())
                                        .contains("GitHub holds " + LIB + "#6"));

        final GitHub refused = new GitHub();
        refused.set =
                list ->
                        new Batch<>(
                                List.of(Batch.Outcome.refused("FORBIDDEN")),
                                null,
                                GitHubExceptions.WITHOUT_A_WORD);
        assertThatThrownBy(() -> refused.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6"))
                .as("a value GitHub refused")
                .isInstanceOf(StoreException.PartlySaved.class);
    }

    @Test
    void anAddGitHubRefusedSavedNothingAndIsNoPartOfASave() {
        // Harm: an add GitHub refused was reported as partly saved, so the window told the person
        // the issue stands on the project although nothing was written.
        final GitHub github = new GitHub();
        github.add =
                ids ->
                        new Batch<>(
                                List.of(Batch.Outcome.refused("FORBIDDEN")),
                                null,
                                GitHubExceptions.WITHOUT_A_WORD);

        assertThatThrownBy(() -> github.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6"))
                .as("an add GitHub refused")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("did not add " + LIB + "#6");
        assertThat(github.calls)
                .as("no value written")
                .containsExactly("issueBoard", "addProjectItems");
    }

    @Test
    void anAddGitHubTookWithoutAnsweringMayStandAndIsPartlySaved() {
        // Harm: an add whose answer was lost was reported as nothing saved, though GitHub may hold
        // the issue on the project outside the workflow.
        final GitHub github = new GitHub();
        github.add =
                ids -> {
                    throw GitHubExceptions.unanswered("timed out");
                };

        assertThatThrownBy(() -> github.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6"))
                .as("an add without an answer")
                .isInstanceOf(StoreException.PartlySaved.class);
        assertThat(github.calls).as("no value written").doesNotContain("setFieldValues");
    }

    @Test
    void anIssueUnderAParentOrBlockerTheBoardDoesNotPlaceIsRefusedBeforeAnyWrite() {
        // Harm: the add was answered as saved, then the next read showed the issue only as a warned
        // issue for its parent being a sub-issue, or its parent or blocker standing off the board:
        // archived, listed apart outside the workflow or left out.
        final ItemView parent = item(LIB, 2, null);
        final ItemView top = item(LIB, 1, null);

        final GitHub deep = new GitHub();
        deep.parentId = parent.issueId();
        assertThatThrownBy(
                        () ->
                                deep.writer()
                                        .addUnlistedIssue(
                                                snapshot(
                                                        List.of(offered(LIB, 6)),
                                                        List.of(
                                                                new Snapshot.Unlisted(
                                                                        "I_lib_6",
                                                                        LIB,
                                                                        6,
                                                                        "Made by hand",
                                                                        null)),
                                                        List.of(
                                                                placed(LIB + "#1", null),
                                                                placed(LIB + "#2", LIB + "#1")),
                                                        top,
                                                        parent),
                                                SHOWN,
                                                LIB + "#6"))
                .as("an issue under a sub-issue")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("Its parent example-lib#2 is a sub-issue itself");
        assertThat(deep.calls).as("only the read").containsExactly("issueBoard");

        final GitHub off = new GitHub();
        off.parentId = parent.issueId();
        assertThatThrownBy(
                        () ->
                                off.writer()
                                        .addUnlistedIssue(
                                                snapshot(
                                                        List.of(offered(LIB, 6)),
                                                        List.of(
                                                                new Snapshot.Unlisted(
                                                                        "I_lib_6",
                                                                        LIB,
                                                                        6,
                                                                        "Made by hand",
                                                                        null)),
                                                        List.of(),
                                                        parent),
                                                SHOWN,
                                                LIB + "#6"))
                .as("an issue under an item off the board")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("Its parent example-lib#2 is not on the board");
        assertThat(off.calls).as("only the read").containsExactly("issueBoard");

        final GitHub blocked = new GitHub();
        blocked.blockers =
                List.of(new GitHubGraphQl.IssueRef(parent.issueId(), 2L, 2, OWNER + "/" + LIB));
        assertThatThrownBy(
                        () ->
                                blocked.writer()
                                        .addUnlistedIssue(
                                                snapshot(
                                                        List.of(offered(LIB, 6)),
                                                        List.of(
                                                                new Snapshot.Unlisted(
                                                                        "I_lib_6",
                                                                        LIB,
                                                                        6,
                                                                        "Made by hand",
                                                                        null)),
                                                        List.of(),
                                                        parent),
                                                SHOWN,
                                                LIB + "#6"))
                .as("an issue blocked by an item off the board")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("Its blocker example-lib#2 is not on the board");
        assertThat(blocked.calls).as("only the read").containsExactly("issueBoard");
    }

    @Test
    void aStoryThatBlocksAnIssueOnTheBoardIsRefusedBeforeAnyWrite() {
        // Harm: the story was added, then the next read showed the issue it blocks, a card at work
        // that waited on it while the story stood outside the project, only as a warned issue.
        final GitHub github = new GitHub();
        github.type = IssueMapping.STORY_TYPE;
        github.repository = OWNER + "/" + OWN;
        final Snapshot current =
                snapshot(
                        List.of(offered(OWN, 6)),
                        List.of(new Snapshot.Unlisted("I_own_6", OWN, 6, "Made by hand", null)),
                        blockedBy(item(LIB, 3, null), "I_own_6"));

        assertThatThrownBy(() -> github.writer().addUnlistedIssue(current, SHOWN, OWN + "#6"))
                .as("a story blocking a board issue")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("It blocks example-lib#3");
        assertThat(github.calls).as("only the read").containsExactly("issueBoard");
    }

    @Test
    void anIssueWithMoreBlockersThanOneReadNamesIsRefused() {
        // Harm: a blocker past the first page, a story or an item off the board, was never checked,
        // and the add was answered as saved while the next read showed the issue only as a warned
        // issue.
        final GitHub github = new GitHub();
        github.moreBlockers = true;

        assertThatThrownBy(() -> github.writer().addUnlistedIssue(offering(), SHOWN, LIB + "#6"))
                .as("blockers past one page")
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(github.calls).as("only the read").containsExactly("issueBoard");
    }
}

package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.assertj.core.api.SoftAssertions;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Dependencies;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/** Rule dependencies can change without changing a target's own stored version. */
class RulesTest {

    private static Issue issue(
            final String id, final String state, final List<String> blockers, final String parent) {
        return new Issue(id, "sample", "Issue", state, null, blockers, parent, null, "");
    }

    private static Story story(final List<String> issues, final boolean whole) {
        return new Story("ST1", "Story", "Idea", "High", null, issues, "", null, whole);
    }

    private static Board board(final List<Issue> issues, final List<Story> stories) {
        final WorkRule work =
                new WorkRule(
                        List.of("Idea"),
                        "Ready",
                        null,
                        null,
                        WorkRule.Per.REPOSITORY,
                        List.of("work"),
                        "Work",
                        Map.of("agent", "model"));
        return new Board(
                new Revision("live"),
                new Workflow(
                        List.of("Idea", "Building", "Ready", "Finished", "Rejected"),
                        List.of(),
                        List.of("Finished", "Rejected")),
                stories,
                issues,
                new Work(Map.of("Building", work), List.of()),
                List.of("sample", "other"),
                List.of(),
                null,
                List.of(
                        unlisted("sample#7", UnlistedIssue.Kind.ISSUE),
                        unlisted("sample#8", UnlistedIssue.Kind.STORY),
                        unlisted("other#9", UnlistedIssue.Kind.STORY)));
    }

    private static UnlistedIssue unlisted(final String id, final UnlistedIssue.Kind kind) {
        final String repository = id.substring(0, id.indexOf('#'));
        return new UnlistedIssue(
                id,
                repository,
                "Offered",
                kind,
                URI.create(
                        "https://example.org/"
                                + repository
                                + "/issues/"
                                + id.substring(id.indexOf('#') + 1)));
    }

    private record Live(Board board, List<Double> rankValues, UnlistedLinks links)
            implements Dependencies {
        @Override
        public List<Double> ranks(final boolean story, final String priority) {
            return story && priority.equals("High") ? rankValues : List.of();
        }

        @Override
        public UnlistedLinks unlisted(final String issue) {
            return links;
        }
    }

    private static Live live(final Board board) {
        return new Live(
                board,
                List.of(),
                new Dependencies.UnlistedLinks(null, List.of(), true, Set.of(), Set.of()));
    }

    @Test
    void aLiveBlockerIsCheckedWithoutBecomingAVersionTarget() {
        // Harm: an unchanged issue starts work while its blocker has reopened.
        final Issue target = issue("AB1", "Idea", List.of("AB2"), null);
        final Board now = board(List.of(target, issue("AB2", "Idea", List.of(), null)), List.of());
        assertThatCode(
                        () ->
                                Edits.unchanged(
                                        now,
                                        Basis.none().with("AB1", target.version()),
                                        Targets.of(now, new Operation.SetWorker("AB1"))))
                .as("the blocker is a dependency rather than a version target")
                .doesNotThrowAnyException();
        assertThat(Rules.openBlockers(live(now), "AB1"))
                .as("the live blocker is still enforced")
                .containsExactly("AB2");
        final Board done =
                board(List.of(target, issue("AB2", "Finished", List.of(), null)), List.of());
        assertThat(Rules.openBlockers(live(done), "AB1"))
                .as("the live closed blocker releases work")
                .isEmpty();
    }

    @Test
    void cyclesAndBlockerEligibilityUseTheCurrentGraph() {
        // Harm: an edit creates a deadlocked blocker cycle or links work to an ineligible item.
        final Live current =
                live(
                        board(
                                List.of(
                                        issue("AB1", "Idea", List.of(), null),
                                        issue("AB2", "Idea", List.of("AB1"), null)),
                                List.of()));
        assertThatThrownBy(() -> Rules.blockedBy(current, "AB1", List.of("AB2")))
                .as("the live graph leads back to the edited issue")
                .isInstanceOf(Moves.Refused.class);
        assertThatThrownBy(() -> Rules.blockedBy(current, "AB1", List.of("AB9")))
                .as("an unknown item is not an eligible blocker")
                .isInstanceOf(Moves.Refused.class);
    }

    @Test
    void theBlockerLimitRejectsAnOtherwiseEligibleAcyclicList() {
        // Harm: a blocker list beyond the source's limit is partly written or silently truncated.
        final List<String> blockers =
                IntStream.rangeClosed(2, 52).mapToObj(number -> "AB" + number).toList();
        final List<Issue> issues = new ArrayList<>();
        issues.add(issue("AB1", "Idea", List.of(), null));
        blockers.forEach(id -> issues.add(issue(id, "Idea", List.of(), null)));
        final Live current = live(board(issues, List.of()));
        assertThat(Rules.blockedBy(current, "AB1", blockers.subList(0, 50)))
                .as("fifty unique acyclic eligible blockers are allowed")
                .isTrue();
        assertThatThrownBy(() -> Rules.blockedBy(current, "AB1", blockers))
                .as("the fifty-first blocker exceeds the limit independently of other rules")
                .isInstanceOf(Moves.Refused.class)
                .hasMessageContaining("at most 50");
    }

    @Test
    void ranksFollowTheLiveMaximumOfTheSameKindAndPriority() {
        // Harm: a new item is ranked using an old or unrelated group's maximum.
        final Board board = board(List.of(), List.of());
        final Live current =
                new Live(
                        board,
                        List.of(2.0, 9.9, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 100000.0),
                        live(board).links());
        assertThat(Rules.nextRank(current, true, "High"))
                .as("valid live ranks give the next rank")
                .isEqualTo(10);
        assertThat(Rules.nextRank(current, false, "High"))
                .as("issues rank independently")
                .isEqualTo(1);
        assertThat(Rules.nextRank(current, true, "Low"))
                .as("priorities rank independently")
                .isEqualTo(1);
        assertThat(Rules.nextRank(new Live(board, List.of(99999.0), current.links()), true, "High"))
                .as("the rank stays within the field's limit")
                .isEqualTo(99999);
    }

    @Test
    void anIdeaCannotNameTwoIssuesInOneRepository() {
        // Harm: an idea creates competing work for the same repository.
        final NewIdeas.Issue issue =
                new NewIdeas.Issue("sample", "Title", "It works", "S", "Agent");
        final NewIdeas.Idea idea =
                new NewIdeas.Idea(
                        new NewIdeas.Story("Story", "Gain", "Test", "High"), List.of(issue, issue));
        assertThatThrownBy(() -> Rules.idea(live(board(List.of(), List.of())), idea))
                .as("the one-repository rule applies on the supplied dependencies")
                .isInstanceOf(WorkRefusal.class);
        assertThat(
                        Rules.idea(
                                        live(board(List.of(), List.of())),
                                        new NewIdeas.Idea(idea.story(), List.of(issue)))
                                .issues())
                .as("one issue in the repository is allowed")
                .hasSize(1);
    }

    @Test
    void aStoryIsDerivedFromItsLiveIssuesRatherThanItsHeldState() {
        // Harm: a story is saved with a state derived from outdated or incomplete issue states.
        final Board current =
                board(
                        List.of(issue("AB1", "Ready", List.of(), null)),
                        List.of(story(List.of("AB1"), true)));
        assertThat(Rules.derivedState(live(current), "ST1", states -> states.getFirst()))
                .as("the issue supplies the derived state")
                .isEqualTo("Ready");
        final Board missing = board(List.of(), List.of(story(List.of("AB1"), true)));
        assertThatThrownBy(() -> Rules.derivedState(live(missing), "ST1", states -> "Finished"))
                .as("an absent issue cannot be ignored")
                .isInstanceOf(StoreException.NotSavable.class);
        final Board partial = board(current.issues(), List.of(story(List.of("AB1"), false)));
        assertThatThrownBy(() -> Rules.derivedState(live(partial), "ST1", states -> "Finished"))
                .as("a partial issue list cannot produce a state")
                .isInstanceOf(StoreException.NotSavable.class);
    }

    @Test
    void aSubIssueTakesItsLiveParentIssuesWorkerAndRisk() {
        // Harm: a child is assigned to the wrong worker or worked with less care than its parent.
        final Issue child =
                issue("AB1.1", "Idea", List.of(), "AB1")
                        .withWorkerAndRisk(Worker.AGENT, Risk.SMALL);
        final Issue parent =
                issue("AB1", "Idea", List.of(), null).withWorkerAndRisk(Worker.MANUAL, Risk.HARM);
        final Issue inherited =
                Rules.inherited(live(board(List.of(parent, child), List.of())), child);
        assertThat(inherited.worker())
                .as("the parent issue supplies the worker")
                .isEqualTo(Worker.MANUAL);
        assertThat(inherited.risk()).as("the parent's higher risk is kept").isEqualTo(Risk.HARM);
        assertThat(inherited.version())
                .as("inheritance does not change the child's stored basis")
                .isEqualTo(child.version());
        assertThatThrownBy(() -> Rules.inherited(live(board(List.of(child), List.of())), child))
                .as("an unreadable parent cannot be guessed")
                .isInstanceOf(StoreException.NotSavable.class);
    }

    @Test
    void assigningAnAgentChecksTheLiveStatesOfSubIssues() {
        // Harm: an agent inherits work already in a working state without any task to finish it.
        final Issue parent =
                issue("AB1", "Idea", List.of(), null)
                        .withWorkerAndRisk(Worker.MANUAL, Risk.ORDINARY);
        final Issue child = issue("AB1.1", "Building", List.of(), "AB1");
        assertThatThrownBy(
                        () ->
                                Rules.worker(
                                        live(board(List.of(parent, child), List.of())),
                                        "AB1",
                                        Worker.AGENT))
                .as("the live working child prevents the reassignment")
                .isInstanceOf(Moves.Refused.class);
        final Issue waiting = issue("AB1.1", "Idea", List.of(), "AB1");
        assertThat(
                        Rules.worker(
                                        live(board(List.of(parent, waiting), List.of())),
                                        "AB1",
                                        Worker.AGENT)
                                .workers())
                .as("a child in a waiting state permits the reassignment")
                .containsExactlyEntriesOf(Map.of("AB1", Worker.AGENT));
    }

    @Test
    void movesCheckLiveParentsAndCompletion() {
        // Harm: a move closes a parent over open work or completes a story before its issues are
        // done.
        final Board current =
                board(
                        List.of(
                                issue("AB1", "Idea", List.of(), null),
                                issue("AB1.1", "Idea", List.of(), "AB1")),
                        List.of(story(List.of("AB1"), true)));
        assertThatThrownBy(
                        () ->
                                Rules.moves(
                                        live(current),
                                        List.of(new Moves.Move(false, "AB1", "Finished")),
                                        (issue, stories) -> false))
                .as("a parent cannot close while its child remains open")
                .isInstanceOf(Moves.Refused.class)
                .hasMessageContaining("AB1.1");
        assertThatThrownBy(
                        () ->
                                Rules.moves(
                                        live(current),
                                        List.of(new Moves.Move(true, "ST1", "Finished")),
                                        (issue, stories) -> false))
                .as("a story cannot complete over unfinished issues")
                .isInstanceOf(Moves.Refused.class)
                .hasMessageContaining("AB1:Idea");
    }

    @Test
    void unlistedParentAndBlockerEligibilityComeFromLiveLinks() {
        // Harm: adding an unlisted issue creates a parent or blocker link the planner cannot work.
        final Board current =
                board(
                        List.of(
                                issue("AB1", "Idea", List.of(), null),
                                issue("AB1.1", "Idea", List.of(), "AB1")),
                        List.of(story(List.of("AB1"), true)));
        final Dependencies.UnlistedLinks allowed =
                new Dependencies.UnlistedLinks(
                        "AB1",
                        List.of("elsewhere#1"),
                        true,
                        Set.of("AB1", "AB1.1", "ST1"),
                        Set.of());
        assertThatCode(() -> Rules.unlisted(new Live(current, List.of(), allowed), "sample#7"))
                .as("an eligible parent and an outside blocker are allowed")
                .doesNotThrowAnyException();
        for (final Dependencies.UnlistedLinks links :
                List.of(
                        new Dependencies.UnlistedLinks(
                                "AB1.1", List.of(), true, Set.of(), Set.of()),
                        new Dependencies.UnlistedLinks("AB9", List.of(), true, Set.of(), Set.of()),
                        new Dependencies.UnlistedLinks(
                                null, List.of("ST1"), true, Set.of("ST1"), Set.of()),
                        new Dependencies.UnlistedLinks(
                                null, List.of("AB9"), true, Set.of("AB9"), Set.of()),
                        new Dependencies.UnlistedLinks(
                                null, List.of(), false, Set.of(), Set.of()))) {
            assertThatThrownBy(
                            () -> Rules.unlisted(new Live(current, List.of(), links), "sample#7"))
                    .as("ineligible or incomplete live links: %s", links)
                    .isInstanceOf(StoreException.NotSavable.class);
        }
    }

    @Test
    void aLoweredParentLowersTheRiskItsChildAlreadyInheritedOnTheBoard() {
        // Harm: a child keeps the care of a risk its parent no longer has, or of one the parent
        // gave it before, because the board's inherited value is mixed into the live one.
        final Issue lowered =
                issue("AB1", "Idea", List.of(), null).withWorkerAndRisk(Worker.AGENT, Risk.SMALL);
        final Issue shown =
                issue("AB1.1", "Idea", List.of(), "AB1")
                        .withWorkerAndRisk(Worker.MANUAL, Risk.HARM);
        final Issue inherited =
                Rules.inherited(live(board(List.of(lowered, shown), List.of())), shown);
        assertThat(inherited.risk())
                .as("the lowered parent's current risk, not the child's board value")
                .isEqualTo(Risk.SMALL);
        assertThat(inherited.worker()).as("the parent's current worker").isEqualTo(Worker.AGENT);
    }

    @Test
    void aWarningThatHoldsWorkDoesNotStopDerivingTheStorysState() {
        // Harm: a story whose issue carries a warning is refused a state its store derives, so a
        // save writes a state the board does not show.
        final Issue held =
                issue("AB1", "Ready", List.of(), null)
                        .withWarnings(List.of(Warning.unknownWorker()));
        final Board current = board(List.of(held), List.of(story(List.of("AB1"), true)));
        assertThat(Rules.derivedState(live(current), "ST1", states -> states.getFirst()))
                .as("the issue's state counts although a warning holds its work")
                .isEqualTo("Ready");
        final Issue outside =
                issue("AB1", "Odd", List.of(), null)
                        .withWarnings(List.of(Warning.stateOutsideWorkflow()));
        final Board odd = board(List.of(outside), List.of(story(List.of("AB1"), true)));
        assertThatThrownBy(() -> Rules.derivedState(live(odd), "ST1", states -> "Finished"))
                .as("a state outside the workflow still gives no state")
                .isInstanceOf(StoreException.NotSavable.class);
    }

    @Test
    void anIdNamingBothAStoryAndAnIssueIsRefusedByEveryRule() {
        // Harm: a rule silently picks the story or the issue behind a shared id and checks or
        // derives from the wrong item.
        final Issue issue = issue("sample#1", "Idea", List.of(), null);
        final Issue child = issue("sample#2", "Idea", List.of(), "sample#1");
        final Issue other = issue("AB2", "Idea", List.of(), null);
        final Story story =
                new Story(
                        "sample#1", "Story", "Idea", "High", null, List.of("AB2"), "", null, true);
        final Board shared = board(List.of(issue, child, other), List.of(story));
        final Live current =
                new Live(
                        shared,
                        List.of(),
                        new Dependencies.UnlistedLinks(
                                "sample#1", List.of(), true, Set.of(), Set.of()));
        final SoftAssertions softly = new SoftAssertions();
        softly.assertThatThrownBy(() -> Rules.openBlockers(current, "sample#1"))
                .as("open blockers")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("both a story and an issue");
        softly.assertThatThrownBy(() -> Rules.blockedBy(current, "AB2", List.of("sample#1")))
                .as("a blocker list")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("both a story and an issue");
        softly.assertThatThrownBy(() -> Rules.derivedState(current, "sample#1", states -> "Idea"))
                .as("a derived state")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("both a story and an issue");
        softly.assertThatThrownBy(() -> Rules.inherited(current, child))
                .as("an inherited worker and risk")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("both a story and an issue");
        softly.assertThatThrownBy(() -> Rules.worker(current, "sample#1", Worker.AGENT))
                .as("a worker change")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("both a story and an issue");
        softly.assertThatThrownBy(
                        () ->
                                Rules.moves(
                                        current,
                                        List.of(new Moves.Move(false, "sample#1", "Building")),
                                        (id, stories) -> false))
                .as("a move")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("both a story and an issue");
        softly.assertThatThrownBy(() -> Rules.unlisted(current, "sample#7"))
                .as("an unlisted issue's parent")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("both a story and an issue");
        softly.assertAll();
    }

    @Test
    void unlistedLinksOutsideWhatThePlannerCanWorkAreRefused() {
        // Harm: adding an unlisted item creates a story in another repository, a story that blocks
        // work, a parent across repositories, a self-block or more blockers than the source keeps.
        final Issue foreign =
                new Issue("other#1", "other", "Issue", "Idea", null, List.of(), null, null, "");
        final List<String> fifty =
                IntStream.rangeClosed(1, 50).mapToObj(number -> "elsewhere#" + number).toList();
        final List<String> fiftyOne =
                IntStream.rangeClosed(1, 51).mapToObj(number -> "elsewhere#" + number).toList();
        final Board current =
                board(
                        List.of(issue("AB1", "Idea", List.of(), null), foreign),
                        List.of(story(List.of("AB1"), true)));
        final SoftAssertions softly = new SoftAssertions();
        softly.assertThatCode(
                        () ->
                                Rules.unlisted(
                                        new Live(
                                                current,
                                                List.of(),
                                                new Dependencies.UnlistedLinks(
                                                        null, fifty, true, Set.of(), Set.of())),
                                        "sample#7"))
                .as("fifty outside blockers are within the limit")
                .doesNotThrowAnyException();
        softly.assertThatCode(
                        () ->
                                Rules.unlisted(
                                        new Live(
                                                current,
                                                List.of(),
                                                new Dependencies.UnlistedLinks(
                                                        null, List.of(), true, Set.of(), Set.of())),
                                        "sample#8"))
                .as("a story of the own repository that blocks nothing is allowed")
                .doesNotThrowAnyException();
        final Map<String, Map.Entry<String, Dependencies.UnlistedLinks>> refused =
                Map.of(
                        "a story outside the own repository",
                        Map.entry(
                                "other#9",
                                new Dependencies.UnlistedLinks(
                                        null, List.of(), true, Set.of(), Set.of())),
                        "a story that blocks a project item",
                        Map.entry(
                                "sample#8",
                                new Dependencies.UnlistedLinks(
                                        null, List.of(), true, Set.of("AB1"), Set.of("AB1"))),
                        "a parent issue in another repository",
                        Map.entry(
                                "sample#7",
                                new Dependencies.UnlistedLinks(
                                        "other#1", List.of(), true, Set.of(), Set.of())),
                        "an issue blocking itself",
                        Map.entry(
                                "sample#7",
                                new Dependencies.UnlistedLinks(
                                        null, List.of("sample#7"), true, Set.of(), Set.of())),
                        "fifty-one blockers",
                        Map.entry(
                                "sample#7",
                                new Dependencies.UnlistedLinks(
                                        null, fiftyOne, true, Set.of(), Set.of())));
        refused.forEach(
                (name, asked) ->
                        softly.assertThatThrownBy(
                                        () ->
                                                Rules.unlisted(
                                                        new Live(
                                                                current,
                                                                List.of(),
                                                                asked.getValue()),
                                                        asked.getKey()))
                                .as(name)
                                .isInstanceOf(StoreException.NotSavable.class));
        softly.assertAll();
    }

    @Test
    void aViewNotGivenRanksOrUnlistedLinksRefusesARuleThatAsksForThem() {
        // Harm: a view that never read the ranks gives a new item rank 1 among ranked items, or
        // one that never read an unlisted issue's links lets it in unchecked.
        final Dependencies boardOnly = Dependencies.of(board(List.of(), List.of()));
        assertThatThrownBy(() -> Rules.nextRank(boardOnly, true, "High"))
                .as("no rank is made up from ranks never read")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Rules.unlisted(boardOnly, "sample#7"))
                .as("no unlisted issue is checked against links never read")
                .isInstanceOf(IllegalStateException.class);
    }
}

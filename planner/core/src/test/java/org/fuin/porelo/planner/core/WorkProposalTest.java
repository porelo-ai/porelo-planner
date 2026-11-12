package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WorkProposalTest {

    @Test
    void eachRepositoryGetsItsOwnMainAndOnlyItsOpenSubIssues() {
        // Tasks in the wrong repository or for closed work can change unrelated code.
        final WorkProposal proposal =
                WorkProposal.propose(board(WorkRule.Per.REPOSITORY), "ST1", "Building");
        assertThat(proposal.repositories())
                .as("repository groups in story order")
                .extracting(WorkProposal.Repository::repository)
                .containsExactly("alpha", "beta");
        assertThat(proposal.repositories().getFirst().issues())
                .as("parent and open child named once")
                .containsExactly("AA1", "AA1.1");
        assertThat(proposal.repositories().getFirst().subAgents())
                .as("one builder, with no reader or closed child")
                .extracting(WorkProposal.Assignment::issue)
                .containsExactly("AA1.1");
        final WorkProposal.Assignment sub =
                proposal.repositories().getFirst().subAgents().getFirst();
        assertThat(sub.kind()).as("only implementation starts now").isEqualTo("BUILDER");
        assertThat(sub.choice()).as("known participants default to retention").isEqualTo("KEEP");
        assertThat(sub.brief())
                .as("only identity, title and acceptance paragraph enter the brief")
                .contains("AA1.1", "Open child", "Keep the data.")
                .doesNotContain("Private notes", "SECRET DESCRIPTION");
        assertThat(proposal.modes())
                .as("mode preference stays ordered")
                .containsExactly("AGENT", "SHELL");
        assertThat(proposal.repositories())
                .as("model preference follows file order rather than lexical order")
                .allSatisfy(
                        repo -> {
                            assertThat(repo.main().agent())
                                    .as("first offered agent")
                                    .isEqualTo("zeta");
                            assertThat(repo.main().model())
                                    .as("model of the first agent")
                                    .isEqualTo("large");
                        });
        assertThat(proposal.comment()).as("the operator has not commented").isEmpty();
        assertThat(proposal.readingRounds()).as("the default reading bound").isEqualTo(2);
        assertThat(proposal.issueReadingRounds()).as("no unsolicited overrides").isEmpty();
        assertThat(proposal.coordinator())
                .as("no runtime coordinator is invented")
                .isEqualTo("START");
        assertThat(WorkProposal.propose(board(WorkRule.Per.REPOSITORY), "ST1", "Building"))
                .as("identities survive recomputation at the same revision")
                .isEqualTo(proposal);
    }

    @Test
    void openChildrenRemainWorkWhenTheirNamedParentIsClosed() {
        // A closed parent's state must not silently discard an open sub-issue's work.
        final Board original = board(WorkRule.Per.REPOSITORY);
        final Board board =
                new Board(
                        original.revision(),
                        original.workflow(),
                        List.of(
                                new Story(
                                        "ST1",
                                        "Story",
                                        "Backlog",
                                        true,
                                        "High",
                                        List.of("AA1", "AA1.1"),
                                        "",
                                        null)),
                        List.of(
                                issue("AA1", "alpha", "Closed parent", "Finished", null, ""),
                                issue("AA1.1", "alpha", "Open child", "Backlog", "AA1", "")),
                        original.work(),
                        original.repositories());
        final WorkProposal.Repository repository =
                WorkProposal.propose(board, "ST1", "Building").repositories().getFirst();
        assertThat(repository.issues())
                .as("only the open child remains in scope")
                .containsExactly("AA1.1");
        assertThat(repository.subAgents())
                .as("the child still has its own builder")
                .extracting(WorkProposal.Assignment::issue)
                .containsExactly("AA1.1");
    }

    @Test
    void theMainProposalShowsTheRulesOrderWithinTheBriefBound() {
        // The operator must see the rule's instructions before accepting a participant.
        final Board board = board(WorkRule.Per.STORY);
        assertThat(
                        WorkProposal.propose(board, "ST1", "Building")
                                .repositories()
                                .getFirst()
                                .main()
                                .brief())
                .as("the proposed main carries the configured order")
                .isEqualTo(board.work().rules().get("Building").order());
        final WorkRule rule = board.work().rules().get("Building");
        final WorkRule large =
                new WorkRule(
                        rule.from(),
                        rule.done(),
                        null,
                        null,
                        rule.per(),
                        rule.modes(),
                        "x".repeat(4001),
                        rule.models());
        final Board oversized =
                new Board(
                        board.revision(),
                        board.workflow(),
                        board.stories(),
                        board.issues(),
                        new Work(Map.of("Building", large), List.of()),
                        board.repositories());
        assertThatThrownBy(() -> WorkProposal.propose(oversized, "ST1", "Building"))
                .as("the main's brief has the same bound as helpers")
                .isInstanceOf(WorkRefusal.class);
    }

    @Test
    void aLongMultilineAcceptanceParagraphIsRefusedWithoutTakingTheStack() {
        // A repository paragraph must produce a bounded refusal instead of crashing the planner.
        final Board original = board(WorkRule.Per.STORY);
        final Board board =
                new Board(
                        original.revision(),
                        original.workflow(),
                        original.stories(),
                        List.of(
                                issue(
                                        "AA1",
                                        "alpha",
                                        "Parent",
                                        "Backlog",
                                        null,
                                        "**What must be true.** Keep\n" + "a\n".repeat(20000)),
                                issue("AA1.1", "alpha", "Child", "Backlog", "AA1", ""),
                                issue("BB1", "beta", "Other", "Backlog", null, ""),
                                issue("CC1", "alpha", "Closed", "Finished", null, "")),
                        original.work(),
                        original.repositories());
        assertThatThrownBy(() -> WorkProposal.propose(board, "ST1", "Building"))
                .as("long multiline criteria are refused through the work contract")
                .isInstanceOf(WorkRefusal.class);
    }

    @Test
    void storyWorkUsesTheOwnRepositoryEvenWhenItHasNoIssuesThere() {
        // Picking an issue's repository for story work starts the main task in the wrong checkout.
        final WorkProposal proposal =
                WorkProposal.propose(board(WorkRule.Per.STORY), "ST1", "Building");
        assertThat(proposal.repositories()).as("one story task").hasSize(1);
        assertThat(proposal.repositories().getFirst().repository())
                .as("the explicitly named own repository")
                .isEqualTo("project");
        assertThat(proposal.repositories().getFirst().issues())
                .as("all open issues once")
                .containsExactly("AA1", "AA1.1", "BB1");
    }

    @Test
    void onlyAnExplicitRunRecordChangesTheCoordinatorProposal() {
        // Inferring a run from a state would tell a coordinator that does not exist.
        final WorkProposal.RunningWork runs =
                new WorkProposal.RunningWork() {
                    /** Supplies no active task for this scenario. */
                    @Override
                    public List<String> tasks(final String story) {
                        return List.of();
                    }

                    /** Supplies an existing coordinator independently of story state. */
                    @Override
                    public boolean coordinatorActive() {
                        return true;
                    }
                };
        assertThat(
                        WorkProposal.propose(board(WorkRule.Per.STORY), "ST1", "Building", runs)
                                .coordinator())
                .as("the coordinator supplied by the run")
                .isEqualTo("TELL");
    }

    @Test
    void missingStoriesAndUnknownOrDisallowedTargetsCannotProduceWork() {
        // A missing or unsound rule must never authorize tasks.
        final Board board = board(WorkRule.Per.STORY);
        assertThatThrownBy(() -> WorkProposal.propose(board, "ST9", "Building"))
                .as("an absent story")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        error ->
                                assertThat(((WorkRefusal) error).name())
                                        .as("typed absence")
                                        .isEqualTo("NoSuchStory"));
        assertThatThrownBy(() -> WorkProposal.propose(board, "ST1", "secret target"))
                .as("unchecked target text is not echoed")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        error ->
                                assertThat(((WorkRefusal) error).parameters())
                                        .as("fixed invalid settings detail")
                                        .containsOnlyKeys("detail")
                                        .doesNotContainValue("secret target"));
        assertThatThrownBy(() -> WorkProposal.propose(board, "ST1", "Review"))
                .as("a known waiting target has no rule")
                .isInstanceOf(WorkRefusal.class);
        final Board wrongFrom =
                new Board(
                        board.revision(),
                        board.workflow(),
                        List.of(
                                new Story(
                                        "ST1",
                                        "Story",
                                        "Review",
                                        true,
                                        "High",
                                        List.of("AA1"),
                                        "",
                                        null)),
                        board.issues(),
                        board.work(),
                        board.repositories());
        assertThatThrownBy(() -> WorkProposal.propose(wrongFrom, "ST1", "Building"))
                .as("the current state must be allowed by from")
                .isInstanceOf(WorkRefusal.class);
        final WorkRule bad =
                new WorkRule(
                        List.of("Backlog"),
                        "Unknown",
                        null,
                        null,
                        WorkRule.Per.STORY,
                        List.of("AGENT"),
                        "Build",
                        Map.of("agent", "model"));
        final Board unsound =
                new Board(
                        board.revision(),
                        board.workflow(),
                        board.stories(),
                        board.issues(),
                        new Work(Map.of("Building", bad), List.of()),
                        board.repositories());
        assertThatThrownBy(() -> WorkProposal.propose(unsound, "ST1", "Building"))
                .as("an unsound rule is never used")
                .isInstanceOf(WorkRefusal.class);
    }

    @Test
    void missingIssuesAndRepositoryWorkWithoutIssuesAreRefused() {
        // Silently omitting an issue would lose work the story requires.
        final Board original = board(WorkRule.Per.REPOSITORY);
        final Board missing =
                new Board(
                        original.revision(),
                        original.workflow(),
                        original.stories(),
                        List.of(),
                        original.work(),
                        original.repositories());
        assertThatThrownBy(() -> WorkProposal.propose(missing, "ST1", "Building"))
                .as("all story references must resolve")
                .isInstanceOf(WorkRefusal.class);
        final Board empty =
                new Board(
                        original.revision(),
                        original.workflow(),
                        List.of(
                                new Story(
                                        "ST1", "Empty", "Backlog", true, "High", List.of(), "",
                                        null)),
                        List.of(),
                        original.work(),
                        original.repositories());
        assertThatThrownBy(() -> WorkProposal.propose(empty, "ST1", "Building"))
                .as("repository work needs a repository with open work")
                .isInstanceOf(WorkRefusal.class);
        final Board wholeStory =
                new Board(
                        empty.revision(),
                        empty.workflow(),
                        empty.stories(),
                        empty.issues(),
                        board(WorkRule.Per.STORY).work(),
                        empty.repositories());
        assertThat(
                        WorkProposal.propose(wholeStory, "ST1", "Building")
                                .repositories()
                                .getFirst()
                                .repository())
                .as("a story without issues still uses its own repository")
                .isEqualTo("project");
    }

    @Test
    void aSubIssueTheStoryNamesWithoutItsParentIsWorkedAsAnIssueOfItsOwn() {
        // Skipped for having a parent outside the story, its work would silently go undone.
        final Board original = board(WorkRule.Per.REPOSITORY);
        final Story story = original.stories().getFirst();
        final Board board =
                new Board(
                        original.revision(),
                        original.workflow(),
                        List.of(
                                new Story(
                                        story.id(),
                                        story.title(),
                                        story.state(),
                                        story.stateGiven(),
                                        story.priority(),
                                        List.of("AA1.1", "BB1"),
                                        story.description(),
                                        null)),
                        original.issues(),
                        original.work(),
                        original.repositories());

        final WorkProposal proposal = WorkProposal.propose(board, "ST1", "Building");

        assertThat(proposal.repositories())
                .as("the sub-issue's repository works it")
                .anySatisfy(
                        repository ->
                                assertThat(repository.issues())
                                        .as("named once")
                                        .containsExactly("AA1.1"));
    }

    @Test
    void aMixedStoryProposesOnlyItsAgentIssuesAndNamesThoseAPersonWorks() {
        // An agent's task over an issue a person works would change what that person is doing.
        final Board board = ManualIssues.of(board(WorkRule.Per.REPOSITORY), "BB1");

        final WorkProposal proposal = WorkProposal.propose(board, "ST1", "Building");

        assertThat(proposal.repositories())
                .as("only the repository with an agent's issue gets a task")
                .extracting(WorkProposal.Repository::repository)
                .containsExactly("alpha");
        assertThat(proposal.repositories())
                .as("no task names the issue a person works")
                .flatExtracting(WorkProposal.Repository::issues)
                .containsExactly("AA1", "AA1.1");
        assertThat(proposal.manual())
                .as("the issue a person works is named, so the window can say so")
                .containsExactly("BB1");
    }

    @Test
    void aSubIssueIsLeftOutWithItsParentWhenAPersonWorksIt() {
        // A builder for a sub-issue of a person's issue would be an agent working that issue.
        final Board board = ManualIssues.of(board(WorkRule.Per.REPOSITORY), "AA1");

        final WorkProposal proposal = WorkProposal.propose(board, "ST1", "Building");

        assertThat(proposal.repositories())
                .as("only the other repository's agent issue gets a task")
                .flatExtracting(WorkProposal.Repository::issues)
                .containsExactly("BB1");
        assertThat(proposal.repositories())
                .as("no builder for the sub-issue")
                .flatExtracting(WorkProposal.Repository::subAgents)
                .isEmpty();
        assertThat(proposal.manual())
                .as("parent and sub-issue named")
                .containsExactly("AA1", "AA1.1");
    }

    @Test
    void aStoryWhoseOpenIssuesAPersonWorksAllProposesNoTask() {
        // Story-wide work over issues people work would hand them to an agent all the same.
        for (final WorkRule.Per per : WorkRule.Per.values()) {
            final Board board = ManualIssues.of(board(per), "AA1", "BB1");

            assertThatThrownBy(() -> WorkProposal.propose(board, "ST1", "Building"))
                    .as("no task for a story only people work, per " + per)
                    .isInstanceOf(WorkRefusal.class)
                    .hasMessageContaining("AA1")
                    .hasMessageContaining("AA1.1")
                    .hasMessageContaining("BB1")
                    .extracting(e -> ((WorkRefusal) e).name())
                    .isEqualTo("WorkNotAllowed");
        }
    }

    @Test
    void aPersonsIssueFurtherBackDoesNotHoldBackTheAgentsWork() {
        // A story held in Idea by a person's issue would never start its agent's ready issue.
        final Board board = staggered(Worker.AGENT, Worker.MANUAL);

        final WorkProposal proposal = WorkProposal.propose(board, "ST1", "Building");

        assertThat(proposal.repositories())
                .as("the agent's issue in Backlog gets its task")
                .flatExtracting(WorkProposal.Repository::issues)
                .containsExactly("AA1");
        assertThat(proposal.manual()).as("the person's issue is named").containsExactly("BB1");
    }

    @Test
    void anAgentsIssueFurtherBackStillHoldsTheStoryBack() {
        // An agent's issue started from a state its rule does not allow skips the work before it.
        final Board board = staggered(Worker.MANUAL, Worker.AGENT);

        assertThatThrownBy(() -> WorkProposal.propose(board, "ST1", "Building"))
                .as("the agent's issue in Idea is not ready for Building")
                .isInstanceOf(WorkRefusal.class);
    }

    @Test
    void aRepositoryOfASmallAndAHarmIssueIsProposedHarmsChoiceAndEachHelperItsOwn() {
        // A main proposed the light choice of the small issue beside one that can do harm would
        // build the harmful change carelessly.
        final Board board =
                risked(
                        board(WorkRule.Per.REPOSITORY),
                        Map.of("AA1", Risk.SMALL, "AA1.1", Risk.HARM, "BB1", Risk.SMALL));
        final WorkProposal proposal = WorkProposal.propose(board, "ST1", "Building");
        final WorkProposal.Main alpha = proposal.repositories().getFirst().main();
        assertThat(List.of(alpha.agent(), alpha.model()))
                .as("the main of a repository holding a harm issue takes harm's model")
                .containsExactly("omega", "huge");
        assertThat(alpha.effort()).as("and harm's effort").isEqualTo("max");
        assertThat(alpha.basis()).as("named as harm's").isEqualTo("Building, risk harm");
        final WorkProposal.Assignment builder =
                proposal.repositories().getFirst().subAgents().getFirst();
        assertThat(List.of(builder.issue(), builder.model(), builder.basis()))
                .as("the harm sub-issue's builder takes harm's choice")
                .containsExactly("AA1.1", "huge", "Building, risk harm");
        final WorkProposal.Main beta = proposal.repositories().get(1).main();
        assertThat(List.of(beta.agent(), beta.model(), beta.basis()))
                .as("the repository of only a small issue takes small's choice")
                .containsExactly("alpha", "small", "Building, risk small");
        assertThat(beta.effort()).as("small's effort").isEqualTo("low");
    }

    @Test
    void aStoryOfNoOpenIssueIsProposedTheOrdinaryChoice() {
        // A story's own work with no issue to weigh must still get a choice, the ordinary one.
        final Board original = risked(board(WorkRule.Per.STORY), Map.of());
        final Board board =
                new Board(
                        original.revision(),
                        original.workflow(),
                        List.of(
                                new Story(
                                        "ST1", "Story", "Backlog", true, "High", List.of(), "",
                                        null)),
                        original.issues(),
                        original.work(),
                        original.repositories());
        final WorkProposal.Main main =
                WorkProposal.propose(board, "ST1", "Building").repositories().getFirst().main();
        assertThat(List.of(main.agent(), main.model(), main.basis()))
                .as("the rule's own first model, named as ordinary")
                .containsExactly("zeta", "large", "Building, risk ordinary");
        assertThat(main.effort()).as("the rule names no effort for ordinary").isNull();
    }

    /** The board with its Building rule naming harm and small, and the issues given their risks. */
    static Board risked(final Board board, final Map<String, Risk> risks) {
        final WorkRule plain = board.work().rules().get("Building");
        final Map<String, String> harm = new LinkedHashMap<>();
        harm.put("omega", "huge");
        harm.put("zeta", "large");
        final WorkRule rule =
                new WorkRule(
                        plain.from(),
                        plain.done(),
                        plain.then(),
                        plain.blocked(),
                        plain.per(),
                        plain.modes(),
                        plain.order(),
                        plain.models(),
                        Map.of(
                                Risk.HARM,
                                new WorkRule.ForRisk(harm, "max"),
                                Risk.SMALL,
                                new WorkRule.ForRisk(Map.of("alpha", "small"), "low")));
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                board.issues().stream()
                        .map(
                                i ->
                                        new Issue(
                                                i.id(),
                                                i.repository(),
                                                i.title(),
                                                i.state(),
                                                i.size(),
                                                i.blockedBy(),
                                                i.parent(),
                                                i.link(),
                                                i.text(),
                                                i.worker(),
                                                risks.getOrDefault(i.id(), i.risk())))
                        .toList(),
                new Work(Map.of("Building", rule), List.of()),
                board.repositories());
    }

    /** A story in Idea: AA1 in Backlog, BB1 in Idea, each with the worker given. */
    private static Board staggered(final Worker inBacklog, final Worker inIdea) {
        final Board original = board(WorkRule.Per.REPOSITORY);
        return new Board(
                original.revision(),
                new Workflow(
                        List.of("Idea", "Backlog", "Building", "Review", "Finished"),
                        List.of(),
                        List.of("Finished")),
                List.of(
                        new Story(
                                "ST1",
                                "Story",
                                "Idea",
                                false,
                                "High",
                                List.of("AA1", "BB1"),
                                "",
                                null)),
                List.of(
                        new Issue(
                                "AA1",
                                "alpha",
                                "Ready",
                                "Backlog",
                                null,
                                List.of(),
                                null,
                                null,
                                "",
                                inBacklog,
                                Risk.ORDINARY),
                        new Issue(
                                "BB1",
                                "beta",
                                "Behind",
                                "Idea",
                                null,
                                List.of(),
                                null,
                                null,
                                "",
                                inIdea,
                                Risk.ORDINARY)),
                original.work(),
                original.repositories());
    }

    static Board board(final WorkRule.Per per) {
        final Map<String, String> models = new LinkedHashMap<>();
        models.put("zeta", "large");
        models.put("alpha", "small");
        final WorkRule rule =
                new WorkRule(
                        List.of("Backlog"),
                        "Review",
                        null,
                        null,
                        per,
                        List.of("AGENT", "SHELL"),
                        "Build {story}; issues {issues}; helpers {tasks}.",
                        models);
        return new Board(
                new Revision("board@1"),
                new Workflow(
                        List.of("Backlog", "Building", "Review", "Finished"),
                        List.of(),
                        List.of("Finished")),
                List.of(
                        new Story(
                                "ST1",
                                "Story",
                                "Backlog",
                                true,
                                "High",
                                List.of("AA1", "AA1.1", "BB1", "CC1"),
                                "SECRET DESCRIPTION",
                                null)),
                List.of(
                        issue(
                                "AA1",
                                "alpha",
                                "Parent",
                                "Backlog",
                                null,
                                "**What must be true.** Parent holds."),
                        issue(
                                "AA1.1",
                                "alpha",
                                "Open child",
                                "Backlog",
                                "AA1",
                                "**What must be true.** Keep the data.\n\nPrivate notes"),
                        issue("AA1.2", "alpha", "Closed child", "Finished", "AA1", ""),
                        issue("AA1.3", "beta", "Wrong repository", "Backlog", "AA1", ""),
                        issue("BB1", "beta", "Other parent", "Backlog", null, ""),
                        issue("CC1", "alpha", "Closed parent", "Finished", null, "")),
                new Work(Map.of("Building", rule), List.of()),
                List.of("project", "alpha", "beta"));
    }

    static Issue issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final @Nullable String parent,
            final String text) {
        return new Issue(id, repository, title, state, null, List.of(), parent, null, text);
    }
}

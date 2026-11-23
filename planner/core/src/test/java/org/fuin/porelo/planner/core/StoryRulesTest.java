package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The stories' rules as a board breaks them, the same for every store. */
class StoryRulesTest {

    private static final List<String> STATES =
            List.of("Idea", "Backlog", "Implementing", "Review", "Finished", "Rejected");

    private static final List<String> CLOSED = List.of("Finished", "Rejected");

    @Test
    void anOpenIssuePastTheFirstStateInNoStoryIsABreach() {
        // Harm: work planned past an idea that no story carries is never seen, nor its gain.
        assertThat(
                        breaches(
                                List.of(story("ST1", "High", "Backlog", "PL1")),
                                issue("PL1", null, "Backlog"),
                                issue("PL2", null, "Backlog")))
                .as("PL2 in Backlog stands in no story; PL1 stands in ST1")
                .containsExactly("PL2 IssueInNoStory");
    }

    @Test
    void noIssueIsInNoStoryWhileAStorysIssuesAreNotAllKnown() {
        // Harm: an issue a story's unread Also needs names marked as in no story.
        final Story partly =
                new Story("ST1", "A story", "Backlog", "High", null, List.of(), "", null, false);
        assertThat(breaches(List.of(partly), issue("PL2", null, "Backlog")))
                .as("PL2 may be one ST1's unread lines name")
                .isEmpty();
    }

    @Test
    void anIssueInTheFirstStateOrClosedInNoStoryIsNoBreach() {
        // Harm: a fresh idea or finished work marked as wrong, so the marks are not read.
        assertThat(
                        breaches(
                                List.of(),
                                issue("PL1", null, "Idea"),
                                issue("PL2", null, "Finished"),
                                issue("PL3", null, "Rejected")))
                .as("Idea is the first state, Finished and Rejected are closed")
                .isEmpty();
    }

    @Test
    void theFirstStateIsTheWorkflowsWhateverItIsCalled() {
        // Harm: a workflow that does not begin with Idea marking every new issue, or none.
        final Workflow workflow =
                new Workflow(List.of("Draft", "Idea", "Finished"), List.of(), List.of("Finished"));
        final Board board =
                new Board(
                        new Revision("r1"),
                        workflow,
                        List.of(),
                        List.of(issue("PL1", null, "Draft"), issue("PL2", null, "Idea")),
                        new Work(Map.of(), List.of()));
        assertThat(spelled(StoryRules.of(board)))
                .as("Draft is this workflow's first state, Idea its second")
                .containsExactly("PL2 IssueInNoStory");
    }

    @Test
    void aSubIssueOfAStorysIssueStandsInThatStoryAtAnyDepth() {
        // Harm: the parts of a story's issue marked as belonging to none.
        assertThat(
                        breaches(
                                List.of(story("ST1", "High", "Backlog", "example-lib#1")),
                                issue("example-lib#1", null, "Backlog"),
                                sub("example-lib#2", "example-lib#1", "Backlog", null),
                                sub("example-lib#3", "example-lib#2", "Backlog", null)))
                .as("#2 and #3 stand under ST1's #1")
                .isEmpty();
    }

    // Rule: a story's priority below its open issues'.

    @Test
    void aStoryBelowTheHighestPriorityOfItsOpenIssuesIsABreach() {
        // Harm: urgent work waits behind a story ranked lower, so it is built late.
        final List<StoryRules.Breach> found =
                StoryRules.of(
                        board(
                                List.of(story("ST1", "Low", "Backlog", "PL1", "PL2")),
                                issue("PL1", "Medium", "Backlog"),
                                issue("PL2", "Highest", "Backlog")));
        assertThat(spelled(found))
                .as("PL2 is Highest under a Low story")
                .containsExactly("ST1 StoryPriorityBelowIssue");
        assertThat(found.get(0).words())
                .as("the words name the story and the issue, never a title")
                .isEqualTo("ST1 has a lower priority than its open issue PL2.");
    }

    @Test
    void aStoryAtOrAboveItsIssuesPrioritiesIsNoBreach() {
        // Harm: a story ranked rightly marked as wrong.
        assertThat(
                        breaches(
                                List.of(story("ST1", "High", "Backlog", "PL1", "PL2")),
                                issue("PL1", "High", "Backlog"),
                                issue("PL2", "Low", "Backlog")))
                .as("High over High and Low")
                .isEmpty();
    }

    @Test
    void anIssueWithoutAPriorityIsNeverRanked() {
        // Harm: a breach made up from a priority nobody gave.
        assertThat(
                        breaches(
                                List.of(story("ST1", "Lowest", "Backlog", "PL1")),
                                issue("PL1", null, "Backlog")))
                .as("PL1 holds no priority")
                .isEmpty();
        assertThat(StoryRules.rank(null)).as("no priority").isEmpty();
        assertThat(StoryRules.rank("Urgent")).as("an unknown priority").isEmpty();
        assertThat(StoryRules.rank("Highest")).as("the highest").hasValue(0);
    }

    @Test
    void aStoryWithAnUnknownPriorityIsNeverRanked() {
        // Harm: a story whose priority could not be read marked below every issue.
        assertThat(
                        breaches(
                                List.of(story("ST1", "Urgent", "Backlog", "PL1")),
                                issue("PL1", "Highest", "Backlog")))
                .as("Urgent is no priority")
                .isEmpty();
    }

    @Test
    void aClosedIssueDoesNotRankItsStory() {
        // Harm: a finished urgent issue keeping its story marked for good.
        assertThat(
                        breaches(
                                List.of(story("ST1", "Low", "Backlog", "PL1", "PL2")),
                                issue("PL1", "Highest", "Finished"),
                                issue("PL2", "Low", "Backlog")))
                .as("PL1 is finished")
                .isEmpty();
    }

    @Test
    void anIssueInReviewAndASubIssueCountAsOpen() {
        // Harm: urgent work still to be tested, or a part of an issue, hiding behind a low story.
        assertThat(
                        breaches(
                                List.of(story("ST1", "Low", "Review", "PL1")),
                                issue("PL1", "Low", "Review"),
                                sub("PL1.1", "PL1", "Review", "High")))
                .as("the sub-issue PL1.1 is High under the Low story")
                .containsExactly("ST1 StoryPriorityBelowIssue");
        assertThat(
                        breaches(
                                List.of(story("ST1", "Low", "Review", "PL1")),
                                issue("PL1", "High", "Review")))
                .as("PL1 in Review is open")
                .containsExactly("ST1 StoryPriorityBelowIssue");
    }

    @Test
    void anIssueInTwoStoriesRanksEachOfThem() {
        // Harm: a story sharing an urgent issue left unmarked because another story holds it.
        assertThat(
                        breaches(
                                List.of(
                                        story("ST1", "Highest", "Backlog", "PL1"),
                                        story("ST2", "Low", "Backlog", "PL1")),
                                issue("PL1", "High", "Backlog")))
                .as("ST2 alone is below PL1")
                .containsExactly("ST2 StoryPriorityBelowIssue");
    }

    @Test
    void aClosedStoryIsRankedByItsOpenIssuesToo() {
        // Harm: a story closed over open urgent work hiding that work.
        assertThat(
                        breaches(
                                List.of(story("ST1", "Low", "Finished", "PL1")),
                                issue("PL1", "High", "Finished"),
                                sub("PL1.1", "PL1", "Backlog", "High")))
                .as("PL1.1 is open and High under the Low story")
                .contains("ST1 StoryPriorityBelowIssue");
    }

    @Test
    void aBoardKeepingEveryRuleHasNoBreach() {
        // Harm: marks on a sound board, so the person learns to ignore them.
        assertThat(
                        breaches(
                                List.of(story("ST1", "High", "Backlog", "PL1")),
                                issue("PL1", "High", "Backlog"),
                                issue("PL2", null, "Idea")))
                .as("a sound board")
                .isEmpty();
    }

    @Test
    void theIssuesInStoriesAreThoseTheyNameAndTheirSubIssues() {
        // Harm: the list of issues in no story and the rule disagreeing on one issue.
        final Board board =
                board(
                        List.of(story("ST1", "High", "Backlog", "PL1")),
                        issue("PL1", null, "Backlog"),
                        sub("PL1.1", "PL1", "Backlog", null),
                        issue("PL2", null, "Backlog"));
        assertThat(StoryRules.inStories(board))
                .as("PL1 and its sub-issue")
                .containsExactlyInAnyOrder("PL1", "PL1.1");
    }

    private static List<String> breaches(final List<Story> stories, final Issue... issues) {
        return spelled(StoryRules.of(board(stories, issues)));
    }

    private static List<String> spelled(final List<StoryRules.Breach> breaches) {
        return breaches.stream().map(b -> b.item() + " " + b.rule().spelled()).toList();
    }

    private static Board board(final List<Story> stories, final Issue... issues) {
        return new Board(
                new Revision("r1"),
                new Workflow(STATES, List.of(), CLOSED),
                stories,
                List.of(issues),
                new Work(Map.of(), List.of()));
    }

    private static Story story(
            final String id, final String priority, final String state, final String... issues) {
        return new Story(id, "A story", state, priority, null, List.of(issues), "", null);
    }

    private static Issue issue(
            final String id, final @Nullable String priority, final String state) {
        return sub(id, null, state, priority);
    }

    private static Issue sub(
            final String id,
            final @Nullable String parent,
            final String state,
            final @Nullable String priority) {
        return new Issue(
                id,
                "example-lib",
                "An issue",
                state,
                "S",
                List.of(),
                parent,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                priority,
                List.of(),
                List.of());
    }
}

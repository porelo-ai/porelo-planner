package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/** A story made over issues that stand, checked against the board before the store writes it. */
class NewIdeasFromIssuesTest {

    /** The marker of the request "Tag stories" over a#5 and a#6, which ST2's body carries. */
    private static final String TAG_STORIES =
            StoryFromIssues.marker(
                    "Tag stories", "People find stories", "Tag one", List.of("a#5", "a#6"));

    /**
     * ST1 "Held", a person's own, holds a#1 and a#8; ST2 "Tag stories" holds a#5 and carries the
     * marker of the request over a#5 and a#6; a#2 is open and loose; a#10 hangs under a#2; a#3 is
     * Finished, a#4 Rejected; a#6 is loose; o#7 stands in a state outside the workflow, warned;
     * a#11 is loose and a#12 belongs to ST3, each held back by a warning a person's edit left.
     */
    private static final Board BOARD =
            new Board(
                    new Revision("r1"),
                    new Workflow(
                            List.of("Idea", "Backlog", "Finished", "Rejected"),
                            List.of(),
                            List.of("Finished", "Rejected")),
                    List.of(
                            story("ST1", "Held", "", "a#1", "a#8"),
                            story("ST2", "Tag stories", "Body.\n\n" + TAG_STORIES + "\n", "a#5"),
                            story("ST3", "Other", "", "a#12")),
                    List.of(
                            issue("a#1", "Idea", null),
                            issue("a#2", "Backlog", null),
                            issue("a#3", "Finished", null),
                            issue("a#4", "Rejected", null),
                            issue("a#5", "Idea", null),
                            issue("a#6", "Idea", null),
                            issue("a#8", "Idea", null),
                            issue("a#10", "Idea", "a#2"),
                            issue("a#11", "Idea", null)
                                    .withWarnings(List.of(Warning.unknownWorker())),
                            issue("a#12", "Idea", null)
                                    .withWarnings(List.of(Warning.unknownWorker())),
                            new Issue(
                                            "o#7",
                                            "o",
                                            "Loose",
                                            "Somewhere",
                                            null,
                                            List.of(),
                                            null,
                                            null,
                                            "")
                                    .withWarnings(List.of(Warning.stateOutsideWorkflow()))),
                    new Work(Map.of(), List.of()),
                    org.fuin.porelo.planner.store.ProjectRepository.named(List.of("a", "o")),
                    List.of(),
                    "example-org");

    private static Story story(
            final String id, final String title, final String body, final String... issues) {
        return new Story(id, title, "Idea", "High", null, List.of(issues), body, null);
    }

    private static Issue issue(final String id, final String state, final String parent) {
        return new Issue(id, "a", "Issue " + id, state, null, List.of(), parent, null, "");
    }

    private static NewIdeas.Story head(final String title) {
        return new NewIdeas.Story(" " + title + " ", "People find stories", "Tag one", "High");
    }

    private static List<String> faults(final List<String> existing) {
        final WorkRefusal refused =
                catchThrowableOfType(
                        WorkRefusal.class,
                        () ->
                                NewIdeas.checkFromIssues(
                                        BOARD, head("A new story"), existing, List.of()));
        assertThat(refused).as("the refusal of %s", existing).isNotNull();
        assertThat(refused.name()).as("the refusal's name").isEqualTo("IdeaRefused");
        @SuppressWarnings("unchecked")
        final List<String> faults = (List<String>) refused.parameters().get("faults");
        return faults;
    }

    @Test
    void openLooseIssuesAreTakenInTheOrderNamedWithTheStorysLinesStripped() {
        // Harm: issues hung into the new story in another order than the person named, or a
        // story saved with the spaces a person typed around its title.
        final StoryFromIssues checked =
                NewIdeas.checkFromIssues(
                        BOARD, head("A new story"), List.of("a#6", "a#2"), List.of());

        assertThat(checked)
                .as("the story the store saves")
                .isEqualTo(
                        new StoryFromIssues(
                                "A new story",
                                "People find stories",
                                "Tag one",
                                "High",
                                List.of("a#6", "a#2")));
    }

    @Test
    void eachIssueANewStoryCannotTakeIsRefusedByItsIdAndNothingIsTaken() {
        // Harm: an issue of another story, a story, a closed or unknown issue, a sub-issue, an
        // issue outside the workflow or one named twice hung into a new story, or refused
        // without its id, so the person cannot tell which one to fix.
        assertThat(faults(List.of("a#1", "ST2", "a#3", "a#4", "a#9", "a#10", "o#7", "a#6", "a#6")))
                .as("every fault, each by its place and its id, before an issue held elsewhere")
                .containsExactly(
                        "existing[1]: ST2 is a story, not an issue.",
                        "existing[2]: The issue a#3 is Finished; a new story takes open issues"
                                + " only.",
                        "existing[3]: The issue a#4 is Rejected; a new story takes open issues"
                                + " only.",
                        "existing[4]: There is no issue a#9 on the board.",
                        "existing[5]: The issue a#10 is a sub-issue of a#2.",
                        "existing[6]: The issue o#7 stands outside the workflow; move it into a"
                                + " state of the workflow first.",
                        "existing[8]: The issue a#6 is named twice.");
    }

    @Test
    void anIssueAnotherStoryHoldsIsTakenIntoTheNewStory() {
        // Harm: an issue filed under the wrong story cannot be gathered into a new one, or a save
        // cut off after it hung an issue can never be finished, since its own story holds it.
        for (final List<String> existing :
                List.of(
                        List.of("a#6", "a#1"),
                        List.of("a#5", "a#6"),
                        List.of("a#5", "a#6", "a#2"),
                        List.of("a#1", "a#8"))) {
            assertThat(
                            NewIdeas.checkFromIssues(
                                            BOARD, head("Another title"), existing, List.of())
                                    .existing())
                    .as("the issues of %s, one or more held by another story", existing)
                    .isEqualTo(existing);
        }
    }

    @Test
    void aStoryOverMoreIssuesThanAStoryHoldsIsRefused() {
        // Harm: a story the reader's bound never saw made over more issues than any story holds.
        final List<String> many = new java.util.ArrayList<>();
        for (int number = 1; number <= Edits.MOST_ISSUES + 1; number++) {
            many.add("a#" + number);
        }
        assertThat(faults(many))
                .as("the bound named first")
                .startsWith("existing: A story holds at most 100 issues.");
    }

    @Test
    void anIssueHeldBackByAWarningIsTakenIntoNoStoryEvenWhenAnotherStoryHoldsIt() {
        // Harm: a new story links an issue a person's hand edit left held back, building the
        // planner's write on an item no edit may add to or take from a story; or the conflict
        // with its story answered instead, so the person moves it where it is still held.
        assertThat(BOARD.holdsWork("a#11")).as("a#11 held back").isPresent();
        assertThat(faults(List.of("a#6", "a#11", "a#12")))
                .as("each held issue refused by its place with the words of its hold")
                .containsExactly(
                        "existing[1]: " + BOARD.holdsWork("a#11").orElseThrow(),
                        "existing[2]: " + BOARD.holdsWork("a#12").orElseThrow());
    }
}

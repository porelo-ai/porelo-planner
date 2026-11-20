package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class IssueMappingTest {

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Backlog", "Implementing", "Finished", "Rejected"),
                    List.of(
                            new Workflow.Phase(
                                    "All",
                                    List.of(
                                            "Idea",
                                            "Backlog",
                                            "Implementing",
                                            "Finished",
                                            "Rejected"))),
                    List.of("Finished", "Rejected"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef("example-org", 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    List.of("example-project", "example-lib"),
                    Map.of(),
                    Map.of());

    private static final StoryStates FIRST_OPEN = states -> states.isEmpty() ? null : states.get(0);

    private static final String SECRET_TITLE = "a title only GitHub may show";

    private static final String SECRET_BODY = "a body only GitHub may show";

    /** One item as GitHub's answer gives it; its node ids are its repository and number. */
    private static final class Item {
        private final String repository;
        private final int number;
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final List<String> labels = new ArrayList<>();
        private final List<String> subs = new ArrayList<>();
        private final List<String> blockers = new ArrayList<>();
        private @Nullable String parent;
        private boolean open = true;
        private boolean archived;
        private String body = SECRET_BODY;
        private final List<CommentView> comments = new ArrayList<>();
        private boolean commentsRead = true;

        private Item(final String repository, final int number, final @Nullable String id) {
            this.repository = repository;
            this.number = number;
            if (id != null) {
                fields.put(FieldNames.ID, id);
            }
        }

        private Item field(final String name, final String value) {
            fields.put(name, value);
            return this;
        }

        private Item story() {
            labels.add(IssueMapping.STORY_LABEL);
            fields.putIfAbsent(FieldNames.PRIORITY, "Medium");
            return this;
        }

        private Item sub(final Item child) {
            subs.add(child.node());
            child.parent = node();
            return this;
        }

        private Item blockedBy(final Item blocker) {
            blockers.add(blocker.node());
            return this;
        }

        private Item closed() {
            open = false;
            return this;
        }

        private Item archived() {
            archived = true;
            return this;
        }

        private Item subNode(final String node) {
            subs.add(node);
            return this;
        }

        private Item blockedByNode(final String node) {
            blockers.add(node);
            return this;
        }

        private Item commentsNotRead() {
            commentsRead = false;
            return this;
        }

        private Item body(final String text) {
            body = text;
            return this;
        }

        private Item stage(final String key, final double minutes) {
            final StageEntry entry =
                    new StageEntry(
                            key,
                            "implement",
                            "done",
                            "agent",
                            "claude-opus",
                            "high",
                            minutes,
                            1,
                            Map.of("claude_input_tokens", 100L, "claude_output_tokens", 20L));
            comments.add(
                    new CommentView(
                            "IC_" + key,
                            StageComment.write(entry),
                            Instant.parse("2026-01-02T00:00:00Z")));
            return this;
        }

        private String node() {
            return "I_" + repository + "_" + number;
        }

        private ItemView view() {
            return new ItemView(
                    "PVTI_" + repository + "_" + number,
                    node(),
                    repository,
                    number,
                    SECRET_TITLE,
                    body,
                    open,
                    open ? null : "COMPLETED",
                    fields,
                    labels,
                    parent,
                    subs,
                    blockers,
                    commentsRead ? comments : null,
                    URI.create(
                            "https://github.com/example-org/" + repository + "/issues/" + number),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    archived);
        }
    }

    private static IssueMapping.Mapped read(final Item... items) {
        return IssueMapping.read(
                java.util.Arrays.stream(items).map(Item::view).toList(), CONFIG, FIRST_OPEN);
    }

    @Test
    void aStoryWithIssuesAcrossRepositoriesIsReadAsTheBoardHoldsIt() {
        // Harm: the board shows another plan than the one kept on GitHub.
        final Item story =
                new Item("example-project", 1, "ST7").story().field(FieldNames.PRIORITY, "High");
        final Item first =
                new Item("example-project", 2, "PL41")
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.SIZE, "M")
                        .field(FieldNames.RISK, "harm")
                        .field(FieldNames.WORKER, "Manual");
        final Item second =
                new Item("example-lib", 3, "LB5")
                        .field(FieldNames.STATUS, "Implementing")
                        .blockedBy(first);
        final Item part = new Item("example-lib", 4, "LB5.1").field(FieldNames.STATUS, "Idea");
        final Item done =
                new Item("example-lib", 5, "LB6").field(FieldNames.STATUS, "Finished").closed();
        story.sub(first).sub(second).sub(done);
        second.sub(part);

        final IssueMapping.Mapped mapped = read(story, first, second, part, done);

        assertThat(mapped.faults()).as("faults of a plan kept as the planner writes it").isEmpty();
        assertThat(mapped.stories())
                .as("the story")
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.id()).as("its id").isEqualTo("ST7");
                            assertThat(s.issues())
                                    .as("its issues, in GitHub's order")
                                    .containsExactly("PL41", "LB5", "LB6");
                            assertThat(s.stateGiven())
                                    .as("an empty Status follows the issues")
                                    .isFalse();
                            assertThat(s.state())
                                    .as("the state its issues give")
                                    .isEqualTo("Backlog");
                            assertThat(s.priority()).as("its priority").isEqualTo("High");
                            assertThat(s.issuesWhole()).as("every issue read").isTrue();
                        });
        final Map<String, Issue> issues = new LinkedHashMap<>();
        mapped.issues().forEach(i -> issues.put(i.id(), i));
        assertThat(issues.get("PL41"))
                .as("an issue with its fields")
                .extracting(Issue::state, Issue::size, Issue::risk, Issue::worker, Issue::parent)
                .containsExactly("Backlog", "M", Risk.HARM, Worker.MANUAL, null);
        assertThat(issues.get("LB5").blockedBy())
                .as("a dependency across repositories")
                .containsExactly("PL41");
        assertThat(issues.get("LB5.1").parent()).as("a Porelo sub-issue's parent").isEqualTo("LB5");
        assertThat(issues.get("LB6").state()).as("a closed issue").isEqualTo("Finished");
    }

    @Test
    void anItemEditedByHandIntoWhatTheBoardCannotHoldIsLeftOutAsAFault() {
        // Harm: one hand edit on GitHub takes the whole board down, or shows a broken plan as
        // whole.
        final Item story = new Item("example-project", 1, "ST7").story();
        final Item noId = new Item("example-project", 2, null).field(FieldNames.STATUS, "Idea");
        final Item badId =
                new Item("example-project", 3, "not an id").field(FieldNames.STATUS, "Idea");
        final Item twice = new Item("example-lib", 4, "LB5").field(FieldNames.STATUS, "Idea");
        final Item again = new Item("example-lib", 5, "LB5").field(FieldNames.STATUS, "Idea");
        final Item badState =
                new Item("example-lib", 6, "LB6").field(FieldNames.STATUS, "Somewhere");
        final Item blocked =
                new Item("example-lib", 7, "LB7")
                        .field(FieldNames.STATUS, "Idea")
                        .blockedBy(badState);
        final Item wrongParent =
                new Item("example-lib", 8, "LB9.1").field(FieldNames.STATUS, "Idea");
        final Item outside = new Item("elsewhere", 9, "LB10").field(FieldNames.STATUS, "Idea");
        final Item fine = new Item("example-lib", 10, "LB11").field(FieldNames.STATUS, "Idea");
        story.sub(twice).sub(blocked).sub(fine);
        twice.sub(wrongParent);

        final IssueMapping.Mapped mapped =
                read(
                        story,
                        noId,
                        badId,
                        twice,
                        again,
                        badState,
                        blocked,
                        wrongParent,
                        outside,
                        fine);

        assertThat(mapped.issues())
                .as("only the issues the board can hold")
                .extracting(Issue::id)
                .containsExactlyInAnyOrder("LB5", "LB11");
        assertThat(mapped.faults()).as("one fault per item left out").hasSize(7);
        assertThat(mapped.stories().get(0).issuesWhole())
                .as("the story says an issue of it is missing")
                .isFalse();
    }

    @Test
    void aStatusAgainstGitHubsOpenOrClosedStaysTheTruthAndIsNamed() {
        // Harm: a finished issue reopened on GitHub silently counted as done, or as open, without a
        // word.
        final Item reopened =
                new Item("example-lib", 1, "LB5").field(FieldNames.STATUS, "Finished");

        final IssueMapping.Mapped mapped = read(reopened);

        assertThat(mapped.issues().get(0).state()).as("the Status").isEqualTo("Finished");
        assertThat(mapped.faults())
                .as("the disagreement named")
                .singleElement()
                .asString()
                .contains("LB5");
    }

    @Test
    void anIssueUnderAStoryHasNoIssueAsItsParent() {
        // Harm: a story read as an issue's parent, which the board would take for a Porelo
        // sub-issue.
        final Item story = new Item("example-project", 1, "ST7").story();
        final Item issue = new Item("example-lib", 2, "LB5").field(FieldNames.STATUS, "Idea");
        story.sub(issue);

        assertThat(read(story, issue).issues().get(0).parent())
                .as("the parent of a story's issue")
                .isNull();
    }

    @Test
    void theFieldsWrittenForAnIssueReadBackAsTheSameIssue() {
        // Harm: a save that changes what it did not mean to, such as a size or a worker lost on the
        // way.
        final Issue issue =
                new Issue(
                        "LB5",
                        "example-lib",
                        SECRET_TITLE,
                        "Backlog",
                        "S",
                        List.of(),
                        null,
                        URI.create("https://github.com/example-org/example-lib/issues/1"),
                        SECRET_BODY,
                        Worker.MANUAL,
                        Risk.SMALL);
        final Item item = new Item("example-lib", 1, null);
        IssueMapping.fields(issue, "Low", 4).forEach(item::field);

        assertThat(read(item).issues()).as("the issue read back").containsExactly(issue);
    }

    @Test
    void theFieldsWrittenForAStoryReadBackAsTheSameStory() {
        // Harm: a story's given state or priority lost on a save.
        final Story story =
                new Story(
                        "ST4",
                        SECRET_TITLE,
                        "Implementing",
                        true,
                        "Low",
                        "L",
                        List.of(),
                        SECRET_BODY,
                        URI.create("https://github.com/example-org/example-project/issues/1"));
        final Item item = new Item("example-project", 1, null).story();
        IssueMapping.fields(story, 2).forEach(item::field);

        assertThat(read(item).stories()).as("the story read back").containsExactly(story);
    }

    @Test
    void aFaultNeverRepeatsWhatAPersonWroteOnGitHub() {
        // Harm: a private title or body shown in the log or to whoever reads the board's faults.
        final Item bad = new Item("example-lib", 1, "LB5").field(FieldNames.STATUS, SECRET_TITLE);
        final Item noId = new Item("example-lib", 2, SECRET_BODY);

        assertThat(String.join("\n", read(bad, noId).faults()))
                .as("the faults' words")
                .doesNotContain(SECRET_TITLE)
                .doesNotContain(SECRET_BODY);
    }

    @Test
    void anIssueListedUnderAlsoNeedsBelongsToTheSecondStoryToo() {
        // Harm: a story counted done, or started, while an issue it needs from another story is
        // not.
        final Item shared = new Item("example-lib", 3, "LB5").field(FieldNames.STATUS, "Idea");
        final Item first = new Item("example-project", 1, "ST7").story().sub(shared);
        final Item second =
                new Item("example-project", 2, "ST8")
                        .story()
                        .body("Covers it.\n\n## Also needs\n\n- example-lib#3\n");
        final Item gone =
                new Item("example-project", 4, "ST9")
                        .story()
                        .body("## Also needs\n\n- example-lib#3\n- example-lib#99\n");

        final IssueMapping.Mapped mapped = read(first, second, gone, shared);

        assertThat(mapped.stories())
                .as("each story with the issues it holds and those it also needs")
                .extracting(Story::id, Story::issues, Story::issuesWhole)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ST7", List.of("LB5"), true),
                        org.assertj.core.groups.Tuple.tuple("ST8", List.of("LB5"), true),
                        org.assertj.core.groups.Tuple.tuple("ST9", List.of("LB5"), false));
        assertThat(mapped.faults())
                .as("an issue also needed that the board does not hold is named")
                .containsExactly(
                        "ST9 has a line under Also needs that names no issue of the project");
    }

    @Test
    void aSumFieldEditedByHandIsShownAsItsCommentsSumAndWrittenAgain() {
        // Harm: the velocity and the board read a hand edit instead of what the stages measured.
        final Item edited =
                new Item("example-lib", 3, "LB5")
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.MINUTES, "99")
                        .field(FieldNames.STAGES, "2")
                        .stage("t1/implement", 10)
                        .stage("t2/implement", 5);
        final Item kept =
                new Item("example-lib", 4, "LB6")
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.MINUTES, "7")
                        .field(FieldNames.VM_MINUTES, "1")
                        .field(FieldNames.TOKENS, "120")
                        .field(FieldNames.STAGES, "1")
                        .stage("t3/implement", 7);

        final IssueMapping.Mapped mapped = read(edited, kept);

        assertThat(mapped.sums().get("LB5").minutes())
                .as("the comments' sum, not the field")
                .isEqualTo(15.0);
        assertThat(mapped.rewrite())
                .as("only the issue whose fields disagree is written again")
                .containsExactly("LB5");
        assertThat(mapped.faults())
                .as("the disagreeing field is named")
                .containsExactly(
                        "LB5 has a Minutes apart from its stage comments' sum, shown as the sum",
                        "LB5 has a VM minutes apart from its stage comments' sum, shown as the sum",
                        "LB5 has a Tokens apart from its stage comments' sum, shown as the sum");
    }

    @Test
    void anIssueWhoseCommentsWereNotReadShowsItsFieldsWithoutASumOrARewrite() {
        // Harm: a board read that reads no comments takes every issue's fields for disagreeing
        // with an empty sum, names a fault for each and writes them all to zero at the next save.
        final Item unread =
                new Item("example-lib", 3, "LB5")
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.MINUTES, "99")
                        .field(FieldNames.STAGES, "2")
                        .commentsNotRead();

        final IssueMapping.Mapped mapped = read(unread);

        assertThat(mapped.issues()).as("the issue is on the board").hasSize(1);
        assertThat(mapped.sums()).as("no sum stands for comments not read").isEmpty();
        assertThat(mapped.rewrite()).as("nothing is written again").isEmpty();
        assertThat(mapped.faults()).as("its fields are no fault").isEmpty();
    }

    @Test
    void aStoryBodyTooLongToReadIsAFaultNotABoardThatFailsToLoad() {
        // Harm: one story edited by hand on GitHub keeps every person from seeing the board.
        final Item story =
                new Item("example-project", 1, "ST7")
                        .story()
                        .body("## Also needs\n\n" + "x".repeat(BodySections.LONGEST));

        final IssueMapping.Mapped mapped = read(story);

        assertThat(mapped.stories())
                .as("the story stays, its issues not known whole")
                .extracting(Story::id, Story::issuesWhole)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ST7", false));
        assertThat(mapped.faults())
                .as("the body is named, not shown")
                .containsExactly(
                        "ST7 has a body too long to read its Also needs, shown without it");
        assertThat(mapped.stories().get(0).description())
                .as("the board holds no body beyond GitHub's bound")
                .isEmpty();
    }

    @Test
    void anIssueBodyBeyondGitHubsBoundStaysOnTheBoardWithoutItsBody() {
        // Harm: a body of megabytes held and sent to the window on every look at the board.
        final Item issue =
                new Item("example-lib", 3, "LB5")
                        .field(FieldNames.STATUS, "Idea")
                        .body(SECRET_BODY + "x".repeat(BodySections.LONGEST));

        final IssueMapping.Mapped mapped = read(issue);

        assertThat(mapped.issues())
                .as("the issue stays, without its body")
                .extracting(Issue::id, Issue::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("LB5", ""));
        assertThat(mapped.faults())
                .as("the issue named, never its body's text")
                .containsExactly("LB5 has a body longer than GitHub takes, shown without it");
    }

    @Test
    void anIssueWhoseBodyCannotBeSplitIntoSectionsStaysOnTheBoardNamedWithWhatToRepair() {
        // Harm: a body whose sections a person must repair hides the issue, or no stage result
        // reaches it and nobody is told why.
        final Item unclear =
                new Item("example-lib", 3, "LB5")
                        .field(FieldNames.STATUS, "Idea")
                        .body("Covers.\n\n## Plan\n\n```\n" + SECRET_BODY + "\n");

        final IssueMapping.Mapped mapped = read(unclear);

        assertThat(mapped.issues())
                .as("the issue on the board")
                .extracting(Issue::id)
                .containsExactly("LB5");
        assertThat(mapped.faults())
                .as("the item named by its repository and number, with what to repair")
                .containsExactly(
                        "Item example-lib#3 has a body whose sections are unclear, so no stage"
                                + " result is written into it; repair: the code block opened in"
                                + " line 5 under Plan never closes");
    }

    @Test
    void aLineUnderAlsoNeedsThatNamesNoIssueOfTheProjectIsAFaultWithoutItsText() {
        // Harm: a story counted whole while a needed issue is unread, and a person's words in a
        // log.
        final Item shared = new Item("example-lib", 3, "LB5").field(FieldNames.STATUS, "Idea");
        final Item story =
                new Item("example-project", 1, "ST7")
                        .story()
                        .body("## Also needs\n\n- LB5\n- secret_words#1\n- example-lib#3\n");

        final IssueMapping.Mapped mapped = read(story, shared);

        assertThat(mapped.stories())
                .as("the issue named rightly is read, the story not whole")
                .extracting(Story::issues, Story::issuesWhole)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(List.of("LB5"), false));
        assertThat(mapped.faults())
                .as("one fault per line, none repeating it")
                .containsExactly(
                        "ST7 has a line under Also needs that names no issue of the project",
                        "ST7 has a line under Also needs that names no issue of the project");
    }

    @Test
    void aSubIssueIsWorkedAsItsParentAndStandsInItsParentsRepository() {
        // Harm: an agent sent to a manual issue's part, at a lower risk than the issue it belongs
        // to.
        final Item part = new Item("example-lib", 4, "LB5.1").field(FieldNames.STATUS, "Idea");
        final Item parent =
                new Item("example-lib", 3, "LB5")
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.WORKER, "Manual")
                        .field(FieldNames.RISK, "harm")
                        .sub(part);
        final Item elsewhere =
                new Item("example-project", 6, "LB6.1").field(FieldNames.STATUS, "Idea");
        final Item other =
                new Item("example-lib", 5, "LB6").field(FieldNames.STATUS, "Idea").sub(elsewhere);

        final IssueMapping.Mapped mapped = read(parent, part, other, elsewhere);

        assertThat(mapped.issues())
                .as("the part worked as its parent, the one in another repository left out")
                .extracting(Issue::id, Issue::worker, Issue::risk)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("LB5", Worker.MANUAL, Risk.HARM),
                        org.assertj.core.groups.Tuple.tuple("LB5.1", Worker.MANUAL, Risk.HARM),
                        org.assertj.core.groups.Tuple.tuple("LB6", Worker.AGENT, Risk.ORDINARY));
        assertThat(mapped.faults())
                .as("the misplaced part is named")
                .containsExactly(
                        "LB6.1 stands in another repository than its parent and is left out");
    }

    @Test
    void aStoryWithASubIssueOutsideTheProjectOrNoPriorityIsNamed() {
        // Harm: a story shown whole and in its place while GitHub holds it otherwise.
        final Item story =
                new Item("example-project", 1, "ST7")
                        .story()
                        .field(FieldNames.PRIORITY, "")
                        .subNode("I_elsewhere_9");

        final IssueMapping.Mapped mapped = read(story);

        assertThat(mapped.stories())
                .as("shown as Medium and not whole")
                .extracting(Story::priority, Story::issuesWhole)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Medium", false));
        assertThat(mapped.faults())
                .as("both are named")
                .containsExactly(
                        "ST7 has a sub-issue outside the project",
                        "ST7 has no Priority, shown as Medium");
    }

    @Test
    void theBoardIsOrderedByThePriorityAndRankWrittenToTheFields() {
        // Harm: work started in another order than the person set, after a priority or rank is
        // saved.
        final List<Item> items = new ArrayList<>();
        int number = 1;
        for (final String[] given :
                List.of(
                        new String[] {"LB1", "Low", "1"},
                        new String[] {"LB2", "High", "7"},
                        new String[] {"LB3", "High", "2"},
                        new String[] {"LB4", "Medium", "1"})) {
            final Issue issue =
                    new Issue(
                            given[0],
                            "example-lib",
                            SECRET_TITLE,
                            "Idea",
                            null,
                            List.of(),
                            null,
                            null,
                            SECRET_BODY,
                            Worker.AGENT,
                            Risk.ORDINARY);
            final Item item = new Item("example-lib", number++, null);
            IssueMapping.fields(issue, given[1], Integer.parseInt(given[2])).forEach(item::field);
            items.add(item);
        }

        assertThat(read(items.toArray(Item[]::new)).issues())
                .as("by priority, then by rank")
                .extracting(Issue::id)
                .containsExactly("LB3", "LB2", "LB4", "LB1");
    }

    @Test
    void anArchivedItemIsNotOnTheBoardButKeepsItsIdTaken() {
        // Harm: an archived issue's id given to a second item, so its alias finds two.
        final Item archived =
                new Item("example-lib", 1, "LB5").field(FieldNames.STATUS, "Idea").archived();
        final Item again = new Item("example-lib", 2, "LB5").field(FieldNames.STATUS, "Idea");
        final Item live = new Item("example-lib", 3, "LB6").field(FieldNames.STATUS, "Idea");

        final IssueMapping.Mapped mapped = read(archived, again, live);

        assertThat(mapped.issues())
                .as("only the live item")
                .extracting(Issue::id)
                .containsExactly("LB6");
        assertThat(mapped.faults())
                .as("the second item under an archived id is named")
                .containsExactly("Item example-lib#2 repeats the Id LB5 and is left out");
    }

    @Test
    void anIssueBlockedByAnItemOutsideTheProjectIsLeftOut() {
        // Harm: work started while an issue it waits on, which the board cannot see, is open.
        final Item blocked =
                new Item("example-lib", 1, "LB5")
                        .field(FieldNames.STATUS, "Idea")
                        .blockedByNode("I_elsewhere_9");

        final IssueMapping.Mapped mapped = read(blocked);

        assertThat(mapped.issues()).as("left out").isEmpty();
        assertThat(mapped.faults())
                .as("named")
                .containsExactly("LB5 is blocked by an issue outside the project and is left out");
    }
}

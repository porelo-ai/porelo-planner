package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.groups.Tuple;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.OutsideIssue;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Wait;
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
        private final List<ItemView.Blocker> named = new ArrayList<>();
        private @Nullable String parent;
        private boolean open = true;
        private boolean archived;
        private String body = SECRET_BODY;
        private final List<CommentView> comments = new ArrayList<>();
        private boolean commentsRead = true;

        private Item(final String repository, final int number) {
            this.repository = repository;
            this.number = number;
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

        /** Blocked by an issue GitHub names by its repository and number, as a read gives it. */
        private Item blockedByElsewhere(
                final String node, final String repository, final int number) {
            blockers.add(node);
            named.add(new ItemView.Blocker(node, repository, number));
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
                    archived,
                    named,
                    0);
        }
    }

    private static IssueMapping.Mapped read(final Item... items) {
        return IssueMapping.read(
                java.util.Arrays.stream(items).map(Item::view).toList(), CONFIG, FIRST_OPEN);
    }

    private static IssueMapping.Mapped read(
            final Map<String, Snapshot.Waited> waits, final Item... items) {
        return IssueMapping.read(
                java.util.Arrays.stream(items).map(Item::view).toList(),
                java.util.Set.of(),
                CONFIG,
                FIRST_OPEN,
                waits);
    }

    @Test
    void aStoryWithIssuesAcrossRepositoriesIsReadAsTheBoardHoldsIt() {
        // Harm: the board shows another plan than the one kept on GitHub.
        final Item story =
                new Item("example-project", 1).story().field(FieldNames.PRIORITY, "High");
        final Item first =
                new Item("example-project", 2)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.SIZE, "M")
                        .field(FieldNames.RISK, "harm")
                        .field(FieldNames.WORKER, "Manual");
        final Item second =
                new Item("example-lib", 3)
                        .field(FieldNames.STATUS, "Implementing")
                        .blockedBy(first);
        final Item part = new Item("example-lib", 4).field(FieldNames.STATUS, "Idea");
        final Item done = new Item("example-lib", 5).field(FieldNames.STATUS, "Finished").closed();
        story.sub(first).sub(second).sub(done);
        second.sub(part);

        final IssueMapping.Mapped mapped = read(story, first, second, part, done);

        assertThat(mapped.faults()).as("faults of a plan kept as the planner writes it").isEmpty();
        assertThat(mapped.stories())
                .as("the story")
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.id()).as("its id").isEqualTo("example-project#1");
                            assertThat(s.issues())
                                    .as("its issues, in GitHub's order")
                                    .containsExactly(
                                            "example-project#2", "example-lib#3", "example-lib#5");
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
        assertThat(issues.get("example-project#2"))
                .as("an issue with its fields")
                .extracting(Issue::state, Issue::size, Issue::risk, Issue::worker, Issue::parent)
                .containsExactly("Backlog", "M", Risk.HARM, Worker.MANUAL, null);
        assertThat(issues.get("example-lib#3").blockedBy())
                .as("a dependency across repositories")
                .containsExactly("example-project#2");
        assertThat(issues.get("example-lib#4").parent())
                .as("a Porelo sub-issue's parent")
                .isEqualTo("example-lib#3");
        assertThat(issues.get("example-lib#5").state()).as("a closed issue").isEqualTo("Finished");
    }

    @Test
    void anItemIsNamedByItsRepositoryAndNumberWithNoIdFieldOnTheProject() {
        // Harm: an item made on GitHub after the cut-over, which has no Id, is not on the board,
        // or an old id left in a field names an item in the reference's place.
        final Item story = new Item("example-project", 4).story();
        final Item made = new Item("example-lib", 12).field(FieldNames.STATUS, "Idea");
        final Item carried =
                new Item("example-lib", 13).field(FieldNames.STATUS, "Idea").field("Id", "LB5");
        story.sub(made).sub(carried);

        final IssueMapping.Mapped mapped = read(story, made, carried);

        assertThat(mapped.faults()).as("faults").isEmpty();
        assertThat(mapped.stories())
                .as("the story by its reference, its issues by theirs")
                .extracting(Story::id, Story::issues)
                .containsExactly(
                        Tuple.tuple(
                                "example-project#4", List.of("example-lib#12", "example-lib#13")));
        assertThat(mapped.issues())
                .as("each issue by its reference alone")
                .extracting(Issue::id)
                .containsExactlyInAnyOrder("example-lib#12", "example-lib#13");
    }

    @Test
    void anItemOfARepositoryOutsideTheProjectIsLeftOutAsAFaultNamingRepositoryAndNumber() {
        // Harm: an item of a repository project.yml does not name is read or written as part of
        // the project, or a hand edit there takes the board down.
        final Item story = new Item("example-project", 1).story();
        final Item outside = new Item("elsewhere", 9).field(FieldNames.STATUS, "Idea");
        final Item unnamed = new Item("Example-Lib", 3).field(FieldNames.STATUS, "Idea");
        final Item fine = new Item("example-lib", 2).field(FieldNames.STATUS, "Idea");
        story.sub(outside).sub(unnamed).sub(fine);

        final IssueMapping.Mapped mapped = read(story, outside, unnamed, fine);

        assertThat(mapped.issues())
                .as("only the issue of the project's repositories")
                .extracting(Issue::id)
                .containsExactly("example-lib#2");
        assertThat(mapped.faults())
                .as("each item left out, named by its repository and number")
                .containsExactly(
                        "Item elsewhere#9 lies outside the project's repositories and is left out",
                        "Item Example-Lib#3 has no valid tracker reference and is left out",
                        "example-project#1 has a sub-issue outside the project",
                        "example-project#1 has a sub-issue outside the project");
    }

    @Test
    void aStoryWhoseOnlySubIssueLiesOutsideTheProjectSaysSo() {
        // Harm: a story is shown whole while one of its issues stands where the board cannot see.
        final Item story = new Item("example-project", 1).story();
        final Item outside = new Item("elsewhere", 9).field(FieldNames.STATUS, "Idea");
        story.sub(outside);

        final IssueMapping.Mapped mapped = read(story, outside);

        assertThat(mapped.faults())
                .as("the foreign item and the story's sub-issue outside the project")
                .containsExactly(
                        "Item elsewhere#9 lies outside the project's repositories and is left out",
                        "example-project#1 has a sub-issue outside the project");
    }

    @Test
    void anItemLabeledStoryOutsideTheOwnRepositoryTakesItsSubIssuesOutWithIt() {
        // Harm: the sub-issue of a refused story is shown as an issue of no story, with its own
        // worker and risk instead of being left out with it.
        final Item own = new Item("example-project", 1).story();
        final Item labeled = new Item("example-lib", 3).story();
        final Item under = new Item("example-lib", 4).field(FieldNames.STATUS, "Idea");
        labeled.sub(under);

        final IssueMapping.Mapped mapped = read(own, labeled, under);

        assertThat(mapped.issues()).as("no issue of the refused story").isEmpty();
        assertThat(mapped.faults())
                .as("the story outside the own repository and its sub-issue left out")
                .containsExactly(
                        "example-lib#3 is a story outside the project's own repository and is"
                                + " left out",
                        "example-lib#4 has a parent outside the project and is left out");
    }

    @Test
    void aSubIssueOfASubIssueIsRefusedWhicheverOrderItsItemsCome() {
        // Harm: a tree deeper than the board holds is shown as a plan, or an item depending on a
        // refused one takes the board down.
        final Item story = new Item("example-project", 1).story();
        final Item issue = new Item("example-lib", 2).field(FieldNames.STATUS, "Idea");
        final Item part = new Item("example-lib", 3).field(FieldNames.STATUS, "Idea");
        final Item deep = new Item("example-lib", 4).field(FieldNames.STATUS, "Idea");
        final Item deeper = new Item("example-lib", 5).field(FieldNames.STATUS, "Idea");
        final Item waiting =
                new Item("example-lib", 6).field(FieldNames.STATUS, "Idea").blockedBy(deep);
        story.sub(issue).sub(waiting);
        issue.sub(part);
        part.sub(deep);
        deep.sub(deeper);

        final IssueMapping.Mapped mapped = read(deeper, deep, waiting, part, story, issue);

        assertThat(mapped.issues())
                .as("the story's issue and its sub-issue stay")
                .extracting(Issue::id, Issue::parent)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("example-lib#2", null),
                        Tuple.tuple("example-lib#3", "example-lib#2"));
        assertThat(mapped.faults())
                .as("each item below a sub-issue refused, and the one waiting on it")
                .containsExactlyInAnyOrder(
                        "example-lib#4 is a sub-issue of a sub-issue and is left out",
                        "example-lib#5 is a sub-issue of a sub-issue and is left out",
                        "example-lib#6 depends on an issue left out and is left out too");
        assertThat(mapped.stories().get(0).issuesWhole())
                .as("the story says an issue of it is missing")
                .isFalse();
    }

    @Test
    void anItemEditedByHandIntoWhatTheBoardCannotHoldIsLeftOutAsAFault() {
        // Harm: one hand edit on GitHub takes the whole board down, or shows a broken plan as
        // whole.
        final Item story = new Item("example-project", 1).story();
        final Item parent = new Item("example-lib", 4).field(FieldNames.STATUS, "Idea");
        final Item badState = new Item("example-lib", 6).field(FieldNames.STATUS, "Somewhere");
        final Item blocked =
                new Item("example-lib", 7).field(FieldNames.STATUS, "Idea").blockedBy(badState);
        final Item wrongRepository =
                new Item("example-project", 8).field(FieldNames.STATUS, "Idea");
        final Item fine = new Item("example-lib", 10).field(FieldNames.STATUS, "Idea");
        final Item again = new Item("example-lib", 10).field(FieldNames.STATUS, "Backlog");
        story.sub(parent).sub(blocked).sub(fine);
        parent.sub(wrongRepository);

        final IssueMapping.Mapped mapped =
                read(story, parent, badState, blocked, wrongRepository, fine, again);

        assertThat(mapped.issues())
                .as("only the issues the board can hold")
                .extracting(Issue::id, Issue::state)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("example-lib#4", "Idea"),
                        Tuple.tuple("example-lib#10", "Idea"));
        assertThat(mapped.faults()).as("one fault per item left out").hasSize(4);
        assertThat(mapped.stories().get(0).issuesWhole())
                .as("the story says an issue of it is missing")
                .isFalse();
    }

    @Test
    void aStatusAgainstGitHubsOpenOrClosedStaysTheTruthAndIsNamed() {
        // Harm: a finished issue reopened on GitHub silently counted as done, or as open, without a
        // word.
        final Item reopened = new Item("example-lib", 1).field(FieldNames.STATUS, "Finished");

        final IssueMapping.Mapped mapped = read(reopened);

        assertThat(mapped.issues().get(0).state()).as("the Status").isEqualTo("Finished");
        assertThat(mapped.faults())
                .as("the disagreement named")
                .singleElement()
                .asString()
                .contains("example-lib#1");
    }

    @Test
    void anIssueUnderAStoryHasNoIssueAsItsParent() {
        // Harm: a story read as an issue's parent, which the board would take for a Porelo
        // sub-issue.
        final Item story = new Item("example-project", 1).story();
        final Item issue = new Item("example-lib", 2).field(FieldNames.STATUS, "Idea");
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
                        "example-lib#1",
                        "example-lib",
                        SECRET_TITLE,
                        "Backlog",
                        "S",
                        List.of(),
                        null,
                        URI.create("https://github.com/example-org/example-lib/issues/1"),
                        SECRET_BODY,
                        Worker.MANUAL,
                        Risk.SMALL,
                        null,
                        null,
                        "Low",
                        List.of(),
                        List.of());
        final Item item = new Item("example-lib", 1);
        IssueMapping.fields(issue, "Low", 4).forEach(item::field);

        assertThat(read(item).issues()).as("the issue read back").containsExactly(issue);
    }

    @Test
    void theFieldsWrittenForAStoryReadBackAsTheSameStory() {
        // Harm: a story's given state or priority lost on a save.
        final Story story =
                new Story(
                        "example-project#1",
                        SECRET_TITLE,
                        "Implementing",
                        true,
                        "Low",
                        "L",
                        List.of(),
                        SECRET_BODY,
                        URI.create("https://github.com/example-org/example-project/issues/1"));
        final Item item = new Item("example-project", 1).story();
        IssueMapping.fields(story, 2).forEach(item::field);

        assertThat(read(item).stories()).as("the story read back").containsExactly(story);
    }

    @Test
    void aFaultNeverRepeatsWhatAPersonWroteOnGitHub() {
        // Harm: a private title or body shown in the log or to whoever reads the board's faults.
        final Item bad = new Item("example-lib", 1).field(FieldNames.STATUS, SECRET_TITLE + "\n");
        final Item tooLong =
                new Item("example-lib", 3).field(FieldNames.STATUS, SECRET_BODY.repeat(4));
        final Item badWorker =
                new Item("example-lib", 4)
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.WORKER, SECRET_TITLE);
        final Item badSize =
                new Item("example-lib", 2)
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.SIZE, SECRET_BODY);
        final Item accepted = new Item("example-lib", 5).field(FieldNames.STATUS, "Accepted");

        final List<String> faults = read(bad, tooLong, badWorker, badSize, accepted).faults();

        assertThat(String.join("\n", faults))
                .as("the faults' words")
                .doesNotContain(SECRET_TITLE)
                .doesNotContain(SECRET_BODY);
        assertThat(faults)
                .as("a Status that is no plain option name, named without it")
                .contains(
                        "example-lib#1 has a Status the workflow does not hold; move it into a"
                                + " state of the board to repair it",
                        "example-lib#3 has a Status the workflow does not hold; move it into a"
                                + " state of the board to repair it");
        assertThat(faults)
                .as("a plain option name named")
                .contains(
                        "example-lib#5 has the Status Accepted, which the workflow does not hold;"
                                + " move it into a state of the board to repair it");
    }

    @Test
    void anIssueListedUnderAlsoNeedsBelongsToTheSecondStoryToo() {
        // Harm: a story counted done, or started, while an issue it needs from another story is
        // not.
        final Item shared = new Item("example-lib", 3).field(FieldNames.STATUS, "Idea");
        final Item first = new Item("example-project", 1).story().sub(shared);
        final Item second =
                new Item("example-project", 2)
                        .story()
                        .body("Covers it.\n\n## Also needs\n\n- Example-Lib#3\n");
        final Item gone =
                new Item("example-project", 4)
                        .story()
                        .body("## Also needs\n\n- example-lib#3\n- example-lib#99\n");

        final IssueMapping.Mapped mapped = read(first, second, gone, shared);

        assertThat(mapped.stories())
                .as("each story with the issues it holds and those it also needs")
                .extracting(Story::id, Story::issues, Story::issuesWhole)
                .containsExactly(
                        Tuple.tuple("example-project#1", List.of("example-lib#3"), true),
                        Tuple.tuple("example-project#2", List.of("example-lib#3"), true),
                        Tuple.tuple("example-project#4", List.of("example-lib#3"), false));
        assertThat(mapped.faults())
                .as("an issue also needed that the board does not hold is named")
                .containsExactly(
                        "example-project#4 has a line under Also needs that names no issue of the"
                                + " project");
    }

    @Test
    void aSumFieldEditedByHandIsShownAsItsCommentsSumAndWrittenAgain() {
        // Harm: the velocity and the board read a hand edit instead of what the stages measured.
        final Item edited =
                new Item("example-lib", 3)
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.MINUTES, "99")
                        .field(FieldNames.STAGES, "2")
                        .stage("t1/implement", 10)
                        .stage("t2/implement", 5);
        final Item kept =
                new Item("example-lib", 4)
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.MINUTES, "7")
                        .field(FieldNames.VM_MINUTES, "1")
                        .field(FieldNames.TOKENS, "120")
                        .field(FieldNames.STAGES, "1")
                        .stage("t3/implement", 7);

        final IssueMapping.Mapped mapped = read(edited, kept);

        assertThat(mapped.sums().get("example-lib#3").minutes())
                .as("the comments' sum, not the field")
                .isEqualTo(15.0);
        assertThat(mapped.issues())
                .as("the minutes the board counts are the comments' sum too")
                .filteredOn(issue -> issue.id().equals("example-lib#3"))
                .singleElement()
                .extracting(Issue::minutes)
                .isEqualTo(15.0);
        assertThat(mapped.rewrite())
                .as("only the issue whose fields disagree is written again")
                .containsExactly("example-lib#3");
        assertThat(mapped.faults())
                .as("the disagreeing field is named")
                .containsExactly(
                        "example-lib#3 has a Minutes apart from its stage comments' sum, shown as"
                                + " the sum",
                        "example-lib#3 has a VM minutes apart from its stage comments' sum, shown"
                                + " as the sum",
                        "example-lib#3 has a Tokens apart from its stage comments' sum, shown as"
                                + " the sum");
    }

    @Test
    void anIssueWhoseCommentsWereNotReadShowsItsFieldsWithoutASumOrARewrite() {
        // Harm: a board read that reads no comments takes every issue's fields for disagreeing
        // with an empty sum, names a fault for each and writes them all to zero at the next save.
        final Item unread =
                new Item("example-lib", 3)
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
                new Item("example-project", 1)
                        .story()
                        .body("## Also needs\n\n" + "x".repeat(BodySections.LONGEST));

        final IssueMapping.Mapped mapped = read(story);

        assertThat(mapped.stories())
                .as("the story stays, its issues not known whole")
                .extracting(Story::id, Story::issuesWhole)
                .containsExactly(Tuple.tuple("example-project#1", false));
        assertThat(mapped.faults())
                .as("the body is named, not shown")
                .containsExactly(
                        "example-project#1 has a body too long to read its Also needs, shown"
                                + " without it");
        assertThat(mapped.stories().get(0).description())
                .as("the board holds no body beyond GitHub's bound")
                .isEmpty();
    }

    @Test
    void anIssueBodyBeyondGitHubsBoundStaysOnTheBoardWithoutItsBody() {
        // Harm: a body of megabytes held and sent to the window on every look at the board.
        final Item issue =
                new Item("example-lib", 3)
                        .field(FieldNames.STATUS, "Idea")
                        .body(SECRET_BODY + "x".repeat(BodySections.LONGEST));

        final IssueMapping.Mapped mapped = read(issue);

        assertThat(mapped.issues())
                .as("the issue stays, without its body")
                .extracting(Issue::id, Issue::text)
                .containsExactly(Tuple.tuple("example-lib#3", ""));
        assertThat(mapped.faults())
                .as("the issue named, never its body's text")
                .containsExactly(
                        "example-lib#3 has a body longer than GitHub takes, shown without it");
    }

    @Test
    void anIssueWhoseBodyCannotBeSplitIntoSectionsStaysOnTheBoardNamedWithWhatToRepair() {
        // Harm: a body whose sections a person must repair hides the issue, or no stage result
        // reaches it and nobody is told why.
        final Item unclear =
                new Item("example-lib", 3)
                        .field(FieldNames.STATUS, "Idea")
                        .body("Covers.\n\n## Plan\n\n```\n" + SECRET_BODY + "\n");

        final IssueMapping.Mapped mapped = read(unclear);

        assertThat(mapped.issues())
                .as("the issue on the board")
                .extracting(Issue::id)
                .containsExactly("example-lib#3");
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
        final Item shared = new Item("example-lib", 3).field(FieldNames.STATUS, "Idea");
        final Item story =
                new Item("example-project", 1)
                        .story()
                        .body("## Also needs\n\n- LB5\n- secret_words#1\n- example-lib#3\n");

        final IssueMapping.Mapped mapped = read(story, shared);

        assertThat(mapped.stories())
                .as("the issue named rightly is read, the story not whole")
                .extracting(Story::issues, Story::issuesWhole)
                .containsExactly(Tuple.tuple(List.of("example-lib#3"), false));
        assertThat(mapped.faults())
                .as("one fault per line, none repeating it")
                .containsExactly(
                        "example-project#1 has a line under Also needs that names no issue of the"
                                + " project",
                        "example-project#1 has a line under Also needs that names no issue of the"
                                + " project");
    }

    @Test
    void aSubIssueIsWorkedAsItsParentAndStandsInItsParentsRepository() {
        // Harm: an agent sent to a manual issue's part, at a lower risk than the issue it belongs
        // to.
        final Item part = new Item("example-lib", 4).field(FieldNames.STATUS, "Idea");
        final Item parent =
                new Item("example-lib", 3)
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.WORKER, "Manual")
                        .field(FieldNames.RISK, "harm")
                        .sub(part);
        final Item elsewhere = new Item("example-project", 6).field(FieldNames.STATUS, "Idea");
        final Item other =
                new Item("example-lib", 5).field(FieldNames.STATUS, "Idea").sub(elsewhere);

        final IssueMapping.Mapped mapped = read(parent, part, other, elsewhere);

        assertThat(mapped.issues())
                .as("the part worked as its parent, the one in another repository left out")
                .extracting(Issue::id, Issue::worker, Issue::risk)
                .containsExactly(
                        Tuple.tuple("example-lib#3", Worker.MANUAL, Risk.HARM),
                        Tuple.tuple("example-lib#4", Worker.MANUAL, Risk.HARM),
                        Tuple.tuple("example-lib#5", Worker.AGENT, Risk.ORDINARY));
        assertThat(mapped.faults())
                .as("the misplaced part is named")
                .containsExactly(
                        "example-project#6 stands in another repository than its parent and is"
                                + " left out");
    }

    @Test
    void aStoryWithASubIssueOutsideTheProjectOrNoPriorityIsNamed() {
        // Harm: a story shown whole and in its place while GitHub holds it otherwise.
        final Item story =
                new Item("example-project", 1)
                        .story()
                        .field(FieldNames.PRIORITY, "")
                        .subNode("I_elsewhere_9");

        final IssueMapping.Mapped mapped = read(story);

        assertThat(mapped.stories())
                .as("shown as Medium and not whole")
                .extracting(Story::priority, Story::issuesWhole)
                .containsExactly(Tuple.tuple("Medium", false));
        assertThat(mapped.faults())
                .as("both are named")
                .containsExactly(
                        "example-project#1 has a sub-issue outside the project",
                        "example-project#1 has no Priority, shown as Medium");
    }

    @Test
    void theBoardIsOrderedByThePriorityAndRankWrittenToTheFields() {
        // Harm: work started in another order than the person set, after a priority or rank is
        // saved.
        final List<Item> items = new ArrayList<>();
        int number = 1;
        for (final String[] given :
                List.of(
                        new String[] {"Low", "1"},
                        new String[] {"High", "7"},
                        new String[] {"High", "2"},
                        new String[] {"Medium", "1"})) {
            final Issue issue =
                    new Issue(
                            "example-lib#" + number,
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
            final Item item = new Item("example-lib", number++);
            IssueMapping.fields(issue, given[0], Integer.parseInt(given[1])).forEach(item::field);
            items.add(item);
        }

        assertThat(read(items.toArray(Item[]::new)).issues())
                .as("by priority, then by rank")
                .extracting(Issue::id)
                .containsExactly(
                        "example-lib#3", "example-lib#2", "example-lib#4", "example-lib#1");
    }

    @Test
    void anArchivedItemIsNotOnTheBoardAndItsSubIssueIsLeftOutWithIt() {
        // Harm: an archived issue shown as open work, or its part shown under a parent the board
        // does not hold.
        final Item archived =
                new Item("example-lib", 1).field(FieldNames.STATUS, "Idea").archived();
        final Item part = new Item("example-lib", 2).field(FieldNames.STATUS, "Idea");
        archived.sub(part);
        final Item live = new Item("example-lib", 3).field(FieldNames.STATUS, "Idea");

        final IssueMapping.Mapped mapped = read(archived, part, live);

        assertThat(mapped.issues())
                .as("only the live item")
                .extracting(Issue::id)
                .containsExactly("example-lib#3");
        assertThat(mapped.faults())
                .as("the part of the archived issue is named")
                .containsExactly("example-lib#2 depends on an issue left out and is left out too");
    }

    @Test
    void anIssueBlockedByAnIssueOutsideTheProjectStandsOnTheBoardWaitingOnIt() {
        // Harm: an issue waiting on another repository's vanishes from the board with everything
        // under it and everything blocked by it, so nobody sees the work at all.
        final Item story = new Item("example-project", 1).story();
        final Item blocked =
                new Item("example-lib", 2)
                        .field(FieldNames.STATUS, "Backlog")
                        .blockedByElsewhere("I_far_7", "other-org/elsewhere", 7)
                        .blockedByElsewhere("I_far_8", "other-org/elsewhere", 8);
        final Item part = new Item("example-lib", 3).field(FieldNames.STATUS, "Backlog");
        final Item after =
                new Item("example-lib", 4).field(FieldNames.STATUS, "Backlog").blockedBy(blocked);
        story.sub(blocked).sub(after);
        blocked.sub(part);

        final IssueMapping.Mapped mapped =
                read(
                        Map.of(
                                "I_far_7", new Snapshot.Waited("other-org/elsewhere", 7, false),
                                "I_far_8", new Snapshot.Waited("other-org/elsewhere", 8, true)),
                        story,
                        blocked,
                        part,
                        after);

        assertThat(mapped.faults()).as("no fault for a wait").isEmpty();
        assertThat(mapped.issues())
                .as("the waiting issue, its part and the issue blocked by it")
                .extracting(Issue::id)
                .containsExactlyInAnyOrder("example-lib#2", "example-lib#3", "example-lib#4");
        final Issue waiting =
                mapped.issues().stream()
                        .filter(i -> i.id().equals("example-lib#2"))
                        .findFirst()
                        .orElseThrow();
        assertThat(waiting.blockedBy()).as("no blocker of the board").isEmpty();
        assertThat(waiting.waitsOn())
                .as("each wait, open and done, with its link")
                .containsExactly(
                        new Wait(
                                "other-org/elsewhere#7",
                                false,
                                URI.create("https://github.com/other-org/elsewhere/issues/7")),
                        new Wait(
                                "other-org/elsewhere#8",
                                true,
                                URI.create("https://github.com/other-org/elsewhere/issues/8")));
        assertThat(
                        mapped.issues().stream()
                                .filter(i -> i.id().equals("example-lib#4"))
                                .findFirst()
                                .orElseThrow()
                                .blockedBy())
                .as("the issue blocked by the waiting one keeps it as its board blocker")
                .containsExactly("example-lib#2");
    }

    @Test
    void aWaitWhoseStateWasNotReadIsNotKnownDoneAndOneGitHubNamesBadlyIsNotReadable() {
        // Harm: a wait the person's token cannot read taken as done, so its issue is offered.
        final Item blocked =
                new Item("example-lib", 2)
                        .field(FieldNames.STATUS, "Backlog")
                        .blockedByElsewhere("I_far_7", "other-org/private", 7)
                        .blockedByElsewhere("I_odd", "no owner", 3);

        final IssueMapping.Mapped mapped =
                read(Map.of("I_far_7", new Snapshot.Waited("other-org/private", 7, null)), blocked);

        assertThat(mapped.issues().getFirst().waitsOn())
                .as("the unread state unknown, the odd name not readable")
                .containsExactly(
                        new Wait(
                                "other-org/private#7",
                                null,
                                URI.create("https://github.com/other-org/private/issues/7")),
                        Wait.unreadable());
    }

    @Test
    void anIssueBlockedByAStoryIsLeftOutWithEverythingUnderIt() {
        // Harm: a story taken as a work wait, so an issue waits on a whole story's issues unseen;
        // this fault stays meant, and the issue's part goes with it, as with every issue left out.
        final Item story = new Item("example-project", 1).story();
        final Item blocked =
                new Item("example-lib", 2).field(FieldNames.STATUS, "Backlog").blockedBy(story);
        final Item part = new Item("example-lib", 3).field(FieldNames.STATUS, "Backlog");
        blocked.sub(part);

        final IssueMapping.Mapped mapped = read(story, blocked, part);

        assertThat(mapped.issues()).as("the issue and its part left out").isEmpty();
        assertThat(mapped.faults())
                .as("named")
                .containsExactlyInAnyOrder(
                        "example-lib#2 is blocked by a story and is left out",
                        "example-lib#3 depends on an issue left out and is left out too");
    }

    @Test
    void anIssueWhoseStatusTheWorkflowDoesNotHoldIsListedOutsideTheBoardToBeMovedBack() {
        // Harm: an issue a person set to a Status the workflow lacks vanishes from the window, and
        // no move can bring it back.
        final Item story = new Item("example-project", 1).story();
        final Item accepted =
                new Item("example-lib", 2)
                        .field(FieldNames.STATUS, "Accepted")
                        .field(FieldNames.WORKER, "Manual");
        final Item none = new Item("example-lib", 3);
        final Item fine = new Item("example-lib", 4).field(FieldNames.STATUS, "Idea");
        story.sub(accepted).sub(fine);

        final IssueMapping.Mapped mapped = read(story, accepted, none, fine);

        assertThat(mapped.issues())
                .as("only the issues in a state of the workflow are on the board")
                .extracting(Issue::id)
                .containsExactly("example-lib#4");
        assertThat(mapped.outsideWorkflow())
                .as("each issue left out of the workflow, with what the window shows of it")
                .containsExactlyInAnyOrder(
                        new OutsideIssue(
                                "example-lib#2",
                                "example-lib",
                                SECRET_TITLE,
                                "Accepted",
                                "example-project#1",
                                null,
                                URI.create("https://github.com/example-org/example-lib/issues/2"),
                                Worker.MANUAL),
                        new OutsideIssue(
                                "example-lib#3",
                                "example-lib",
                                SECRET_TITLE,
                                "",
                                null,
                                null,
                                URI.create("https://github.com/example-org/example-lib/issues/3"),
                                Worker.AGENT));
        assertThat(mapped.faults())
                .as("each named with how a person repairs it")
                .containsExactlyInAnyOrder(
                        "example-lib#2 has the Status Accepted, which the workflow does not hold;"
                                + " move it into a state of the board to repair it",
                        "example-lib#3 has no Status; move it into a state of the board to repair"
                                + " it");
    }

    @Test
    void anIssueUnderAParentOutsideTheWorkflowIsWorkedAsItsParentAndStandsUnderIt() {
        // Harm: a sub-issue moved by hand into a working state an agent's parent enters only by
        // work, or moved open under a parent closed in the same batch.
        final Item parent =
                new Item("example-lib", 1)
                        .field(FieldNames.STATUS, "Accepted")
                        .field(FieldNames.WORKER, "Agent");
        final Item sub =
                new Item("example-lib", 2)
                        .field(FieldNames.STATUS, "Accepted")
                        .field(FieldNames.WORKER, "Manual");
        parent.sub(sub);

        assertThat(read(parent, sub).outsideWorkflow())
                .as("the sub-issue under its parent, with its parent's worker")
                .filteredOn(issue -> issue.id().equals("example-lib#2"))
                .singleElement()
                .extracting(OutsideIssue::parent, OutsideIssue::worker)
                .containsExactly("example-lib#1", Worker.AGENT);
    }

    @Test
    void anIssueOutsideTheWorkflowWithAnotherFaultIsLeftOutForThatFaultAlone() {
        // Harm: an issue offered for a move back onto the board that the next read leaves out
        // again, or worked as a parent of another repository.
        final Item story = new Item("example-project", 10).story();
        final Item blockedElsewhere =
                new Item("example-lib", 1).field(FieldNames.STATUS, "Accepted").blockedBy(story);
        final Item otherRepository =
                new Item("example-project", 2)
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.WORKER, "Manual");
        final Item crossing = new Item("example-lib", 3).field(FieldNames.STATUS, "Accepted");
        otherRepository.sub(crossing);
        final Item top = new Item("example-lib", 4).field(FieldNames.STATUS, "Idea");
        final Item middle = new Item("example-lib", 5).field(FieldNames.STATUS, "Idea");
        final Item deep = new Item("example-lib", 6).field(FieldNames.STATUS, "Accepted");
        top.sub(middle);
        middle.sub(deep);
        final Item leftOut =
                new Item("example-lib", 7).field(FieldNames.STATUS, "Idea").blockedBy(story);
        final Item dangling =
                new Item("example-lib", 8).field(FieldNames.STATUS, "Accepted").blockedBy(leftOut);
        final Item orphan = new Item("example-lib", 9).field(FieldNames.STATUS, "Accepted");
        orphan.parent = "I_elsewhere_7";

        final IssueMapping.Mapped mapped =
                read(
                        story,
                        blockedElsewhere,
                        otherRepository,
                        crossing,
                        top,
                        middle,
                        deep,
                        leftOut,
                        dangling,
                        orphan);

        assertThat(mapped.outsideWorkflow()).as("none offered for a move").isEmpty();
        assertThat(mapped.faults())
                .as("each named for its other fault alone, as a board issue with it is")
                .containsExactlyInAnyOrder(
                        "example-lib#1 is blocked by a story and is left out",
                        "example-lib#3 stands in another repository than its parent and is left"
                                + " out",
                        "example-lib#6 is a sub-issue of a sub-issue and is left out",
                        "example-lib#7 is blocked by a story and is left out",
                        "example-lib#8 depends on an issue left out and is left out too",
                        "example-lib#9 has a parent outside the project and is left out");
    }

    @Test
    void anIssuesEstimateAndMinutesAreReadFromTheirFieldsAndAnUnreadableOneIsNamed() {
        // Harm: minutes left from another figure than GitHub holds, or a bad one taken silently.
        final Item story = new Item("example-project", 1).story();
        final Item estimated =
                new Item("example-project", 2)
                        .commentsNotRead()
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.ESTIMATE, "32.5")
                        .field(FieldNames.MINUTES, "12");
        final Item bad =
                new Item("example-project", 3)
                        .commentsNotRead()
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.ESTIMATE, SECRET_BODY);
        final Item none =
                new Item("example-project", 4)
                        .commentsNotRead()
                        .field(FieldNames.STATUS, "Backlog");
        story.sub(estimated).sub(bad).sub(none);

        final IssueMapping.Mapped mapped = read(story, estimated, bad, none);

        assertThat(mapped.issues())
                .as("each issue's estimate and minutes")
                .extracting(Issue::id, Issue::estimate, Issue::minutes)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("example-project#2", 32.5, 12.0),
                        org.assertj.core.groups.Tuple.tuple("example-project#3", null, null),
                        org.assertj.core.groups.Tuple.tuple("example-project#4", null, null));
        assertThat(mapped.faults())
                .as("the unreadable estimate named by its issue and field, never its value")
                .containsExactly(
                        "example-project#3 has no number of minutes in its field Estimate, read as"
                                + " none");
    }

    @Test
    void anEstimateOrMinutesOutOfRangeIsNamedAndReadAsNone() {
        // Harm: one huge figure on GitHub overflows the sums and fails every stories read.
        final Item story = new Item("example-project", 1).story();
        final Item huge =
                new Item("example-project", 2)
                        .commentsNotRead()
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.ESTIMATE, "1e308")
                        .field(FieldNames.MINUTES, "-3");
        final Item zero =
                new Item("example-project", 3)
                        .commentsNotRead()
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.ESTIMATE, "0");
        story.sub(huge).sub(zero);

        final IssueMapping.Mapped mapped = read(story, huge, zero);

        assertThat(mapped.issues())
                .as("no figure out of its range is taken")
                .extracting(Issue::estimate, Issue::minutes)
                .containsOnly(org.assertj.core.groups.Tuple.tuple(null, null));
        assertThat(mapped.faults())
                .as("each named by its issue and field")
                .containsExactly(
                        "example-project#2 has no number of minutes in its field Estimate, read as"
                                + " none",
                        "example-project#2 has no number of minutes in its field Minutes, read as"
                                + " none",
                        "example-project#3 has no number of minutes in its field Estimate, read as"
                                + " none");
    }

    @Test
    void anIssuesPriorityIsReadFromItsFieldAndAnUnknownOneIsNamedAndReadAsNone() {
        // Harm: a story's rank judged against a priority GitHub does not hold, or a bad one
        // taken silently.
        final Item story = new Item("example-project", 1).story();
        final Item high =
                new Item("example-project", 2)
                        .commentsNotRead()
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.PRIORITY, "High");
        final Item unknown =
                new Item("example-project", 3)
                        .commentsNotRead()
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.PRIORITY, SECRET_BODY);
        final Item none =
                new Item("example-project", 4)
                        .commentsNotRead()
                        .field(FieldNames.STATUS, "Backlog");
        story.sub(high).sub(unknown).sub(none);

        final IssueMapping.Mapped mapped = read(story, high, unknown, none);

        assertThat(mapped.issues())
                .as("each issue's priority")
                .extracting(Issue::id, Issue::priority)
                .containsExactly(
                        Tuple.tuple("example-project#2", "High"),
                        Tuple.tuple("example-project#3", null),
                        Tuple.tuple("example-project#4", null));
        assertThat(mapped.faults())
                .as("the unknown priority named by its issue and field, never its value")
                .containsExactly(
                        "example-project#3 has a Priority the planner does not know, shown as"
                                + " none");
    }

    @Test
    void anIssuesPriorityStaysThroughItsStagesSumAndItsParentsWorker() {
        // Harm: a priority dropped on the way, so a story is judged against issues without one.
        final Item part =
                new Item("example-lib", 4)
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.PRIORITY, "Highest");
        final Item parent =
                new Item("example-lib", 3)
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.WORKER, "Manual")
                        .field(FieldNames.PRIORITY, "Low")
                        .field(FieldNames.MINUTES, "10")
                        .field(FieldNames.STAGES, "1")
                        .stage("t1/implement", 10)
                        .sub(part);

        final IssueMapping.Mapped mapped = read(parent, part);

        assertThat(mapped.issues())
                .as("the summed parent and its part, worked as the parent, each with its own")
                .extracting(Issue::id, Issue::minutes, Issue::worker, Issue::priority)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("example-lib#3", 10.0, Worker.MANUAL, "Low"),
                        Tuple.tuple("example-lib#4", null, Worker.MANUAL, "Highest"));
    }
}

package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.groups.Tuple;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.Warning;
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
        private @Nullable String type;
        private boolean open = true;
        private boolean archived;
        private String body = SECRET_BODY;

        private Item(final String repository, final int number) {
            this.repository = repository;
            this.number = number;
        }

        private Item field(final String name, final String value) {
            fields.put(name, value);
            return this;
        }

        private Item story() {
            type = IssueMapping.STORY_TYPE;
            fields.putIfAbsent(FieldNames.PRIORITY, "Medium");
            return this;
        }

        private Item labeled(final String label) {
            labels.add(label);
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

        private Item body(final String text) {
            body = text;
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
                    URI.create(
                            "https://github.com/example-org/" + repository + "/issues/" + number),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    archived,
                    named,
                    0,
                    type);
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
                        "Item Example-Lib#3 has no valid tracker reference and is left out");
        assertThat(mapped.stories().get(0).warnings())
                .as("the story warned once for its sub-issues outside the project")
                .containsExactly(Warning.issueOutsideProject());
    }

    @Test
    void aStoryWhoseOnlySubIssueLiesOutsideTheProjectSaysSo() {
        // Harm: a story is shown whole while one of its issues stands where the board cannot see.
        final Item story = new Item("example-project", 1).story();
        final Item outside = new Item("elsewhere", 9).field(FieldNames.STATUS, "Idea");
        story.sub(outside);

        final IssueMapping.Mapped mapped = read(story, outside);

        assertThat(mapped.faults())
                .as("the foreign item left out")
                .containsExactly(
                        "Item elsewhere#9 lies outside the project's repositories and is left out");
        assertThat(mapped.stories())
                .as("the story warned on its card and not whole")
                .extracting(Story::warnings, Story::issuesWhole)
                .containsExactly(Tuple.tuple(List.of(Warning.issueOutsideProject()), false));
    }

    @Test
    void aStoryKnownByItsIssueTypeAloneMapsAsAStoryAndALabelMakesNone() {
        // Harm: a story made by hand on GitHub, typed Story without the label, showed as an issue
        // held in Idea, and an issue labeled story vanished from the board as a story.
        final Item typed = new Item("example-project", 1).story();
        final Item labeled =
                new Item("example-project", 2).labeled("story").field(FieldNames.STATUS, "Idea");

        final IssueMapping.Mapped mapped = read(typed, labeled);

        assertThat(mapped.stories())
                .as("the typed story")
                .extracting(org.fuin.porelo.planner.store.Story::id)
                .containsExactly("example-project#1");
        assertThat(mapped.issues())
                .as("the labeled issue")
                .extracting(org.fuin.porelo.planner.store.Issue::id)
                .containsExactly("example-project#2");
        assertThat(mapped.faults()).as("neither is a fault").isEmpty();
    }

    @Test
    void anItemTypedStoryOutsideTheOwnRepositoryIsAWarnedIssueAndKeepsItsSubIssues() {
        // Harm: one issue typed Story by hand in another repository hides that issue and every
        // issue under it from the board, or shows as a story the board cannot hold.
        final Item own = new Item("example-project", 1).story();
        final Item typed = new Item("example-lib", 3).story();
        final Item under = new Item("example-lib", 4).field(FieldNames.STATUS, "Idea");
        typed.sub(under);

        final IssueMapping.Mapped mapped = read(own, typed, under);

        assertThat(mapped.issues())
                .as("the typed item as an issue, warned, and its sub-issue under it")
                .extracting(Issue::id, Issue::parent, Issue::warnings)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(
                                "example-lib#3",
                                null,
                                List.of(Warning.storyOutsideOwnRepository())),
                        Tuple.tuple("example-lib#4", "example-lib#3", List.of()));
        assertThat(mapped.stories()).extracting(Story::id).containsExactly("example-project#1");
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
    }

    @Test
    void aSubIssueOfASubIssueIsShownWithoutItsParentWhicheverOrderItsItemsCome() {
        // Harm: a tree deeper than the board holds hides every issue below its second level, and
        // the issues waiting on them.
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
                .as("every issue shown, those below a sub-issue without their parent and warned")
                .extracting(Issue::id, Issue::parent, Issue::warnings)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("example-lib#2", null, List.of()),
                        Tuple.tuple("example-lib#3", "example-lib#2", List.of()),
                        Tuple.tuple("example-lib#4", null, List.of(Warning.subIssueOfSubIssue())),
                        Tuple.tuple("example-lib#5", null, List.of(Warning.subIssueOfSubIssue())),
                        Tuple.tuple("example-lib#6", null, List.of()));
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
        assertThat(mapped.stories().get(0).issuesWhole())
                .as("the story holds every issue it names")
                .isTrue();
    }

    @Test
    void anItemEditedByHandIntoWhatTheBoardCannotHoldIsShownWarned() {
        // Harm: one hand edit on GitHub hides an issue, or every issue that hangs on it, from
        // the board.
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
                .as("every issue, warned where the board cannot hold it")
                .extracting(
                        Issue::id, Issue::state, Issue::parent, Issue::blockedBy, Issue::warnings)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("example-lib#4", "Idea", null, List.of(), List.of()),
                        Tuple.tuple(
                                "example-lib#6",
                                "Somewhere",
                                null,
                                List.of(),
                                List.of(Warning.stateOutsideWorkflow())),
                        Tuple.tuple(
                                "example-lib#7", "Idea", null, List.of("example-lib#6"), List.of()),
                        Tuple.tuple(
                                "example-project#8",
                                "Idea",
                                null,
                                List.of(),
                                List.of(Warning.parentInOtherRepository())),
                        Tuple.tuple(
                                "example-lib#10",
                                "Idea",
                                null,
                                List.of(),
                                List.of(Warning.idTwice())),
                        Tuple.tuple(
                                "example-lib#10",
                                "Backlog",
                                null,
                                List.of(),
                                List.of(Warning.idTwice())));
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
        assertThat(mapped.stories().get(0).issuesWhole())
                .as("the story holds every issue it names")
                .isTrue();
    }

    @Test
    void aStatusAgainstGitHubsOpenOrClosedStaysTheTruthAndIsWarnedOnTheItem() {
        // Harm: a finished issue reopened on GitHub, or an issue a merge closed under an open
        // state, counted by its state with no sign on its card that GitHub says otherwise.
        final Item reopened = new Item("example-lib", 1).field(FieldNames.STATUS, "Finished");
        final Item closed = new Item("example-lib", 2).field(FieldNames.STATUS, "Backlog").closed();
        final Item unstated = new Item("example-lib", 3).closed();
        final Item agreeing = new Item("example-lib", 4).field(FieldNames.STATUS, "Backlog");

        final IssueMapping.Mapped mapped = read(reopened, closed, unstated, agreeing);

        assertThat(mapped.issues())
                .as("each by its Status, a disagreement warned without holding work")
                .extracting(Issue::id, Issue::state, Issue::warnings)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(
                                "example-lib#1",
                                "Finished",
                                List.of(Warning.openUnderClosedState())),
                        Tuple.tuple(
                                "example-lib#2",
                                "Backlog",
                                List.of(Warning.closedUnderOpenState())),
                        Tuple.tuple(
                                "example-lib#3", "Idea", List.of(Warning.closedUnderOpenState())),
                        Tuple.tuple("example-lib#4", "Backlog", List.of()));
        assertThat(Warning.closedUnderOpenState().holdsWork()).as("work goes on").isFalse();
        assertThat(Warning.openUnderClosedState().holdsWork()).as("work goes on").isFalse();
        assertThat(mapped.faults()).as("no fault").isEmpty();
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

        assertThat(read(item).issues())
                .as("the issue read back, with GitHub's version of its item")
                .containsExactly(issue.withVersion(ItemVersions.of(item.view())));
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

        assertThat(read(item).stories())
                .as("the story read back, with GitHub's version of its item")
                .containsExactly(story.withVersion(ItemVersions.of(item.view())));
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

        final IssueMapping.Mapped mapped = read(bad, tooLong, badWorker, badSize, accepted);
        final List<String> faults = mapped.faults();

        assertThat(String.join("\n", faults))
                .as("the faults' words")
                .doesNotContain(SECRET_TITLE)
                .doesNotContain(SECRET_BODY);
        assertThat(faults)
                .as("a Status outside the workflow is no fault, whatever it holds")
                .noneMatch(fault -> fault.contains("Status"));
        assertThat(mapped.issues())
                .as("each issue whose Status is outside the workflow warned instead")
                .filteredOn(issue -> issue.warnings().contains(Warning.stateOutsideWorkflow()))
                .extracting(Issue::id)
                .containsExactlyInAnyOrder("example-lib#1", "example-lib#3", "example-lib#5");
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
        assertThat(mapped.stories())
                .as("the story whose line names no issue the board shows is warned")
                .extracting(Story::id, Story::warnings)
                .containsExactly(
                        Tuple.tuple("example-project#1", List.of()),
                        Tuple.tuple("example-project#2", List.of()),
                        Tuple.tuple("example-project#4", List.of(Warning.alsoNeedsNotOnBoard())));
        assertThat(mapped.faults()).as("no fault").isEmpty();
    }

    @Test
    void aNumberFieldSetByHandIsShownAsGitHubHoldsIt() {
        // Harm: a person's override of an issue's minutes replaced on the board by a sum of the
        // stage comments, named as a fault and written back over at the next save.
        final Item edited =
                new Item("example-lib", 3)
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.MINUTES, "99")
                        .field(FieldNames.STAGES, "2");

        final IssueMapping.Mapped mapped = read(edited);

        assertThat(mapped.issues())
                .as("the minutes as the field holds them, with no warning")
                .extracting(Issue::minutes, Issue::warnings)
                .containsExactly(Tuple.tuple(99.0, List.of()));
        assertThat(mapped.faults()).as("no fault").isEmpty();
    }

    @Test
    void aStoryBodyTooLongToReadIsWarnedNotABoardThatFailsToLoad() {
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
        assertThat(mapped.stories().get(0).warnings())
                .as("the story warned, its body not shown")
                .containsExactly(Warning.bodyTooLong());
        assertThat(mapped.faults()).as("no fault").isEmpty();
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
        assertThat(mapped.issues().get(0).warnings())
                .as("the issue warned in fixed words, never its body's text")
                .containsExactly(Warning.bodyTooLong());
        assertThat(mapped.faults()).as("no fault").isEmpty();
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
    void aLineUnderAlsoNeedsThatNamesNoIssueOfTheProjectIsWarnedWithoutItsText() {
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
        assertThat(mapped.stories().get(0).warnings())
                .as("warned once on the story, its fixed words repeating no line")
                .containsExactly(Warning.alsoNeedsNotOnBoard());
        assertThat(mapped.faults()).as("no fault").isEmpty();
    }

    @Test
    void aSubIssueIsWorkedAsItsParentAndStandsInItsParentsRepository() {
        // Harm: an agent sent to a manual issue's part, at a lower risk than the issue it belongs
        // to; or a part in another repository than its parent hidden from the board.
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
                .as("the part worked as its parent, the one in another repository without it")
                .extracting(Issue::id, Issue::parent, Issue::worker, Issue::risk, Issue::warnings)
                .containsExactly(
                        Tuple.tuple("example-lib#3", null, Worker.MANUAL, Risk.HARM, List.of()),
                        Tuple.tuple(
                                "example-lib#4",
                                "example-lib#3",
                                Worker.MANUAL,
                                Risk.HARM,
                                List.of()),
                        Tuple.tuple("example-lib#5", null, Worker.AGENT, Risk.ORDINARY, List.of()),
                        Tuple.tuple(
                                "example-project#6",
                                null,
                                Worker.AGENT,
                                Risk.ORDINARY,
                                List.of(Warning.parentInOtherRepository())));
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
    }

    @Test
    void aStoryUnderAnIssueOrWithAnUnknownSizeOrPriorityIsShownWarned() {
        // Harm: a story a person put under an issue, or gave a Size or Priority the planner does
        // not know, shown as if GitHub held it so, with no sign on its card.
        final Item parent = new Item("example-lib", 3).field(FieldNames.STATUS, "Idea");
        final Item story =
                new Item("example-project", 1)
                        .story()
                        .field(FieldNames.SIZE, "XXL")
                        .field(FieldNames.PRIORITY, "Urgent");
        story.parent = parent.node();

        final IssueMapping.Mapped mapped = read(story, parent);

        assertThat(mapped.stories())
                .as("the story without a parent, a size or its priority, each warned")
                .extracting(Story::size, Story::priority, Story::warnings)
                .containsExactly(
                        Tuple.tuple(
                                null,
                                "Medium",
                                List.of(
                                        Warning.storyUnderIssue(),
                                        Warning.unknownPriority(),
                                        Warning.unknownSize())));
        assertThat(Warning.storyUnderIssue().holdsWork()).as("work goes on").isFalse();
        assertThat(mapped.faults()).as("no fault").isEmpty();
    }

    @Test
    void anIssueWithASizeThePlannerDoesNotKnowIsShownWithoutOneAndWarned() {
        // Harm: an issue's Size set by hand to an option the planner lacks shown as having no
        // size, with no sign why on its card.
        final Item issue =
                new Item("example-lib", 3)
                        .field(FieldNames.STATUS, "Idea")
                        .field(FieldNames.SIZE, "XXL");

        final IssueMapping.Mapped mapped = read(issue);

        assertThat(mapped.issues())
                .extracting(Issue::size, Issue::warnings)
                .containsExactly(Tuple.tuple(null, List.of(Warning.unknownSize())));
        assertThat(Warning.unknownSize().holdsWork()).as("work goes on").isFalse();
        assertThat(mapped.faults()).as("no fault").isEmpty();
    }

    @Test
    void aStoryWithASubIssueOutsideTheProjectOrNoPriorityIsWarned() {
        // Harm: a story shown whole and in its place while GitHub holds it otherwise, with no
        // sign on its card.
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
        assertThat(mapped.stories().get(0).warnings())
                .as("both warned")
                .containsExactly(Warning.issueOutsideProject(), Warning.noPriority());
        assertThat(mapped.faults()).as("no fault").isEmpty();
    }

    @Test
    void aStoryNotWholeNeverFollowsTheIssuesShownIntoAClose() {
        // Harm: a story without a Status shown Finished, its work done, while an issue of it the
        // board cannot see is still open.
        final Item partial =
                new Item("example-project", 1)
                        .story()
                        .field(FieldNames.PRIORITY, "High")
                        .subNode("I_elsewhere_9");
        final Item done =
                new Item("example-project", 2).field(FieldNames.STATUS, "Finished").closed();
        partial.sub(done);
        final Item whole =
                new Item("example-project", 3).story().field(FieldNames.PRIORITY, "High");
        final Item alsoDone =
                new Item("example-project", 4).field(FieldNames.STATUS, "Finished").closed();
        whole.sub(alsoDone);

        final IssueMapping.Mapped mapped = read(partial, done, whole, alsoDone);

        assertThat(mapped.stories())
                .as("the story not whole in the workflow's last open state, the whole one finished")
                .extracting(Story::id, Story::issuesWhole, Story::state)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("example-project#1", false, "Implementing"),
                        Tuple.tuple("example-project#3", true, "Finished"));
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
    void anArchivedItemIsNotOnTheBoardAndItsSubIssueIsShownWithoutIt() {
        // Harm: an archived issue shown as open work, or its part hidden with it or shown under a
        // parent the board does not hold.
        final Item archived =
                new Item("example-lib", 1).field(FieldNames.STATUS, "Idea").archived();
        final Item part = new Item("example-lib", 2).field(FieldNames.STATUS, "Idea");
        archived.sub(part);
        final Item live = new Item("example-lib", 3).field(FieldNames.STATUS, "Idea");

        final IssueMapping.Mapped mapped = read(archived, part, live);

        assertThat(mapped.issues())
                .as("the live items, the part without its archived parent and warned")
                .extracting(Issue::id, Issue::parent, Issue::warnings)
                .containsExactly(
                        Tuple.tuple("example-lib#2", null, List.of(Warning.dependsOnUnplaced())),
                        Tuple.tuple("example-lib#3", null, List.of()));
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
    }

    @Test
    void anIssueBlockedByAClosedArchivedIssueWaitsOnItAsDoneAndIsNotHeld() {
        // Harm: GitHub archives a finished, closed issue on its own, and every issue it blocked is
        // held for good, while a closed blocker outside the project would have let it start.
        final Item done =
                new Item("example-lib", 3).field(FieldNames.STATUS, "Finished").closed().archived();
        final Item blocked =
                new Item("example-lib", 4).field(FieldNames.STATUS, "Backlog").blockedBy(done);

        final IssueMapping.Mapped mapped = read(done, blocked);

        assertThat(mapped.issues())
                .as("the blocked issue, unwarned, waiting on its blocker as done")
                .extracting(Issue::id, Issue::blockedBy, Issue::waitsOn, Issue::warnings)
                .containsExactly(
                        Tuple.tuple(
                                "example-lib#4",
                                List.of(),
                                List.of(
                                        new Wait(
                                                "example-org/example-lib#3",
                                                true,
                                                URI.create(
                                                        "https://github.com/example-org/example-lib/issues/3"))),
                                List.of()));
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
    }

    @Test
    void anIssueBlockedByAnOpenArchivedIssueIsShownWithoutThatBlockerAndHeld() {
        // Harm: an issue blocked by open work someone archived starts as if that work were done.
        final Item open = new Item("example-lib", 3).field(FieldNames.STATUS, "Backlog").archived();
        final Item blocked =
                new Item("example-lib", 4).field(FieldNames.STATUS, "Backlog").blockedBy(open);

        final IssueMapping.Mapped mapped = read(open, blocked);

        assertThat(mapped.issues())
                .as("the blocked issue without the link, warned, and no wait")
                .extracting(Issue::id, Issue::blockedBy, Issue::waitsOn, Issue::warnings)
                .containsExactly(
                        Tuple.tuple(
                                "example-lib#4",
                                List.of(),
                                List.of(),
                                List.of(Warning.dependsOnUnplaced())));
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
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
        assertThat(waiting.warnings()).as("a wait, never a fault or a warning").isEmpty();
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
    void anIssueBlockedByAStoryIsShownWithoutThatBlockerAndHeld() {
        // Harm: an issue a person blocked by a story vanishes with everything under it; or the
        // story is taken as a wait, so the issue waits on a whole story's issues unseen.
        final Item story = new Item("example-project", 1).story();
        final Item blocked =
                new Item("example-lib", 2).field(FieldNames.STATUS, "Backlog").blockedBy(story);
        final Item part = new Item("example-lib", 3).field(FieldNames.STATUS, "Backlog");
        blocked.sub(part);

        final IssueMapping.Mapped mapped = read(story, blocked, part);

        assertThat(mapped.issues())
                .as("the issue without the story as a blocker, warned, and its part under it")
                .extracting(
                        Issue::id, Issue::parent, Issue::blockedBy, Issue::waitsOn, Issue::warnings)
                .containsExactly(
                        Tuple.tuple(
                                "example-lib#2",
                                null,
                                List.of(),
                                List.of(),
                                List.of(Warning.blockedByStory())),
                        Tuple.tuple(
                                "example-lib#3", "example-lib#2", List.of(), List.of(), List.of()));
        assertThat(Warning.blockedByStory().holdsWork()).as("no work starts on it").isTrue();
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
    }

    @Test
    void anIssueWhoseStatusTheWorkflowDoesNotHoldStandsUnderItsStoryWarned() {
        // Harm: an issue a person set to a Status the workflow lacks leaves its story for a list
        // of its own, so the story is judged without it and the window shows it apart.
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
                .as("every issue on the board, one without a Status in the first state")
                .extracting(Issue::id, Issue::state, Issue::worker, Issue::warnings)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(
                                "example-lib#2",
                                "Accepted",
                                Worker.MANUAL,
                                List.of(Warning.stateOutsideWorkflow())),
                        Tuple.tuple("example-lib#3", "Idea", Worker.AGENT, List.of()),
                        Tuple.tuple("example-lib#4", "Idea", Worker.AGENT, List.of()));
        assertThat(mapped.stories())
                .as("the issue under its story")
                .singleElement()
                .extracting(Story::issues)
                .isEqualTo(List.of("example-lib#2", "example-lib#4"));
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
    }

    @Test
    void aCopyOfAnIdNamedTwiceWhoseStatusIsOutsideTheWorkflowLeavesTheBoardReadable() {
        // Harm: one copy of a twice-named issue set to a Status the workflow lacks makes the
        // whole board unreadable, so no story or issue of the project is shown.
        final Item somewhere = new Item("example-lib", 2).field(FieldNames.STATUS, "Somewhere");
        final Item idea = new Item("example-lib", 2).field(FieldNames.STATUS, "Idea");

        final IssueMapping.Mapped mapped = read(somewhere, idea);

        assertThat(mapped.issues())
                .as("both copies, the one outside the workflow warned for that too")
                .extracting(Issue::state, Issue::warnings)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(
                                "Somewhere",
                                List.of(Warning.idTwice(), Warning.stateOutsideWorkflow())),
                        Tuple.tuple("Idea", List.of(Warning.idTwice())));
        assertThat(
                        new Board(
                                        new Revision("r"),
                                        WORKFLOW,
                                        mapped.stories(),
                                        mapped.issues(),
                                        new Work(Map.of(), List.of()),
                                        CONFIG.repositories(),
                                        mapped.faults(),
                                        "example-org")
                                .issues())
                .as("the board built")
                .hasSize(2);
    }

    @Test
    void aStoryIsNeverFinishedByItsIssuesWhileOneOfThemStandsOutsideTheWorkflow() {
        // Harm: a story shows as finished over an issue in doubt, which a person's own move to
        // Finished is refused for, or one whose issues all stand outside the workflow shows in
        // the first state here and as having none on a Git board.
        final Item finishing = new Item("example-project", 1).story();
        final Item done = new Item("example-lib", 2).field(FieldNames.STATUS, "Finished");
        final Item accepted = new Item("example-lib", 3).field(FieldNames.STATUS, "Accepted");
        finishing.sub(done).sub(accepted);
        final Item lost = new Item("example-project", 4).story();
        final Item parked = new Item("example-lib", 5).field(FieldNames.STATUS, "Parked");
        lost.sub(parked);
        final Item open = new Item("example-project", 6).story();
        final Item backlog = new Item("example-lib", 7).field(FieldNames.STATUS, "Backlog");
        final Item elsewhere = new Item("example-lib", 8).field(FieldNames.STATUS, "Accepted");
        open.sub(backlog).sub(elsewhere);

        final IssueMapping.Mapped mapped =
                read(finishing, done, accepted, lost, parked, open, backlog, elsewhere);

        assertThat(mapped.stories())
                .as("no state its issues give while one is in doubt, an open one still told")
                .extracting(Story::id, Story::state, Story::warnings)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("example-project#1", "", List.of(Warning.noState())),
                        Tuple.tuple("example-project#4", "", List.of(Warning.noState())),
                        Tuple.tuple("example-project#6", "Backlog", List.of()));
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

        assertThat(read(parent, sub).issues())
                .as("the sub-issue under its parent, with its parent's worker")
                .filteredOn(issue -> issue.id().equals("example-lib#2"))
                .singleElement()
                .extracting(Issue::parent, Issue::worker)
                .containsExactly("example-lib#1", Worker.AGENT);
    }

    @Test
    void aStoryWhoseStatusTheWorkflowDoesNotHoldIsShownWarnedWithItsIssues() {
        // Harm: a story given a Status by hand that the workflow lacks hides itself and leaves
        // its issues shown as if they belonged to no story.
        final Item story =
                new Item("example-project", 1).story().field(FieldNames.STATUS, "Accepted");
        final Item issue = new Item("example-lib", 2).field(FieldNames.STATUS, "Idea");
        story.sub(issue);

        final IssueMapping.Mapped mapped = read(story, issue);

        assertThat(mapped.stories())
                .as("the story in its own state, warned, its issue in it")
                .extracting(Story::id, Story::state, Story::issues, Story::warnings)
                .containsExactly(
                        Tuple.tuple(
                                "example-project#1",
                                "Accepted",
                                List.of("example-lib#2"),
                                List.of(Warning.stateOutsideWorkflow())));
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
    }

    @Test
    void anIssueOutsideTheWorkflowStandsOnTheBoardWarnedForItAndForEachOtherFault() {
        // Harm: an issue outside the workflow that the board cannot hold for another reason too
        // is hidden; or it is offered for a move back that the next read refuses again.
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
        final Item blockedByStory =
                new Item("example-lib", 7).field(FieldNames.STATUS, "Idea").blockedBy(story);
        final Item waiting =
                new Item("example-lib", 8)
                        .field(FieldNames.STATUS, "Accepted")
                        .blockedBy(blockedByStory);
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
                        blockedByStory,
                        waiting,
                        orphan);

        final Warning outside = Warning.stateOutsideWorkflow();
        assertThat(mapped.issues())
                .as("each warned issue on the board, outside the workflow warned too")
                .filteredOn(issue -> !issue.warnings().isEmpty())
                .extracting(Issue::id, Issue::warnings)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("example-lib#1", List.of(Warning.blockedByStory(), outside)),
                        Tuple.tuple(
                                "example-lib#3",
                                List.of(Warning.parentInOtherRepository(), outside)),
                        Tuple.tuple(
                                "example-lib#6", List.of(Warning.subIssueOfSubIssue(), outside)),
                        Tuple.tuple("example-lib#7", List.of(Warning.blockedByStory())),
                        Tuple.tuple("example-lib#8", List.of(outside)),
                        Tuple.tuple(
                                "example-lib#9", List.of(Warning.parentOutsideProject(), outside)));
        assertThat(mapped.faults()).as("nothing left out").isEmpty();
    }

    @Test
    void aStageSumFieldOutOfItsBoundsIsWarnedByNameThoughTheBoardShowsItNot() {
        // Harm: a VM minutes, Tokens or Stages set by hand to what no stage can add to, with no
        // sign on the card until a stage's save is refused over it.
        final Item fine =
                new Item("example-lib", 1)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.VM_MINUTES, "3")
                        .field(FieldNames.TOKENS, "300")
                        .field(FieldNames.STAGES, "2");
        final Item bad =
                new Item("example-lib", 2)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.VM_MINUTES, "-1")
                        .field(FieldNames.TOKENS, "1.5")
                        .field(FieldNames.STAGES, "1e300");

        final IssueMapping.Mapped mapped = read(fine, bad);

        assertThat(mapped.issues())
                .as("each field out of its bounds warned by its name")
                .extracting(Issue::id, Issue::warnings)
                .containsExactly(
                        Tuple.tuple("example-lib#1", List.of()),
                        Tuple.tuple(
                                "example-lib#2",
                                List.of(
                                        Warning.unreadableNumber(FieldNames.VM_MINUTES),
                                        Warning.unreadableNumber(FieldNames.TOKENS),
                                        Warning.unreadableNumber(FieldNames.STAGES))));
    }

    @Test
    void anIssuesEstimateAndMinutesAreReadFromTheirFieldsAndAnUnreadableOneIsWarned() {
        // Harm: minutes left from another figure than GitHub holds, or a bad one taken silently.
        final Item story = new Item("example-project", 1).story();
        final Item estimated =
                new Item("example-project", 2)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.ESTIMATE, "32.5")
                        .field(FieldNames.MINUTES, "12");
        final Item bad =
                new Item("example-project", 3)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.ESTIMATE, SECRET_BODY);
        final Item none = new Item("example-project", 4).field(FieldNames.STATUS, "Backlog");
        story.sub(estimated).sub(bad).sub(none);

        final IssueMapping.Mapped mapped = read(story, estimated, bad, none);

        assertThat(mapped.issues())
                .as("each issue's estimate and minutes")
                .extracting(Issue::id, Issue::estimate, Issue::minutes)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("example-project#2", 32.5, 12.0),
                        org.assertj.core.groups.Tuple.tuple("example-project#3", null, null),
                        org.assertj.core.groups.Tuple.tuple("example-project#4", null, null));
        assertThat(mapped.issues())
                .as("the unreadable estimate warned in fixed words, never its value")
                .extracting(Issue::id, Issue::warnings)
                .containsExactly(
                        Tuple.tuple("example-project#2", List.of()),
                        Tuple.tuple(
                                "example-project#3",
                                List.of(Warning.unreadableNumber(FieldNames.ESTIMATE))),
                        Tuple.tuple("example-project#4", List.of()));
        assertThat(mapped.faults()).as("no fault").isEmpty();
    }

    @Test
    void anEstimateOrMinutesOutOfRangeIsWarnedAndReadAsNone() {
        // Harm: one huge figure on GitHub overflows the sums and fails every stories read.
        final Item story = new Item("example-project", 1).story();
        final Item huge =
                new Item("example-project", 2)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.ESTIMATE, "1e308")
                        .field(FieldNames.MINUTES, "-3");
        final Item zero =
                new Item("example-project", 3)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.ESTIMATE, "0");
        story.sub(huge).sub(zero);

        final IssueMapping.Mapped mapped = read(story, huge, zero);

        assertThat(mapped.issues())
                .as("no figure out of its range is taken")
                .extracting(Issue::estimate, Issue::minutes)
                .containsOnly(org.assertj.core.groups.Tuple.tuple(null, null));
        assertThat(mapped.issues())
                .as("each field warned on its card, by its name")
                .extracting(Issue::warnings)
                .containsExactly(
                        List.of(
                                Warning.unreadableNumber(FieldNames.ESTIMATE),
                                Warning.unreadableNumber(FieldNames.MINUTES)),
                        List.of(Warning.unreadableNumber(FieldNames.ESTIMATE)));
        assertThat(Warning.unreadableNumber(FieldNames.MINUTES).holdsWork())
                .as("work goes on")
                .isFalse();
        assertThat(mapped.faults()).as("no fault").isEmpty();
    }

    @Test
    void anIssuesPriorityIsReadFromItsFieldAndAnUnknownOneIsWarnedAndReadAsNone() {
        // Harm: a story's rank judged against a priority GitHub does not hold, or a bad one
        // taken silently.
        final Item story = new Item("example-project", 1).story();
        final Item high =
                new Item("example-project", 2)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.PRIORITY, "High");
        final Item unknown =
                new Item("example-project", 3)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.PRIORITY, SECRET_BODY);
        final Item none = new Item("example-project", 4).field(FieldNames.STATUS, "Backlog");
        story.sub(high).sub(unknown).sub(none);

        final IssueMapping.Mapped mapped = read(story, high, unknown, none);

        assertThat(mapped.issues())
                .as("each issue's priority")
                .extracting(Issue::id, Issue::priority)
                .containsExactly(
                        Tuple.tuple("example-project#2", "High"),
                        Tuple.tuple("example-project#3", null),
                        Tuple.tuple("example-project#4", null));
        assertThat(mapped.issues())
                .as("the unknown priority warned in fixed words, never its value; none is no sign")
                .extracting(Issue::id, Issue::warnings)
                .containsExactly(
                        Tuple.tuple("example-project#2", List.of()),
                        Tuple.tuple("example-project#3", List.of(Warning.unknownPriority())),
                        Tuple.tuple("example-project#4", List.of()));
        assertThat(mapped.faults()).as("no fault").isEmpty();
    }

    @Test
    void anUnknownWorkerOrRiskIsWarnedOnItsIssueAndNamedNowhereElse() {
        // Harm: an issue a person gave a Worker or Risk the planner does not know is proposed to
        // an agent under ordinary care, with nothing on its card to say so.
        final Item story = new Item("example-project", 1).story();
        final Item worker =
                new Item("example-project", 2)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.WORKER, SECRET_TITLE);
        final Item risk =
                new Item("example-project", 3)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.RISK, "Harm");
        final Item known =
                new Item("example-project", 4)
                        .field(FieldNames.STATUS, "Backlog")
                        .field(FieldNames.WORKER, "Manual")
                        .field(FieldNames.RISK, "harm");
        story.sub(worker).sub(risk).sub(known);

        final IssueMapping.Mapped mapped = read(story, worker, risk, known);

        assertThat(mapped.issues())
                .as(
                        "each issue shown, an unknown worker as an agent's, an unknown risk at the most care, and warned")
                .extracting(Issue::id, Issue::worker, Issue::risk, Issue::warnings)
                .containsExactly(
                        Tuple.tuple(
                                "example-project#2",
                                Worker.AGENT,
                                Risk.ORDINARY,
                                List.of(Warning.unknownWorker())),
                        Tuple.tuple(
                                "example-project#3",
                                Worker.AGENT,
                                Risk.HARM,
                                List.of(Warning.unknownRisk())),
                        Tuple.tuple("example-project#4", Worker.MANUAL, Risk.HARM, List.of()));
        assertThat(mapped.faults()).as("the item carries it, not the project").isEmpty();
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
                        .sub(part);

        final IssueMapping.Mapped mapped = read(parent, part);

        assertThat(mapped.issues())
                .as("the summed parent and its part, worked as the parent, each with its own")
                .extracting(Issue::id, Issue::minutes, Issue::worker, Issue::priority)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("example-lib#3", 10.0, Worker.MANUAL, "Low"),
                        Tuple.tuple("example-lib#4", null, Worker.MANUAL, "Highest"));
    }

    private static org.fuin.porelo.planner.store.Version versionOf(
            final IssueMapping.Mapped mapped, final String id) {
        return java.util.stream.Stream.concat(
                        mapped.issues().stream()
                                .filter(i -> i.id().equals(id))
                                .map(org.fuin.porelo.planner.store.Issue::version),
                        mapped.stories().stream()
                                .filter(s -> s.id().equals(id))
                                .map(org.fuin.porelo.planner.store.Story::version))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void anItemsVersionChangesWithItsOwnStateOnly() {
        // Harm: a write to one item is refused because another changed, or let through over a
        // change to the item itself.
        final Item story = new Item("example-project", 1).story();
        final Item first = new Item("example-lib", 2).field(FieldNames.STATUS, "Backlog");
        final Item second =
                new Item("example-lib", 3).field(FieldNames.STATUS, "Backlog").blockedBy(first);
        story.sub(first).sub(second);
        final IssueMapping.Mapped before = read(story, first, second);
        assertThat(versionOf(read(story, first, second), "example-lib#2"))
                .as("the same item read again")
                .isEqualTo(versionOf(before, "example-lib#2"));

        first.field(FieldNames.STATUS, "Implementing");
        final IssueMapping.Mapped after = read(story, first, second);

        assertThat(versionOf(after, "example-lib#2"))
                .as("the issue that moved")
                .isNotEqualTo(versionOf(before, "example-lib#2"));
        assertThat(versionOf(after, "example-lib#3"))
                .as("the issue its blocker moved")
                .isEqualTo(versionOf(before, "example-lib#3"));
        assertThat(versionOf(after, "example-project#1"))
                .as("the story whose state follows its issues")
                .isEqualTo(versionOf(before, "example-project#1"));

        story.field(FieldNames.PRIORITY, "Highest");
        assertThat(versionOf(read(story, first, second), "example-project#1"))
                .as("the story whose own priority changed")
                .isNotEqualTo(versionOf(before, "example-project#1"));
    }

    @Test
    void whenGitHubLastTouchedAnItemIsNoPartOfItsVersion() {
        // Harm: a comment, which moves an issue's updatedAt, refuses every write to it.
        final ItemView view = new Item("example-lib", 2).field(FieldNames.STATUS, "Backlog").view();
        final ItemView touched =
                new ItemView(
                        view.itemId(),
                        view.issueId(),
                        view.repository(),
                        view.number(),
                        view.title(),
                        view.body(),
                        view.open(),
                        view.stateReason(),
                        view.fields(),
                        view.labels(),
                        view.parentIssueId(),
                        view.subIssueIds(),
                        view.blockedByIds(),
                        view.link(),
                        view.updatedAt().plusSeconds(3600),
                        view.archived(),
                        view.blockers(),
                        view.unseenBlockers(),
                        view.issueType());

        assertThat(ItemVersions.of(touched)).isEqualTo(ItemVersions.of(view));
        assertThat(
                        ItemVersions.of(
                                new Item("example-lib", 2)
                                        .blockedByNode("I_b")
                                        .blockedByNode("I_a")
                                        .view()))
                .as("its blockers in another order, which GitHub does not keep")
                .isEqualTo(
                        ItemVersions.of(
                                new Item("example-lib", 2)
                                        .blockedByNode("I_a")
                                        .blockedByNode("I_b")
                                        .view()));
        assertThat(ItemVersions.of(new Item("example-lib", 2).blockedByNode("I_a").view()))
                .as("a blocker added")
                .isNotEqualTo(ItemVersions.of(new Item("example-lib", 2).view()));
        assertThat(ItemVersions.of(new Item("example-lib", 2).body("Changed.").view()))
                .as("its body changed")
                .isNotEqualTo(ItemVersions.of(new Item("example-lib", 2).view()));
    }
}

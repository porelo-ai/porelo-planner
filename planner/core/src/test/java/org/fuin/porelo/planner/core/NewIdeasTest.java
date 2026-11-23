package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A discussed idea checked against the idea format and the board before the store saves it. */
class NewIdeasTest {

    static final Board BOARD =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                    List.of(),
                    List.of(),
                    new Work(Map.of(), List.of()),
                    ProjectRepository.named(List.of("porelo-project", "porelo-other")));

    static NewIdeas.Story story() {
        return new NewIdeas.Story("Tag stories", "People find stories", "Tag one", "High");
    }

    static NewIdeas.Issue issue(final String repository) {
        return new NewIdeas.Issue(repository, "Tags", "A story can be tagged.", "S", "Agent");
    }

    static NewIdeas.Idea idea() {
        return new NewIdeas.Idea(story(), List.of(issue("porelo-project"), issue("porelo-other")));
    }

    @Test
    void anIdeaWithinEveryBoundIsTakenWithItsLinesStrippedAndItsWorkersKept() {
        // Harm: a task's idea is refused although a forge accepts its titles and text.
        final String astral = "𝔸";
        final NewIdea checked =
                NewIdeas.check(
                        BOARD,
                        new NewIdeas.Idea(
                                new NewIdeas.Story(
                                        " " + astral.repeat(256) + " ",
                                        "G".repeat(400),
                                        "T".repeat(400),
                                        "Lowest"),
                                List.of(
                                        new NewIdeas.Issue(
                                                "porelo-other",
                                                astral.repeat(256),
                                                astral.repeat(65536),
                                                null,
                                                "Manual"),
                                        issue("porelo-project"))),
                        List.of());

        assertThat(checked.title())
                .as("256 code points are taken, without the spaces around them")
                .isEqualTo(astral.repeat(256));
        assertThat(checked.issues().getFirst().title())
                .as("the issue's full title")
                .isEqualTo(astral.repeat(256));
        assertThat(checked.issues().getFirst().mustBeTrue())
                .as("the issue's full text")
                .isEqualTo(astral.repeat(65536));
        assertThat(checked.priority()).as("the story's priority").isEqualTo("Lowest");
        assertThat(checked.issues())
                .as("each issue in the idea's order, its worker as the person chose")
                .extracting(issue -> issue.repository() + ":" + issue.worker())
                .containsExactly("porelo-other:" + Worker.MANUAL, "porelo-project:" + Worker.AGENT);
    }

    @Test
    void eachFieldOutOfBoundsIsRefusedNamingThatFieldAlone() {
        // A field let through would reach a file the store writes, or a story nobody can read.
        assertThat(fault(i -> story(new NewIdeas.Story("T".repeat(257), "G", "T", "High"))))
                .isEqualTo("story.title");
        assertThat(
                        fault(
                                i ->
                                        issues(
                                                new NewIdeas.Issue(
                                                        "porelo-other",
                                                        "𝔸".repeat(257),
                                                        "W",
                                                        null,
                                                        "Agent"))))
                .as("one code point beyond an idea issue's title bound")
                .isEqualTo("issues[0].title");
        assertThat(fault(i -> story(new NewIdeas.Story("A\u0085B", "G", "T", "High"))))
                .isEqualTo("story.title");
        assertThat(fault(i -> story(new NewIdeas.Story("A\u0090B", "G", "T", "High"))))
                .isEqualTo("story.title");
        assertThat(fault(i -> story(new NewIdeas.Story("A B", "G", "T", "High"))))
                .isEqualTo("story.title");
        assertThat(fault(i -> story(new NewIdeas.Story("   ", "G", "T", "High"))))
                .isEqualTo("story.title");
        assertThat(fault(i -> story(new NewIdeas.Story(null, "G", "T", "High"))))
                .isEqualTo("story.title");
        assertThat(fault(i -> story(new NewIdeas.Story("T", "G".repeat(401), "T", "High"))))
                .isEqualTo("story.gain");
        assertThat(fault(i -> story(new NewIdeas.Story("T", "G\nH", "T", "High"))))
                .isEqualTo("story.gain");
        assertThat(fault(i -> story(new NewIdeas.Story("T", "G", "A\tB", "High"))))
                .isEqualTo("story.test");
        assertThat(fault(i -> story(new NewIdeas.Story("T", "G", "", "High"))))
                .isEqualTo("story.test");
        assertThat(fault(i -> story(new NewIdeas.Story("T", "G", "T", "high"))))
                .isEqualTo("story.priority");
        assertThat(fault(i -> story(null))).isEqualTo("story");
        assertThat(fault(i -> new NewIdeas.Idea(story(), List.of()))).isEqualTo("issues");
        assertThat(fault(i -> issues(null, issue("porelo-other")))).isEqualTo("issues[0]");
        assertThat(
                        fault(
                                i ->
                                        issues(
                                                issue("porelo-project"),
                                                new NewIdeas.Issue(
                                                        "porelo-other",
                                                        "A\u007FB",
                                                        "W",
                                                        null,
                                                        "Agent"))))
                .isEqualTo("issues[1].title");
        assertThat(
                        fault(
                                i ->
                                        issues(
                                                new NewIdeas.Issue(
                                                        "porelo-other",
                                                        "T",
                                                        " \n ",
                                                        null,
                                                        "Agent"))))
                .isEqualTo("issues[0].mustBeTrue");
        assertThat(
                        fault(
                                i ->
                                        issues(
                                                new NewIdeas.Issue(
                                                        "porelo-other",
                                                        "T",
                                                        "W".repeat(65537),
                                                        null,
                                                        "Agent"))))
                .isEqualTo("issues[0].mustBeTrue");
        assertThat(fault(i -> issues(new NewIdeas.Issue("porelo-other", "T", "W", "XXL", "Agent"))))
                .isEqualTo("issues[0].size");
    }

    @Test
    void anIssueWithoutAWorkerOrWithTheContractsOwnWordsIsRefused() {
        // A worker the planner chose would start an agent on work the person keeps.
        for (final String worker : new String[] {null, "AGENT", "manual", ""}) {
            assertThat(
                            fault(
                                    i ->
                                            issues(
                                                    new NewIdeas.Issue(
                                                            "porelo-other",
                                                            "T",
                                                            "W",
                                                            null,
                                                            worker))))
                    .as("the worker %s", worker)
                    .isEqualTo("issues[0].worker");
        }
    }

    @Test
    void aRepositoryNamedTwiceOrOutsideTheProjectIsRefusedBeforeAnyIssueIsBuilt() {
        // A name such as a..b reaching an issue's constructor would answer a crash, not a refusal.
        assertThat(fault(i -> issues(issue("porelo-other"), issue("porelo-other"))))
                .isEqualTo("issues[1].repository");
        for (final String repository :
                new String[] {"porelo-elsewhere", "a..b", "../porelo-other", "Porelo", null}) {
            assertThat(fault(i -> issues(issue(repository))))
                    .as("the repository %s", repository)
                    .isEqualTo("issues[0].repository");
        }
    }

    @Test
    void theNamesAllowedAreToldOnceSoARefusalStaysSmallAtTheLimits() {
        // Harm: a hundred repository faults each listing every repository make a refusal of
        // hundreds of kilobytes in the window and the log.
        final List<String> names = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            names.add(String.format("r%02d", index) + "x".repeat(97));
        }
        final Board board =
                new Board(
                        BOARD.revision(),
                        BOARD.workflow(),
                        List.of(),
                        List.of(),
                        BOARD.work(),
                        ProjectRepository.named(names));
        final List<NewIdeas.@Nullable Issue> issues = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            issues.add(issue(index % 2 == 0 ? "unknown-" + index : "Not A Name"));
        }
        final WorkRefusal refused =
                catchThrowableOfType(
                        WorkRefusal.class,
                        () -> NewIdeas.check(board, new NewIdeas.Idea(story(), issues), List.of()));

        assertThat(refused).as("the idea is refused").isNotNull();
        final List<?> faults = (List<?>) refused.parameters().get("faults");
        assertThat(faults).as("a fault for each issue").hasSize(100);
        assertThat(String.valueOf(faults.getFirst()))
                .as("the first repository fault names every repository allowed")
                .contains(String.join(", ", names));
        assertThat(faults.stream().mapToInt(fault -> String.valueOf(fault).length()).sum())
                .as("the names are told once, the other faults stay short")
                .isLessThan(String.join(", ", names).length() + 100 * 200);
    }

    @Test
    void aHundredRepositoryFaultsAreListedWithoutRepeatingWhatWasSent() {
        // Harm: a fault echoing a malformed repository would carry an agent's words into the
        // window and the log; one beyond a hundred would make the refusal unbounded.
        final List<NewIdeas.@Nullable Issue> issues = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            issues.add(issue("secret repo"));
        }
        final WorkRefusal refused =
                catchThrowableOfType(
                        WorkRefusal.class,
                        () -> NewIdeas.check(BOARD, new NewIdeas.Idea(story(), issues), List.of()));

        assertThat(refused).as("the idea is refused").isNotNull();
        final List<?> faults = (List<?>) refused.parameters().get("faults");
        assertThat(faults).as("at most a hundred faults").hasSize(100);
        for (int index = 0; index < faults.size(); index++) {
            assertThat(String.valueOf(faults.get(index)))
                    .as("fault %d", index)
                    .startsWith("issues[" + index + "].repository: ")
                    .doesNotContain("secret");
        }
    }

    @Test
    void aRefusedRepositoryIsToldWithTheNamesAllowedAndItselfOnlyWhenItIsAName() {
        // Harm: a discussion told only that a repository is wrong guesses again and is refused
        // again; one echoing any text would carry an agent's words into the window and the log.
        assertThat(faultWords(i -> issues(issue("porelo-pi"))))
                .as("a well-formed name the project lacks, named with the names allowed")
                .startsWith("issues[0].repository: ")
                .contains("porelo-pi", "porelo-project, porelo-other");
        for (final String sent :
                new String[] {"Porelo Pi", "a".repeat(101), "porelo-pi\nsecret", "../porelo"}) {
            assertThat(faultWords(i -> issues(issue(sent))))
                    .as("the value %s, no repository name, is not repeated", sent)
                    .startsWith("issues[0].repository: ")
                    .contains("porelo-project, porelo-other")
                    .doesNotContain(sent, "Porelo Pi", "aaaa", "secret", "../");
        }
        assertThat(faultWords(i -> issues(new NewIdeas.Issue(null, "Tags", "Text", "S", "Agent"))))
                .as("a missing repository is told with the names allowed")
                .startsWith("issues[0].repository: ")
                .contains("porelo-project, porelo-other");
    }

    @Test
    void aRefusalListsEveryFaultUpToAHundredAndNeverRepeatsWhatWasSent() {
        // A fault echoing the sent text would carry an agent's words into the window and the log.
        final List<NewIdeas.@Nullable Issue> issues = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            issues.add(new NewIdeas.Issue("secret repo", "secret\u0007", "", "secret", "x"));
        }
        final WorkRefusal refused =
                catchThrowableOfType(
                        WorkRefusal.class,
                        () ->
                                NewIdeas.check(
                                        BOARD,
                                        new NewIdeas.Idea(
                                                new NewIdeas.Story(
                                                        "secret\n", "secret", "secret", "secret"),
                                                issues),
                                        List.of("story: a fault the reader found")));

        assertThat(refused).as("a malformed idea is refused").isNotNull();
        assertThat(refused.name()).as("the refusal's name").isEqualTo("IdeaRefused");
        final List<?> faults = (List<?>) refused.parameters().get("faults");
        assertThat(faults).as("at most a hundred faults").hasSize(100);
        assertThat(faults.getFirst())
                .as("the reader's faults come first")
                .isEqualTo("story: a fault the reader found");
        assertThat(faults)
                .as("no fault repeats what was sent")
                .noneMatch(fault -> String.valueOf(fault).contains("secret"));
        assertThat(refused.parameters())
                .as("a malformed idea names no repository as pushed")
                .doesNotContainKey("pushed");
    }

    @Test
    void theIdeaFormatTheDiscussionIsGivenHoldsTheBoundsThePlannerChecks() {
        // A format wider than the check lets a discussion write an idea the planner then refuses.
        final java.util.regex.Matcher bounds =
                java.util.regex.Pattern.compile("\"maxLength\": ([0-9]+)")
                        .matcher(IdeaDiscussion.format(List.of("porelo-project")));
        final List<Integer> found = new ArrayList<>();
        while (bounds.find()) {
            found.add(Integer.valueOf(bounds.group(1)));
        }
        assertThat(found)
                .as("the bounds of story title, gain, test, issue title and mustBeTrue in order")
                .containsExactly(
                        NewIdeas.MOST_TITLE,
                        NewIdeas.MOST_LINE,
                        NewIdeas.MOST_LINE,
                        NewIdeas.MOST_TITLE,
                        NewIdeas.MOST_TEXT);
        assertThat(IdeaDiscussion.format(List.of("porelo-project")))
                .as("titles, gain and test are one line without C1 controls or separators")
                .contains("\\\\u007F-\\\\u009F\\\\u2028\\\\u2029");
    }

    private static NewIdeas.Idea story(final NewIdeas.@Nullable Story story) {
        return new NewIdeas.Idea(story, List.of(issue("porelo-other")));
    }

    private static NewIdeas.Idea issues(final NewIdeas.@Nullable Issue... issues) {
        return new NewIdeas.Idea(story(), java.util.Arrays.asList(issues));
    }

    private static String fault(final UnaryOperator<NewIdeas.Idea> change) {
        return faultWords(change).split(":", 2)[0];
    }

    private static String faultWords(final UnaryOperator<NewIdeas.Idea> change) {
        final WorkRefusal refused =
                catchThrowableOfType(
                        WorkRefusal.class,
                        () -> NewIdeas.check(BOARD, change.apply(idea()), List.of()));
        assertThat(refused).as("the idea is refused").isNotNull();
        final List<?> faults = (List<?>) refused.parameters().get("faults");
        assertThat(faults).as("exactly one fault").hasSize(1);
        return String.valueOf(faults.getFirst());
    }
}

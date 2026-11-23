package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A repository's warning about its labels, its calls, and the one correction with no choice. */
class RepositoryWarningTest {

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Finished"),
                    List.of(new Workflow.Phase("Open", List.of("Idea"))),
                    List.of("Finished"));

    private static RepositoryWarning warning(
            final List<String> missing, final List<String> defaults, final List<String> differing) {
        return new RepositoryWarning(
                "porelo-lib", RepositoryWarning.TOPIC_LABELS, missing, defaults, differing, null);
    }

    @Test
    void theCallsDeleteTheDefaultsThenCreateThenUpdateWithTheTopicLabelsWords() {
        // Harm: a created label got another color than the topic labels', or an update ran before
        // a delete freed nothing it needed, and the preview showed another order than was made.
        final List<RepositoryWarning.Call> calls =
                warning(List.of("security"), List.of("bug", "wontfix"), List.of("tests")).calls();

        assertThat(calls)
                .containsExactly(
                        new RepositoryWarning.Call(
                                RepositoryWarning.Call.Action.DELETE, "bug", null, null),
                        new RepositoryWarning.Call(
                                RepositoryWarning.Call.Action.DELETE, "wontfix", null, null),
                        new RepositoryWarning.Call(
                                RepositoryWarning.Call.Action.CREATE,
                                "security",
                                "b60205",
                                "A guard, a secret or a trust boundary"),
                        new RepositoryWarning.Call(
                                RepositoryWarning.Call.Action.UPDATE,
                                "tests",
                                "fbca04",
                                "Tests and what runs them"));
    }

    @Test
    void theCallsOfTheWidestWarningFitTheContractsBound() {
        // Harm: the preview answered more calls than the contract allows, and the window refused
        // the whole answer.
        assertThat(warning(TopicLabels.NAMES, TopicLabels.DEFAULTS, List.of()).calls())
                .hasSizeLessThanOrEqualTo(15);
        assertThat(TopicLabels.NAMES).hasSize(7).doesNotHaveDuplicates();
        assertThat(TopicLabels.DEFAULTS).doesNotContainAnyElementsOf(TopicLabels.NAMES);
    }

    @Test
    void aWarningNamingNoSuchLabelNothingOrALabelTwiceIsRefused() {
        // Harm: a warning offered to delete the project's own label, or to create one twice.
        assertThatThrownBy(() -> warning(List.of(), List.of("customer"), List.of()))
                .as("the own label as a default")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> warning(List.of("customer"), List.of(), List.of()))
                .as("the own label as missing")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> warning(List.of("tests", "tests"), List.of(), List.of()))
                .as("twice")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> warning(List.of("tests"), List.of(), List.of("tests")))
                .as("missing and differing")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> warning(List.of(), List.of(), List.of()))
                .as("nothing wrong")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RepositoryWarning(
                                        "../x",
                                        RepositoryWarning.TOPIC_LABELS,
                                        List.of("tests"),
                                        List.of(),
                                        List.of(),
                                        null))
                .as("no repository's name")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RepositoryWarning(
                                        "porelo-lib",
                                        RepositoryWarning.TOPIC_LABELS,
                                        List.of("tests"),
                                        List.of(),
                                        List.of(),
                                        new Correction(Correction.MOVE, List.of("Idea"))))
                .as("another correction")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheLabelsCorrectionOffersNoChoice() {
        // Harm: a move offered with no state to move to, which the window cannot ask about.
        assertThat(Correction.labels().choices()).isEmpty();
        assertThatThrownBy(() -> new Correction(Correction.MOVE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Correction(Correction.LABELS, List.of("Idea")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBoardCarriesAWarningOnlyForItsOwnRepositoriesAndOncePerKind() {
        // Harm: a warning about a repository outside the project offered calls on it.
        final Board board =
                new Board(
                        new Revision("r"),
                        WORKFLOW,
                        List.of(),
                        List.of(),
                        new Work(java.util.Map.of(), List.of()),
                        ProjectRepository.named(List.of("porelo-lib")));
        final RepositoryWarning one = warning(List.of("tests"), List.of(), List.of()).offered();

        final Board warned = board.withRepositories(List.of(one), List.of("A fault"));

        assertThat(warned.repositoryWarning("porelo-lib", RepositoryWarning.TOPIC_LABELS))
                .contains(one);
        assertThat(warned.faults()).contains("A fault");
        assertThat(board.repositoryWarnings()).as("the board unchanged").isEmpty();
        assertThatThrownBy(() -> board.withRepositories(List.of(one, one), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                board.withRepositories(
                                        List.of(
                                                new RepositoryWarning(
                                                        "elsewhere",
                                                        RepositoryWarning.TOPIC_LABELS,
                                                        List.of("tests"),
                                                        List.of(),
                                                        List.of(),
                                                        null)),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

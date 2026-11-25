package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PlannerFileTest {

    // Harm: a project a GitHub project keeps opened on another project than the file names.
    @Test
    void aGitHubTrackerIsReadFromKindAndProject() {
        assertThat(PlannerFile.tracker("tracker: {kind: github, project: porelo-ai/1}\n"))
                .as("the owner and the number the file names")
                .hasValue(new PlannerFile.Tracker("github", "porelo-ai", "1"));
    }

    // Harm: a local project cannot open when tracker parsing demands a forge project.
    @Test
    void aLocalTrackerNamesNoForgeProject() {
        assertThat(PlannerFile.tracker("tracker: {kind: local}\n"))
                .as("a directory tracker needs neither owner nor project")
                .hasValue(new PlannerFile.Tracker("local", "", ""));
    }

    // Harm: a planner file without a tracker read as one, so the Git store's issues are hidden.
    @Test
    void aFileNamingNoTrackerNamesNone() {
        assertThat(PlannerFile.tracker("{}\n")).as("no tracker").isEmpty();
    }

    // Harm: a planner.yml emptied, or with every setting commented out, refuses the whole project
    // instead of opening it with no tracker.
    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "# tracker: {kind: local}\n", "  # none\r\n\r\n"})
    void anEmptyOrCommentOnlyFileNamesNoTracker(final String text) {
        assertThat(PlannerFile.tracker(text)).as("no settings, no tracker").isEmpty();
    }

    // Harm: machine paths could enter the planning repository as tracker settings.
    @Test
    void aLocalTrackerRefusesAdditionalSettings() {
        assertThatThrownBy(() -> PlannerFile.tracker("tracker: {kind: local, project: acme/1}\n"))
                .as("local trackers take only their kind")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("planner.yml tracker: a local tracker names its kind alone");
    }

    // Harm: a setting the planner does not know, or one of the wrong shape, passed over as if it
    // held, so the project opens on a default the person did not choose.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "tracker: {kind: github, project: porelo-ai/1}\\nvelocity: 3|"
                        + "planner.yml: unknown key 'velocity'",
                "tracker: [github]|planner.yml tracker: expected a mapping of kind and project",
                "tracker: {kind: github}|planner.yml tracker: expected a mapping of kind and project",
                "tracker: {kind: github, project: porelo-ai}|"
                        + "planner.yml tracker: project must be <owner>/<number>",
                "tracker: {kind: GitHub, project: porelo-ai/1}|"
                        + "planner.yml tracker: kind must be a lower-case name",
                "[tracker]|planner.yml: expected a YAML mapping",
            })
    void aFileOfAnotherShapeIsRefusedNamingWhatIsWrong(final String text, final String said) {
        assertThatThrownBy(() -> PlannerFile.tracker(text.replace("\\n", "\n")))
                .as("refused, never a silent default")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(said);
    }
}

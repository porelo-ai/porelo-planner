package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectRefTest {

    @Test
    void aGitHubProjectIsReadFromOwnerAndNumber() {
        // Harm: the planner reading or writing another owner's project than project.yml names.
        assertThat(ProjectRef.parse("example-org/7"))
                .as("the tracker's project")
                .isEqualTo(new ProjectRef("example-org", 7));
        for (final String text :
                List.of(
                        "example-org",
                        "a/b/7",
                        "/7",
                        "example-org/",
                        "example-org/x",
                        "example-org/0",
                        "example-org/9999999999",
                        "ex ample/7")) {
            assertThatThrownBy(() -> ProjectRef.parse(text))
                    .as("the project %s", text)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

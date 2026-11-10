package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** A project given by its own repository's URL and read from Git, as the planner wires it. */
class RealProjectReadTest {

    @TempDir Path dir;

    @Test
    void aProjectGivenByItsOwnRepositoryIsReadFromGitAndServed() throws Exception {
        // The window's only way in: a PUT that reads nothing would leave every read a 404.
        try (FixtureProject project = FixtureProject.serve(dir)) {
            final MockMvc mvc =
                    MockMvcBuilders.standaloneSetup(
                                    new BoardController(
                                            StoreConfiguration.fromGit(
                                                    StoreConfiguration.gitProjects(
                                                            project.environment()))))
                            .setControllerAdvice(new Problems())
                            .build();
            final MockHttpServletResponse given =
                    mvc.perform(
                                    put("/projects/" + FixtureProject.NAME)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(project.source()))
                            .andReturn()
                            .getResponse();
            assertThat(given.getStatus())
                    .as("the PUT's status; the planner answered %s", given.getContentAsString())
                    .isEqualTo(204);
            mvc.perform(get("/projects/alpha/workflow"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.states[1].kind").value("WORKING"))
                    .andExpect(jsonPath("$.states[3].kind").value("CLOSED"))
                    .andExpect(jsonPath("$.faults.length()").value(0));
            mvc.perform(get("/projects/alpha/stories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stories[0].id").value("ST7"))
                    .andExpect(jsonPath("$.stories[0].state").value("Implementing"))
                    .andExpect(jsonPath("$.stories[0].stateGiven").value(false));
            mvc.perform(get("/projects/alpha/issues/XY07"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.issue.whatMustBeTrue").value("It is served."));
        }
    }
}

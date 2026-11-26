package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;

/** A fault no handler names: one line in the planner's log to find it by, fixed words outward. */
class UnexpectedFaultTest {

    /** A token's shape, made of parts so that no scan takes this file for one that leaked. */
    private static final String TOKEN = "ghp" + "_" + "abcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * Calls that fail in the ways a handler must tell apart; no rest controller, so the check of
     * the description never takes them for the planner's own.
     */
    @Controller
    @ResponseBody
    static class Failing {

        @PutMapping("/projects/{project}/fail")
        String fail(@PathVariable("project") final String project) {
            throw new IllegalStateException(
                    "The save failed.",
                    new IllegalArgumentException("The login " + TOKEN + " was refused."));
        }

        @GetMapping("/count")
        String count(@RequestParam("n") final int n) {
            return String.valueOf(n);
        }

        @GetMapping("/gone")
        String gone() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private final Logger logger = (Logger) LoggerFactory.getLogger(Problems.class);

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    private final Problems problems =
            new Problems(
                    new Projects(
                            (project, repository, branch) -> {
                                throw new UnsupportedOperationException("No store here.");
                            }));

    private MockMvc mvc;

    @BeforeEach
    void serve() {
        lines.start();
        logger.addAppender(lines);
        mvc = MockMvcBuilders.standaloneSetup(new Failing()).setControllerAdvice(problems).build();
    }

    @AfterEach
    void detach() {
        logger.detachAppender(lines);
    }

    private List<ILoggingEvent> errors() {
        return lines.list.stream().filter(line -> line.getLevel() == Level.ERROR).toList();
    }

    @Test
    void aFaultIsAnsweredWithFixedWordsAndLoggedOnOneLineWithItsCausesAndFrames() throws Exception {
        // Harm: a fault that leaves no trace in the planner's log to find it by, or an answer
        // that hands the window what the fault's words carried.
        final String body =
                mvc.perform(put("/projects/alpha/fail"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.title").value("Unexpected"))
                        .andExpect(jsonPath("$.type").value(Problems.TYPE + "Unexpected"))
                        .andExpect(
                                jsonPath("$.detail")
                                        .value(
                                                "The planner failed on this call; its log names"
                                                        + " the cause."))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).as("no word of the fault").doesNotContain("save failed", "login");

        assertThat(errors()).as("one line").hasSize(1);
        final ILoggingEvent line = errors().getFirst();
        assertThat(line.getThrowableProxy()).as("the fault is never handed over").isNull();
        assertThat(line.getFormattedMessage())
                .startsWith("A call to PUT /projects/{project}/fail failed unexpectedly:")
                .contains("IllegalStateException: The save failed.")
                .contains("; caused by IllegalArgumentException: The login")
                .contains("; at UnexpectedFaultTest$Failing.fail:")
                .doesNotContain("\n");
    }

    @Test
    void aTokenInACausesWordsIsLoggedScrubbed() throws Exception {
        // Harm: a secret an exception's words repeat written to the planner's log.
        mvc.perform(put("/projects/alpha/fail")).andExpect(status().isInternalServerError());

        assertThat(errors().getFirst().getFormattedMessage())
                .doesNotContain(TOKEN)
                .contains("The login *** was refused.");
    }

    @Test
    void springsOwnAnswersStandAndAreNotLoggedAsFaults() throws Exception {
        // Harm: a wrong method, a parameter of the wrong type or an answer a call chose turned
        // into a fault of the planner's.
        mvc.perform(post("/projects/alpha/fail")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/count").param("n", "many")).andExpect(status().isBadRequest());
        mvc.perform(get("/gone")).andExpect(status().isNotFound());

        assertThat(errors()).as("no fault logged").isEmpty();
    }

    @Test
    void aCauseWithoutFramesSaysSoInsteadOfABareAt() {
        // Harm: a line that ends in a dangling "at", read as a trace cut off by the log.
        final IllegalArgumentException cause = new IllegalArgumentException("No trace.");
        cause.setStackTrace(new StackTraceElement[0]);

        problems.unexpected(
                new IllegalStateException("The call failed.", cause),
                new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse()));

        assertThat(errors().getFirst().getFormattedMessage())
                .endsWith("; no frames")
                .doesNotContain("; at ");
    }

    @Test
    void aFaultAfterTheAnswerBeganIsLoggedAndWritesNoProblem() {
        // Harm: a problem written into a stream whose answer has begun, after the fault is lost.
        final MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        assertThat(
                        problems.unexpected(
                                new IllegalStateException("The stream broke."),
                                new ServletWebRequest(new MockHttpServletRequest(), response)))
                .isNull();
        assertThat(errors()).hasSize(1);
    }
}

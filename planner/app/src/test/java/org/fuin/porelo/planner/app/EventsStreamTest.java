package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** A project's boards streamed to a window as the project's remote changes. */
class EventsStreamTest {

    private static final Pattern BOARD = Pattern.compile("event:board\\nid:([^\\n]+)\\ndata:");

    @TempDir Path dir;

    @Test
    void aWindowGetsTheBoardAndThenTheBoardOfAPushMadeElsewhere() throws Exception {
        // A window that never hears of a push shows a board that is no longer true.
        try (FixtureProject project = FixtureProject.serve(dir);
                ChangesRegistry changes = registry(project)) {
            final MockMvc mvc = mvc(project, changes);
            final MvcResult stream = mvc.perform(get("/projects/alpha/events")).andReturn();
            final MockHttpServletResponse events = stream.getResponse();
            within(() -> boards(events) >= 1, "the first board", events);
            final String first = lastRevision(events);
            assertThat(events.getContentAsString())
                    .as("the first board's data")
                    .contains("\"stories\":[{\"id\":\"ST7\"", "\"kind\":\"WORKING\"");

            project.pushElsewhere();
            final String refreshed =
                    mvc.perform(post("/projects/alpha/refresh"))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            within(() -> boards(events) >= 2, "a second board after the push", events);
            assertThat(lastRevision(events)).as("the second board's revision").isNotEqualTo(first);
            assertThat(refreshed).as("the refresh's revision").contains(lastRevision(events));
            assertThat(events.getContentAsString())
                    .as("the second board's story, now derived from the issue in Review")
                    .contains("\"state\":\"Review\"");
            within(
                    () -> content(events).contains(": keep-alive"),
                    "a keep-alive while nothing changes",
                    events);

            mvc.perform(post("/projects/alpha/refresh")).andExpect(status().isOk());
            assertThat(boards(events)).as("no board for a fetch with nothing new").isEqualTo(2);

            // Ended at once, not at the next keep-alive: a window gone must not hold its stream.
            stream.getRequest().getAsyncContext().complete();
            assertThat(changes.followedProjects())
                    .as("the project polled once its last window went")
                    .isZero();
        }
    }

    @Test
    void anUnknownProjectIsRefusedBeforeAnyStreamStarts() throws Exception {
        // A stream that starts and stays silent would look like a board that never changes.
        try (FixtureProject project = FixtureProject.serve(dir);
                ChangesRegistry changes = registry(project)) {
            final MockMvc mvc = mvc(project, changes);
            mvc.perform(get("/projects/beta/events").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isNotFound())
                    .andExpect(
                            MockMvcResultMatchers.content()
                                    .contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchProject"));
            mvc.perform(post("/projects/beta/refresh"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchProject"));
            assertThat(changes.followedProjects()).as("nothing followed").isZero();
        }
    }

    private static ChangesRegistry registry(final FixtureProject project) {
        return new ChangesRegistry(
                StoreConfiguration.fromGit(StoreConfiguration.gitProjects(project.environment())),
                Duration.ofHours(1),
                Executors.newSingleThreadScheduledExecutor());
    }

    private static MockMvc mvc(final FixtureProject project, final ChangesRegistry changes)
            throws Exception {
        final MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new BoardController(changes.projects()),
                                new EventsController(changes, Duration.ofSeconds(2)))
                        .setControllerAdvice(new Problems())
                        .build();
        mvc.perform(
                        put("/projects/alpha")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(project.source()))
                .andExpect(status().isNoContent());
        return mvc;
    }

    private static int boards(final MockHttpServletResponse events) {
        final Matcher matcher = BOARD.matcher(content(events));
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String lastRevision(final MockHttpServletResponse events) {
        final Matcher matcher = BOARD.matcher(content(events));
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        assertThat(last).as("a board's revision").isNotNull();
        return last;
    }

    private static String content(final MockHttpServletResponse events) {
        try {
            return events.getContentAsString();
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void within(
            final BooleanSupplier condition,
            final String what,
            final MockHttpServletResponse events)
            throws InterruptedException {
        final long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() < end)
                    .as("%s within 20 s; the stream so far:%n%s", what, content(events))
                    .isTrue();
            Thread.sleep(50);
        }
    }
}

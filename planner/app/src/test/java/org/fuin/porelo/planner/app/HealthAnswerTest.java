package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The health answer over HTTP, as the window reads it, for a build with and without a commit. */
class HealthAnswerTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(WithCommit.Build.class)
    class WithCommit {

        private static final Path RUNTIME = create();

        @TestConfiguration(proxyBeanMethods = false)
        static class Build {
            @Bean
            @Primary
            PlannerBuild withCommit() {
                return new PlannerBuild("0.1.0~snapshot.36", COMMIT);
            }
        }

        @LocalServerPort private int port;

        @DynamicPropertySource
        static void runtimeDirectory(final DynamicPropertyRegistry registry) {
            registry.add("planner.runtime-directory", RUNTIME::toString);
            registry.add("planner.log.base", RUNTIME::toString);
        }

        @AfterAll
        static void deleteRuntimeDirectory() throws IOException {
            RuntimeDirectories.delete(RUNTIME);
        }

        @Test
        void theHealthAnswerNamesTheVersionAndTheCommit() throws Exception {
            // A window naming another planner than the one it talks to.
            final JsonNode answer = health(port, RUNTIME);
            assertThat(fields(answer))
                    .as("the health answer's fields, all required")
                    .isEqualTo(Set.of("commit", "status", "version"));
            assertThat(answer.get("status").asText()).as("the status").isEqualTo("UP");
            assertThat(answer.get("version").asText())
                    .as("the version")
                    .isEqualTo("0.1.0~snapshot.36");
            assertThat(answer.get("commit").isTextual()).as("the commit is a string").isTrue();
            assertThat(answer.get("commit").asText())
                    .as("the commit, 40 lowercase hex")
                    .matches("[0-9a-f]{40}")
                    .isEqualTo(COMMIT);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(WithoutCommit.Build.class)
    class WithoutCommit {

        private static final Path RUNTIME = create();

        @TestConfiguration(proxyBeanMethods = false)
        static class Build {
            @Bean
            @Primary
            PlannerBuild withoutCommit() {
                return new PlannerBuild("1.2.3", null);
            }
        }

        @LocalServerPort private int port;

        @DynamicPropertySource
        static void runtimeDirectory(final DynamicPropertyRegistry registry) {
            registry.add("planner.runtime-directory", RUNTIME::toString);
            registry.add("planner.log.base", RUNTIME::toString);
        }

        @AfterAll
        static void deleteRuntimeDirectory() throws IOException {
            RuntimeDirectories.delete(RUNTIME);
        }

        @Test
        void aBuildWithoutACommitAnswersAnExplicitNull() throws Exception {
            // A build without a checkout answering 500, or leaving out a field the window requires.
            final JsonNode answer = health(port, RUNTIME);
            assertThat(fields(answer))
                    .as("the health answer's fields, all required")
                    .isEqualTo(Set.of("commit", "status", "version"));
            assertThat(answer.get("version").asText()).as("the version").isEqualTo("1.2.3");
            assertThat(answer.get("commit").isNull()).as("the commit is JSON null").isTrue();
        }
    }

    private static JsonNode health(final int port, final Path runtime) throws Exception {
        final HttpResponse<String> answer =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + "/health"))
                                        .header(
                                                "Authorization",
                                                "Bearer "
                                                        + RuntimeDirectories.read(runtime).token())
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).as("the health call's status").isEqualTo(200);
        return new ObjectMapper().readTree(answer.body());
    }

    private static Path create() {
        try {
            return RuntimeDirectories.create();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> fields(final JsonNode answer) {
        final Set<String> names = new TreeSet<>();
        answer.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

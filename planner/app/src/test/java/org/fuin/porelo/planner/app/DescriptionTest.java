package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The planner's own description: every operation marked for agents or not, and served as built. */
class DescriptionTest {

    private static final Path YAML = Path.of("src/main/resources/openapi/planner.yaml");

    @Test
    void everyOperationOfTheDescriptionCarriesItsMarker() throws IOException {
        // An operation added without a marker would be offered to agents, or hidden, by nobody's
        // choice.
        assertThat(Description.unmarked(new YAMLMapper().readTree(YAML.toFile())))
                .as("the operations of planner.yaml without x-mcp: true or x-mcp: false <reason>")
                .isEmpty();
    }

    @Test
    void anOperationWithoutAMarkerFailsTheConversion(@TempDir final Path dir) throws IOException {
        final Path yaml = dir.resolve("unmarked.yaml");
        Files.writeString(
                yaml,
                """
                openapi: 3.1.0
                paths:
                  /a:
                    get:
                      x-mcp: true
                    put:
                      x-mcp: false
                  /b:
                    parameters: []
                    post:
                      operationId: b
                """);
        assertThatThrownBy(
                        () ->
                                Description.main(
                                        new String[] {
                                            yaml.toString(), dir.resolve("out.json").toString()
                                        }))
                .as("a bare false and a missing marker, each named")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUT /a")
                .hasMessageContaining("POST /b")
                .hasMessageNotContaining("GET /a");
        assertThat(dir.resolve("out.json"))
                .as("nothing written for a refused description")
                .doesNotExist();
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class Served {

        private static final Path RUNTIME = create();

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
        void aSignedReadAnswersTheDescriptionAsJsonWithEveryMarker() throws Exception {
            final HttpResponse<String> answer =
                    Signing.sign(
                                    port,
                                    RuntimeDirectories.read(RUNTIME).token(),
                                    "GET",
                                    "/openapi",
                                    null,
                                    null)
                            .send();
            assertThat(answer.statusCode()).as("the description's status").isEqualTo(200);
            assertThat(answer.headers().firstValue("Content-Type"))
                    .as("the description's type")
                    .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
            final JsonNode served = new ObjectMapper().readTree(answer.body());
            assertThat(served)
                    .as(
                            "the served description, member for member the YAML the planner was built with")
                    .isEqualTo(new YAMLMapper().readTree(YAML.toFile()));
            assertThat(served.at("/paths/~1openapi/get/x-mcp").asText())
                    .as("the description itself is not offered to agents")
                    .startsWith("false ");
            // Without them an agent applies a correction the window offers only by a signed script.
            final JsonNode preview =
                    served.at("/paths/~1projects~1{project}~1corrections~1preview/post/x-mcp");
            assertThat(preview.isBoolean() && preview.asBoolean())
                    .as("a correction's preview is offered to agents: %s", preview)
                    .isTrue();
            final JsonNode save = served.at("/paths/~1projects~1{project}~1corrections/post/x-mcp");
            assertThat(save.isBoolean() && save.asBoolean())
                    .as("a correction's save is offered to agents: %s", save)
                    .isTrue();
        }

        @Test
        void anUnsignedReadIsRefused() throws Exception {
            final HttpResponse<String> answer =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + port
                                                                    + "/openapi"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            assertThat(answer.statusCode())
                    .as("an unsigned read of the description")
                    .isEqualTo(401);
            assertThat(answer.body()).as("the refusal's body").isEmpty();
        }

        private static Path create() {
            try {
                return RuntimeDirectories.create();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

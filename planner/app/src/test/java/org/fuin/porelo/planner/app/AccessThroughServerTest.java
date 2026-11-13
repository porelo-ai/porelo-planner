package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessThroughServerTest {

    private static final Path RUNTIME = create();

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
    void aSignedCallReachesThePlannerAndItsAnswersProveThemselves() throws Exception {
        // Harm: the owner's window could not reach its planner, or could not tell an answer or an
        // error from a stranger's.
        final RuntimeDirectories.Address address = RuntimeDirectories.read(RUNTIME);
        final HttpResponse<String> health =
                Signing.sign(address, "GET", "/health", null, null).send();
        assertThat(health.statusCode()).as("the status of a signed call").isEqualTo(200);
        final HttpResponse<String> unknown =
                Signing.sign(address, "GET", "/nothing-here", null, null).send();
        assertThat(unknown.statusCode())
                .as("the status of a signed call on an unknown path, its proof held by send")
                .isEqualTo(404);
    }

    @Test
    void theProofOverTheServerNamesThePortItListensOn() throws Exception {
        // Harm: a proof bound to no port could be relayed by a process on another port.
        final RuntimeDirectories.Address address = RuntimeDirectories.read(RUNTIME);
        assertThat(Signing.askProof(address.port(), address.token()))
                .as("the proof's answer for the port and token of the address file")
                .isEqualTo("200 proven");
    }

    @Test
    void aCallWithoutASignatureIsRefusedOnAnyPathBeforeAnythingAnswers() throws Exception {
        // Harm: a stranger reaching an operation; the check must sit ahead of everything,
        // unknown paths included, and a bearer with the right token counts for nothing.
        final RuntimeDirectories.Address address = RuntimeDirectories.read(RUNTIME);
        assertRefused(call("/health", null, "application/json"), "no signature on a known path");
        assertRefused(
                call("/nothing-here", null, "text/event-stream"),
                "no signature on an unknown path");
        assertRefused(
                call("/health", "Bearer " + address.token(), "application/json"),
                "the right token as a bearer");
    }

    private static void assertRefused(final HttpResponse<String> answer, final String what) {
        assertThat(answer.statusCode()).as("the status with %s", what).isEqualTo(401);
        assertThat(answer.headers().firstValue("WWW-Authenticate"))
                .as("the challenge with %s", what)
                .hasValue("porelo-planner-request-1");
        assertThat(answer.body()).as("the body with %s, which must say nothing", what).isEmpty();
        assertThat(answer.headers().firstValue("Access-Control-Allow-Origin"))
                .as("a CORS header with %s", what)
                .isEmpty();
    }

    private static HttpResponse<String> call(
            final String path, final @Nullable String authorization, final String accept)
            throws IOException, InterruptedException {
        final int port = RuntimeDirectories.read(RUNTIME).port();
        final HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Accept", accept)
                        .header("Origin", "http://example.org");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Path create() {
        try {
            return RuntimeDirectories.create();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

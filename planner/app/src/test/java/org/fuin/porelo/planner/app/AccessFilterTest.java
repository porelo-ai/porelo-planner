package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessFilterTest {

    private static final Path RUNTIME = create();

    @DynamicPropertySource
    static void runtimeDirectory(final DynamicPropertyRegistry registry) {
        registry.add("planner.runtime-directory", RUNTIME::toString);
    }

    @AfterAll
    static void deleteRuntimeDirectory() throws IOException {
        RuntimeDirectories.delete(RUNTIME);
    }

    @Test
    void theRightTokenReachesThePlanner() throws Exception {
        // The owner's window must get through, or the planner is of no use.
        final RuntimeDirectories.Address address = RuntimeDirectories.read(RUNTIME);
        final HttpResponse<String> answer =
                call("/health", "Bearer " + address.token(), "application/json");
        assertThat(answer.statusCode()).as("the status with the right token").isEqualTo(200);
    }

    @Test
    void aCallWithoutTheTokenIsRefusedBeforeAnythingAnswers() throws Exception {
        // Any program of any account on this computer can reach the loopback address.
        final String token = RuntimeDirectories.read(RUNTIME).token();
        final String sameLength =
                token.charAt(0) == 'A' ? "B" + token.substring(1) : "A" + token.substring(1);
        assertRefused(call("/health", null, "application/json"), "no Authorization header");
        assertRefused(call("/health", "Bearer wrong", "application/json"), "a wrong token");
        assertRefused(
                call("/health", "Bearer " + sameLength, "application/json"),
                "a token of the right length with other bytes");
        assertRefused(call("/health", "Basic " + token, "application/json"), "Basic, not Bearer");
        assertRefused(
                call("/health", "Bearer " + token + "x", "application/json"), "a longer token");
        assertRefused(call("/nothing-here", null, "application/json"), "an unknown path");
        assertRefused(call("/events", null, "text/event-stream"), "an event stream");
    }

    private static void assertRefused(final HttpResponse<String> answer, final String what) {
        assertThat(answer.statusCode()).as("the status with %s", what).isEqualTo(401);
        assertThat(answer.headers().firstValue("WWW-Authenticate"))
                .as("the challenge with %s", what)
                .hasValue("Bearer");
        assertThat(answer.body()).as("the body with %s, which must say nothing", what).isEmpty();
        assertThat(answer.headers().firstValue("Access-Control-Allow-Origin"))
                .as("a CORS header with %s", what)
                .isEmpty();
    }

    private static HttpResponse<String> call(
            final String path, final String authorization, final String accept)
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

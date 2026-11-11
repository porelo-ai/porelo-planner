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
    void theRightTokenReachesThePlanner() throws Exception {
        // The owner's window must get through, or the planner is of no use.
        final RuntimeDirectories.Address address = RuntimeDirectories.read(RUNTIME);
        final HttpResponse<String> answer =
                call("/health", "Bearer " + address.token(), "application/json");
        assertThat(answer.statusCode()).as("the status with the right token").isEqualTo(200);
    }

    @Test
    void aCallWithoutTheTokenIsRefusedOnAnyPathBeforeAnythingAnswers() throws Exception {
        // The check must sit ahead of everything, unknown paths included; its cases are in
        // AccessFilterTest.
        assertRefused(call("/health", null, "application/json"), "no token on a known path");
        assertRefused(
                call("/nothing-here", null, "text/event-stream"), "no token on an unknown path");
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

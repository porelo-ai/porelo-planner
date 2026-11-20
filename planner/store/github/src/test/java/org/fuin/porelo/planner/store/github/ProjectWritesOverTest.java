package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The project writes over the forge client, against GitHub's answers on a local server. */
class ProjectWritesOverTest {

    private static final String TOKEN = "invented-token-0123456789";

    private static final Instant SINCE = Instant.parse("2026-01-02T10:00:00Z");

    /** The paths with queries the server was asked. */
    private final List<String> asked = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private ProjectWrites writes;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
        final URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        final HttpClient http =
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        writes = ProjectWrites.over(new GitHubGraphQl(base, http), new GitHubClient(base, http));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Answers the person as {@code example-user}, and one page of the repository's issues. */
    private void answer(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        asked.add(exchange.getRequestURI().toString());
        final String body;
        if (path.equals("/user")) {
            body =
                    user("example-user").replaceFirst("\\}$", "")
                            + ", \"bio\": null, \"blog\": null, \"company\": null, \"email\": null,"
                            + " \"followers\": 0, \"following\": 0, \"hireable\": null,"
                            + " \"location\": null, \"name\": null, \"public_gists\": 0,"
                            + " \"public_repos\": 0, \"created_at\": \"2020-01-01T00:00:00Z\","
                            + " \"updated_at\": \"2020-01-01T00:00:00Z\"}";
        } else if (path.equals("/repos/example-org/example-lib/issues")) {
            body =
                    "["
                            + issue(1, "example-user", "2026-01-02T10:00:05Z", true)
                            + ","
                            + issue(2, "someone-else", "2026-01-02T10:00:05Z", false)
                            + ","
                            + issue(3, "example-user", "2026-01-02T09:59:59Z", false)
                            + ","
                            + issue(4, "Example-User", "2026-01-02T10:00:05Z", false)
                            // GitHub lists a closed issue only when asked for every state.
                            + (exchange.getRequestURI().getQuery().contains("state=open")
                                    ? ""
                                    : ","
                                            + issue(
                                                    5,
                                                    "example-user",
                                                    "2026-01-02T10:00:05Z",
                                                    false,
                                                    "closed"))
                            + "]";
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** A user as GitHub writes one, with every field it always sends. */
    private static String user(final String login) {
        final String url = "https://example.org/users/" + login;
        return "{\"login\": \""
                + login
                + "\", \"id\": 2, \"node_id\": \"U_2\", \"type\": \"User\", \"site_admin\": false,"
                + " \"gravatar_id\": \"\", \"avatar_url\": \""
                + url
                + "/avatar\", \"url\": \""
                + url
                + "\", \"html_url\": \""
                + url
                + "\", \"events_url\": \""
                + url
                + "/events\", \"followers_url\": \""
                + url
                + "/followers\", \"following_url\": \""
                + url
                + "/following\", \"gists_url\": \""
                + url
                + "/gists\", \"organizations_url\": \""
                + url
                + "/orgs\", \"received_events_url\": \""
                + url
                + "/received_events\", \"repos_url\": \""
                + url
                + "/repos\", \"starred_url\": \""
                + url
                + "/starred\", \"subscriptions_url\": \""
                + url
                + "/subscriptions\"}";
    }

    /** An issue as GitHub lists it: the person's, another's, or a pull request. */
    private static String issue(
            final int number, final String login, final String created, final boolean pull) {
        return issue(number, login, created, pull, "open");
    }

    /** An issue as GitHub lists it, in a state. */
    private static String issue(
            final int number,
            final String login,
            final String created,
            final boolean pull,
            final String state) {
        final String url = "https://example.org/repos/example-org/example-lib/issues/" + number;
        return "{\"id\": "
                + (1000 + number)
                + ", \"node_id\": \"I_"
                + number
                + "\", \"number\": "
                + number
                + ", \"title\": \"A new thing\", \"body\": \"Text.\", \"state\": \""
                + state
                + "\","
                + " \"locked\": false, \"labels\": [], \"milestone\": null, \"comments\": 0,"
                + " \"closed_at\": null, \"url\": \""
                + url
                + "\", \"html_url\": \""
                + url
                + "\", \"comments_url\": \""
                + url
                + "/comments\", \"events_url\": \""
                + url
                + "/events\", \"labels_url\": \""
                + url
                + "/labels\", \"repository_url\":"
                + " \"https://example.org/repos/example-org/example-lib\", \"user\": "
                + user(login)
                + ", \"created_at\": \""
                + created
                + "\", \"updated_at\": \"2026-01-02T10:00:06Z\""
                + (pull
                        ? ", \"pull_request\": {\"url\": \"https://example.org/pulls/"
                                + number
                                + "\", \"html_url\": \"https://example.org/pulls/"
                                + number
                                + "\", \"diff_url\": \"https://example.org/pulls/"
                                + number
                                + ".diff\", \"patch_url\": \"https://example.org/pulls/"
                                + number
                                + ".patch\"}"
                        : "")
                + "}";
    }

    @Test
    void onlyThePersonsOwnOpenIssueMadeSinceIsFoundWhateverTheCaseOfTheLogin() {
        // Harm: a pull request, another person's issue, one made before the call, or a duplicate
        // the person closed taken for the issue an unanswered call made, so the new issue is
        // never made, another one is taken as it, or every new try stops again.
        final ProjectWrites.FoundPage page =
                writes.issuesCreatedSince(TOKEN, "example-org", "example-lib", SINCE, 1);

        assertThat(page.issues())
                .as("the issues found")
                .extracting(found -> found.issue().number())
                .containsExactly(4);
        assertThat(asked)
                .as("the lists asked")
                .anySatisfy(
                        target ->
                                assertThat(target)
                                        .startsWith("/repos/example-org/example-lib/issues?")
                                        .contains("state=open"));
    }
}

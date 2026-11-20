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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.forge.github.GitHubClient;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The conditional reads over the forge client, against GitHub's answers on a local server. */
class ConditionalReadsTest {

    private static final String TOKEN = "invented-token-0123456789";

    /** One ask the server saw: its path with query and the tag sent. */
    private record Asked(String target, @Nullable String tag) {}

    private final List<Asked> asked = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private ConditionalReads reads;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
        final URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        reads = ConditionalReads.over(new GitHubClient(base, HttpClient.newHttpClient()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Answers 304 to the tag it holds, otherwise the page with a next link and a rate limit. */
    private void answer(final HttpExchange exchange) throws IOException {
        final String target = exchange.getRequestURI().toString();
        final String tag = exchange.getRequestHeaders().getFirst("If-None-Match");
        asked.add(new Asked(target, tag));
        exchange.getResponseHeaders().add("X-RateLimit-Limit", "5000");
        exchange.getResponseHeaders().add("X-RateLimit-Remaining", "4321");
        exchange.getResponseHeaders().add("X-RateLimit-Reset", "1795000000");
        if ("W/\"held\"".equals(tag)) {
            exchange.getResponseHeaders().add("ETag", "W/\"held\"");
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("ETag", "W/\"held\"");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (target.contains("/items")) {
            final String next =
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + exchange.getRequestURI().getPath()
                            + "?per_page=100&after=cursor-2";
            exchange.getResponseHeaders().add("Link", "<" + next + ">; rel=\"next\"");
        }
        final byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void aRepositorysIssuesAreAskedNewestUpdateFirstWithTheTagSent() {
        // Harm: the change check asks another list than the one any issue's change moves, or
        // drops the tag, so a change is never seen or every look costs rate limit.
        final ConditionalReads.Answer first =
                reads.issues(TOKEN, "example-org", "example-project", null);
        final ConditionalReads.Answer again =
                reads.issues(TOKEN, "example-org", "example-project", first.etag());

        assertThat(asked)
                .as("the asks GitHub saw")
                .extracting(Asked::tag)
                .containsExactly(null, "W/\"held\"");
        assertThat(asked.get(0).target())
                .as("the list asked")
                .startsWith("/repos/example-org/example-project/issues?")
                .contains("state=all", "sort=updated", "per_page=1");
        assertThat(first)
                .as("the first answer")
                .isEqualTo(new ConditionalReads.Answer(true, "W/\"held\"", null, 4321L, 5000L));
        assertThat(again.changed()).as("the answer to the tag held").isFalse();
        assertThat(again.remaining()).as("requests left").isEqualTo(4321L);
        assertThat(again.limit()).as("requests allowed").isEqualTo(5000L);
    }

    @Test
    void aPageOfTheProjectsItemsIsAskedAtItsCursorAndNamesTheNext() {
        // Harm: a page of items asked at the wrong place, or its next page lost, so items on a
        // later page change unseen.
        final ConditionalReads.Answer page =
                reads.projectItems(TOKEN, "example-org", 7, "cursor-1", null);
        final ConditionalReads.Answer again =
                reads.projectItems(TOKEN, "example-org", 7, "cursor-1", page.etag());

        assertThat(asked.get(0).target())
                .as("the page asked")
                .contains("/orgs/example-org/projectsV2/7/items")
                .contains("after=cursor-1", "per_page=100");
        assertThat(page)
                .as("the changed page")
                .isEqualTo(
                        new ConditionalReads.Answer(
                                true, "W/\"held\"", "cursor-2", 4321L, 5000L, false));
        assertThat(asked.get(1).tag()).as("the tag sent again").isEqualTo("W/\"held\"");
        assertThat(again.changed()).as("the answer to the tag held").isFalse();
    }
}

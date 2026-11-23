package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.forge.github.model.IssueFieldValue;
import org.fuin.porelo.forge.github.model.IssueFieldValueSingleSelectOption;
import org.fuin.porelo.forge.github.model.IssueLabelsInner;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.junit.jupiter.api.Test;

/** Issue updates serialized by the real forge client without opening a socket. */
class ProjectWritesWireTest {

    private static final String TOKEN = "invented-token-0123456789";

    private static final URI BASE = URI.create("https://example.org/");

    private static final String PATH = "/repos/example-org/example-lib/issues/7";

    private static final String ISSUE =
            """
            {
              "id": 1007, "node_id": "I_7", "number": 7,
              "title": "A story", "body": "Text.", "state": "open", "locked": false,
              "labels": [{"id": 1, "node_id": "L_1", "name": "story",
                "url": "https://example.org/labels/story", "color": "abcdef", "default": false}],
              "assignees": [{"login": "example-user", "id": 2, "node_id": "U_2",
                "type": "User", "site_admin": false, "gravatar_id": "",
                "avatar_url": "https://example.org/users/example-user/avatar",
                "url": "https://example.org/users/example-user",
                "html_url": "https://example.org/users/example-user",
                "events_url": "https://example.org/users/example-user/events{/privacy}",
                "followers_url": "https://example.org/users/example-user/followers",
                "following_url": "https://example.org/users/example-user/following{/other_user}",
                "gists_url": "https://example.org/users/example-user/gists{/gist_id}",
                "organizations_url": "https://example.org/users/example-user/orgs",
                "received_events_url": "https://example.org/users/example-user/received_events",
                "repos_url": "https://example.org/users/example-user/repos",
                "starred_url": "https://example.org/users/example-user/starred{/owner}{/repo}",
                "subscriptions_url": "https://example.org/users/example-user/subscriptions"}],
              "milestone": null, "comments": 0, "closed_at": null, "user": null,
              "url": "https://example.org/issues/7", "html_url": "https://example.org/issues/7",
              "comments_url": "https://example.org/issues/7/comments",
              "events_url": "https://example.org/issues/7/events",
              "labels_url": "https://example.org/issues/7/labels{/name}",
              "repository_url": "https://example.org/repos/example-org/example-lib",
              "created_at": "2026-01-02T10:00:00Z", "updated_at": "2026-01-02T10:00:00Z"
            }
            """;

    @Test
    void issuePrioritiesAreSentAsOptionNamesThroughTheRestFieldValues() throws IOException {
        // Harm: a generated request loses the priority, or sends an option's numeric id where
        // GitHub takes its name, so the priority or another field's value is lost.
        final RecordingHttp http = new RecordingHttp();
        final ProjectWrites writes =
                ProjectWrites.over(new GitHubGraphQl(BASE, http), new GitHubClient(BASE, http));
        final IssueField priority = OrganizationFields.fields().getFirst();
        final IssueFieldValue team =
                new IssueFieldValue(
                        23L,
                        "Team",
                        "IFV_23",
                        IssueFieldValue.DataTypeEnum.SINGLE_SELECT,
                        900L,
                        new IssueFieldValueSingleSelectOption(900L, "Platform", "blue"),
                        null);
        writes.updateIssue(
                TOKEN,
                "example-org",
                "example-lib",
                7,
                null,
                null,
                null,
                IssuePriority.update(
                        "example-lib#7",
                        priority,
                        List.of(OrganizationFields.priority("High"), team),
                        "Highest"));
        final JsonNode update =
                new ObjectMapper().readTree(http.asked.getLast().body()).path("issue_field_values");
        assertThat(update.get(0).path("field_id").asLong())
                .as("the other field's id")
                .isEqualTo(23L);
        assertThat(update.get(0).path("value").asText())
                .as("the other field's option, by name")
                .isEqualTo("Platform");
        assertThat(update.get(1).path("field_id").asLong())
                .as("the organization's Priority")
                .isEqualTo(OrganizationFields.PRIORITY_ID);
        assertThat(update.get(1).path("value").asText())
                .as("Highest sent as GitHub's option name")
                .isEqualTo("Urgent");

        writes.createIssue(
                TOKEN,
                "example-org",
                "example-lib",
                "Title",
                "Body",
                null,
                IssuePriority.create(priority, "Highest"));
        final JsonNode create = new ObjectMapper().readTree(http.asked.getLast().body());
        assertThat(create.path("issue_field_values").get(0).path("value").asText())
                .as("the option name on create")
                .isEqualTo("Urgent");
    }

    @Test
    void titleBodyAndStateWritesLeaveLabelsAndAssigneesUntouched() throws IOException {
        // Harm: a partial update clears an issue's labels or assignment without the person's
        // intent.
        final RecordingHttp http = new RecordingHttp();
        final GitHubClient rest = new GitHubClient(BASE, http);
        final ProjectWrites writes = ProjectWrites.over(new GitHubGraphQl(BASE, http), rest);

        writes.updateIssue(TOKEN, "example-org", "example-lib", 7, "New title", null, null);
        writes.updateIssue(TOKEN, "example-org", "example-lib", 7, null, "New body", null);
        writes.updateIssue(
                TOKEN,
                "example-org",
                "example-lib",
                7,
                null,
                null,
                new ProjectWrites.Opening(true, false));
        writes.updateIssue(
                TOKEN,
                "example-org",
                "example-lib",
                7,
                null,
                null,
                new ProjectWrites.Opening(false, false));
        writes.updateIssue(
                TOKEN,
                "example-org",
                "example-lib",
                7,
                null,
                null,
                new ProjectWrites.Opening(false, true));
        writes.updateIssue(
                TOKEN,
                "example-org",
                "example-lib",
                7,
                "New title",
                "New body",
                new ProjectWrites.Opening(true, false));

        final List<String> expected =
                List.of(
                        "{\"title\":\"New title\"}",
                        "{\"body\":\"New body\"}",
                        "{\"state\":\"open\"}",
                        "{\"state\":\"closed\",\"state_reason\":\"completed\"}",
                        "{\"state\":\"closed\",\"state_reason\":\"not_planned\"}",
                        "{\"title\":\"New title\",\"body\":\"New body\",\"state\":\"open\"}");
        assertThat(http.asked).as("one PATCH per partial update").hasSize(expected.size());
        final ObjectMapper json = new ObjectMapper();
        for (int at = 0; at < expected.size(); at++) {
            final Asked asked = http.asked.get(at);
            assertThat(asked.method()).as("the update method").isEqualTo("PATCH");
            assertThat(asked.path()).as("the updated issue").isEqualTo(PATH);
            final JsonNode body = json.readTree(asked.body());
            assertThat(body.has("labels")).as("labels must be omitted from PATCH %s", at).isFalse();
            assertThat(body.has("assignees"))
                    .as("assignees must be omitted from PATCH %s", at)
                    .isFalse();
            assertThat(body)
                    .as("only the requested fields are sent in PATCH %s", at)
                    .isEqualTo(json.readTree(expected.get(at)));
        }

        final var readBack = rest.issue(TOKEN, "example-org", "example-lib", 7);
        assertThat(readBack.labels())
                .as("the story label read back through the real client")
                .extracting(IssueLabelsInner::name)
                .containsExactly("story");
        assertThat(readBack.assignees())
                .as("the assignee read back through the real client")
                .extracting(SimpleUser::login)
                .containsExactly("example-user");
        assertThat(http.asked.getLast())
                .as("the read-back request")
                .isEqualTo(new Asked("GET", PATH, ""));
    }

    @Test
    void aStoryIsMadeWithTheIssueTypeStoryAndNoLabelAndItsTypeIsReadBack() throws IOException {
        // Harm: the type sent in another place of the request, or a regenerated client's new
        // component before it, makes every idea's story untyped, and its write stops partly saved.
        final RecordingHttp http = new RecordingHttp();
        http.answer =
                ISSUE.replace(
                        "\"milestone\": null,",
                        "\"type\": {\"id\": 3, \"node_id\": \"IT_3\", \"name\": \"Story\","
                                + " \"description\": \"A story\", \"color\": \"green\","
                                + " \"created_at\": \"2026-01-01T00:00:00Z\","
                                + " \"updated_at\": \"2026-01-01T00:00:00Z\","
                                + " \"is_enabled\": true}, \"milestone\": null,");
        final ProjectWrites writes =
                ProjectWrites.over(new GitHubGraphQl(BASE, http), new GitHubClient(BASE, http));

        final ProjectWrites.Created made =
                writes.createIssue(
                        TOKEN, "example-org", "example-lib", "A story", "Text.", "Story");

        final Asked asked = http.asked.getLast();
        assertThat(asked.method()).as("the create method").isEqualTo("POST");
        assertThat(asked.path())
                .as("the create path")
                .isEqualTo("/repos/example-org/example-lib/issues");
        final JsonNode body = new ObjectMapper().readTree(asked.body());
        assertThat(body.path("type").asText()).as("the issue type sent").isEqualTo("Story");
        assertThat(body.has("labels")).as("no label sent").isFalse();
        assertThat(made.type()).as("the type read back").isEqualTo("Story");
        assertThat(made.number()).as("the number read back").isEqualTo(7);
    }

    private record Asked(String method, String path, String body) {}

    private record Reply<T>(HttpRequest request, T body) implements HttpResponse<T> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(
                    Map.of("Content-Type", List.of("application/json")), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class RecordingHttp extends HttpClient {
        private final List<Asked> asked = new ArrayList<>();

        /** The issue every call is answered with. */
        private String answer = ISSUE;

        @Override
        public <T> HttpResponse<T> send(
                final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final CompletableFuture<String> body = new CompletableFuture<>();
            request.bodyPublisher()
                    .ifPresentOrElse(
                            publisher ->
                                    publisher.subscribe(
                                            new Flow.Subscriber<ByteBuffer>() {
                                                @Override
                                                public void onSubscribe(
                                                        final Flow.Subscription subscription) {
                                                    subscription.request(Long.MAX_VALUE);
                                                }

                                                @Override
                                                public void onNext(final ByteBuffer buffer) {
                                                    final byte[] chunk =
                                                            new byte[buffer.remaining()];
                                                    buffer.get(chunk);
                                                    bytes.writeBytes(chunk);
                                                }

                                                @Override
                                                public void onError(final Throwable error) {
                                                    body.completeExceptionally(error);
                                                }

                                                @Override
                                                public void onComplete() {
                                                    body.complete(
                                                            bytes.toString(StandardCharsets.UTF_8));
                                                }
                                            }),
                            () -> body.complete(""));
            asked.add(new Asked(request.method(), request.uri().getPath(), body.join()));
            final HttpResponse.BodySubscriber<T> subscriber =
                    handler.apply(
                            new HttpResponse.ResponseInfo() {
                                @Override
                                public int statusCode() {
                                    return 200;
                                }

                                @Override
                                public HttpHeaders headers() {
                                    return HttpHeaders.of(
                                            Map.of("Content-Type", List.of("application/json")),
                                            (name, value) -> true);
                                }

                                @Override
                                public Version version() {
                                    return Version.HTTP_1_1;
                                }
                            });
            subscriber.onSubscribe(
                    new Flow.Subscription() {
                        @Override
                        public void request(final long count) {}

                        @Override
                        public void cancel() {}
                    });
            subscriber.onNext(List.of(ByteBuffer.wrap(answer.getBytes(StandardCharsets.UTF_8))));
            subscriber.onComplete();
            return new Reply<>(request, subscriber.getBody().toCompletableFuture().join());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request,
                final HttpResponse.BodyHandler<T> handler,
                final HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }
}

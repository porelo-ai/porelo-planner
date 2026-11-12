package org.fuin.porelo.planner.store.git.pushes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.store.git.stories.CommitRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PushRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    private static final String ACTIVITY =
            "https://api.github.com/repos/porelo-ai/porelo-x/activity"
                    + "?ref=refs%2Fheads%2Fmain&per_page=100";

    private static final String TOKEN = "ghp_test_0123456789";

    private static final int FOUR_MEGABYTES = 4 * 1024 * 1024;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private final List<String> checked = new ArrayList<>();

    private final Map<String, Integer> answers = new HashMap<>();

    // Prevents a commit pushed a day after it was made from going unchecked: the push's own range
    // is read, whatever its commits' dates.
    @Test
    void refusesAnOldCommitInADaysPush() {
        answers.put(range('a', 'b'), CommitRules.REFUSED);
        final FakeActivity web =
                new FakeActivity()
                        .serve(ACTIVITY, activity(push('a', 'b', "2026-10-27T11:00:00Z")));

        assertThat(run(web, env(), 26)).as("the day's push holds a refused commit").isEqualTo(1);
        assertThat(checked).as("its range is read").containsExactly(range('a', 'b'));
        assertThat(text(out)).as("the push is named").contains(sha('a').substring(0, 7));
    }

    // Prevents a quiet day from failing the scheduled run.
    @Test
    void aCleanDayPasses() {
        final FakeActivity web = new FakeActivity().serve(ACTIVITY, "[]");

        assertThat(run(web, env(), 26)).as("no push").isZero();
        assertThat(checked).as("nothing to read").isEmpty();
        assertThat(text(out)).as("it says so").contains("no push to main");
    }

    // Prevents an older push, already read by an earlier day, from being read again and again.
    @Test
    void aPushOlderThanTheWindowIsNotRead() {
        final FakeActivity web =
                new FakeActivity()
                        .serve(
                                ACTIVITY,
                                activity(
                                        push('c', 'd', "2026-10-27T10:00:00Z"),
                                        push('a', 'c', "2026-10-26T09:00:00Z")));

        assertThat(run(web, env(), 26)).as("the day's push is clean").isZero();
        assertThat(checked).as("only the push inside 26 hours").containsExactly(range('c', 'd'));
    }

    // Prevents a page the command cannot follow from hiding pushes inside the window.
    @Test
    void aFullPageStillInsideTheWindowIsUnanswered() {
        final String[] pushes = new String[100];
        for (int i = 0; i < pushes.length; i++) {
            pushes[i] = push('a', 'b', "2026-10-27T11:00:00Z");
        }
        final FakeActivity web = new FakeActivity().serve(ACTIVITY, activity(pushes));

        assertThat(run(web, env(), 26)).as("a page it cannot follow").isEqualTo(2);
        assertThat(checked).as("nothing is read half").isEmpty();
        assertThat(text(err)).as("it says why").contains("100");
    }

    // Prevents an unreachable API, a missing token or another forge from passing as a clean day.
    @Test
    void whatCannotBeAskedIsUnanswered() {
        assertThat(run(new FakeActivity().fail(ACTIVITY, "connection refused"), env(), 26))
                .as("the API could not be reached")
                .isEqualTo(2);
        assertThat(text(err)).as("the address is named").contains("/activity");

        final Map<String, String> noToken = new HashMap<>(env());
        noToken.remove("GITHUB_TOKEN");
        final FakeActivity untouched = new FakeActivity();
        assertThat(run(untouched, noToken, 26)).as("no token").isEqualTo(2);

        final Map<String, String> forge = new HashMap<>(env());
        forge.put("GITHUB_API_URL", "https://192.0.2.1:3000/api/v1");
        assertThat(run(untouched, forge, 26)).as("another forge's API").isEqualTo(2);
        assertThat(untouched.asked).as("neither asked anything").isEmpty();
        assertThat(checked).as("nothing was read").isEmpty();
    }

    // Prevents a commit the checkout lacks from passing unread.
    @Test
    void aRangeTheCheckoutCannotReadIsUnanswered() {
        answers.put(range('e', 'f'), CommitRules.UNANSWERED);
        final FakeActivity web =
                new FakeActivity()
                        .serve(ACTIVITY, activity(push('e', 'f', "2026-10-27T11:30:00Z")));

        assertThat(run(web, env(), 26)).as("git could not read it").isEqualTo(2);
    }

    // Prevents the token from reaching a log, or GitHub's API from being asked without it.
    @Test
    void theTokenAppearsInNoOutput() {
        final FakeActivity web =
                new FakeActivity()
                        .serve(ACTIVITY, activity(push('a', 'b', "2026-10-27T11:00:00Z")));

        run(web, env(), 26);
        run(new FakeActivity().answer(ACTIVITY, 500, "{}"), env(), 26);

        assertThat(web.headers).as("GitHub was asked once").hasSize(1);
        assertThat(web.headers.getFirst().get("Authorization"))
                .as("the token goes to GitHub as a bearer")
                .isEqualTo("Bearer " + TOKEN);
        assertThat(web.headers.getFirst().get("Accept"))
                .as("GitHub's own media type is asked for")
                .isEqualTo("application/vnd.github+json");
        assertThat(text(out) + text(err)).as("and nowhere else").doesNotContain(TOKEN);
    }

    // Prevents the push that made main from going unread: every commit up to its head is new.
    @Test
    void thePushThatMadeMainIsReadWhole() {
        final FakeActivity web =
                new FakeActivity()
                        .serve(
                                ACTIVITY,
                                activity(
                                        activity(
                                                sha('0'),
                                                sha('a'),
                                                "refs/heads/main",
                                                "2026-10-27T11:00:00Z",
                                                "branch_creation")));

        assertThat(run(web, env(), 26)).as("main's first push is clean").isZero();
        assertThat(checked).as("its head and all before it").containsExactly(sha('a'));
    }

    // Prevents another branch's activity from being read as main's.
    @Test
    void anotherBranchIsNotRead() {
        final FakeActivity web =
                new FakeActivity()
                        .serve(
                                ACTIVITY,
                                activity(
                                        activity(
                                                sha('a'),
                                                sha('b'),
                                                "refs/heads/topic",
                                                "2026-10-27T11:00:00Z",
                                                "push")));

        assertThat(run(web, env(), 26)).as("no push to main").isZero();
        assertThat(checked).as("the topic branch is not read").isEmpty();
    }

    // Prevents a later clean push from hiding an earlier refused one, and keeps the pushes in
    // order.
    @Test
    void everyPushIsReadOldestFirstAndTheWorstCounts() {
        answers.put(range('a', 'b'), CommitRules.REFUSED);
        final FakeActivity web =
                new FakeActivity()
                        .serve(
                                ACTIVITY,
                                activity(
                                        push('b', 'c', "2026-10-27T11:00:00Z"),
                                        push('a', 'b', "2026-10-27T09:00:00Z")));

        assertThat(run(web, env(), 26)).as("the earlier push is refused").isEqualTo(1);
        assertThat(checked)
                .as("both, oldest first")
                .containsExactly(range('a', 'b'), range('b', 'c'));
    }

    // Prevents a token the client cannot send, or an error that repeats it, from reaching a log.
    @Test
    void aTokenInAnErrorIsNeverShown() {
        assertThat(run(new FakeActivity().fail(ACTIVITY, "rejected " + TOKEN), env(), 26))
                .as("the API refused")
                .isEqualTo(2);
        final Map<String, String> broken = new HashMap<>(env());
        broken.put("GITHUB_TOKEN", TOKEN + "\n");
        final FakeActivity untouched = new FakeActivity();
        assertThat(run(untouched, broken, 26)).as("a token with a newline").isEqualTo(2);

        assertThat(untouched.asked).as("a token with a newline is never sent").isEmpty();
        assertThat(text(out) + text(err)).as("neither shows it").doesNotContain(TOKEN);
    }

    // Prevents an answer that quotes the token, read back into a line, from leaking it to a log.
    @Test
    void theTokenIsNeverSaidEvenWhenTheAnswerQuotesIt() {
        assertThat(
                        run(
                                new FakeActivity()
                                        .serve(
                                                ACTIVITY,
                                                activity(
                                                        push(
                                                                'a',
                                                                'b',
                                                                "token " + TOKEN + " expired"))),
                                env(),
                                26))
                .as("a time that quotes the token")
                .isEqualTo(2);
        assertThat(text(err)).as("the quoted value is said redacted").contains("<token>");
        assertThat(run(new FakeActivity().serve(ACTIVITY, "Bad credentials: " + TOKEN), env(), 26))
                .as("a body that is no JSON and quotes the token")
                .isEqualTo(2);
        assertThat(
                        run(
                                new FakeActivity()
                                        .serve(ACTIVITY, "{\"message\":\"" + TOKEN + "\""),
                                env(),
                                26))
                .as("broken JSON that quotes the token")
                .isEqualTo(2);
        assertThat(
                        run(
                                new FakeActivity()
                                        .serve(ACTIVITY, "{\"message\":\"" + TOKEN + "\"}"),
                                env(),
                                26))
                .as("an object that quotes the token")
                .isEqualTo(2);

        assertThat(text(err)).as("JSON is refused without quoting the body").contains("JSON");
        assertThat(text(out) + text(err)).as("no line shows the token").doesNotContain(TOKEN);
    }

    // Prevents a redirect from carrying the token elsewhere, and an error page from passing as a
    // clean day.
    @ParameterizedTest
    @ValueSource(ints = {301, 302, 403, 404, 500})
    void anAnswerOutsideTwoHundredIsUnansweredAndNotFollowed(int status) {
        final FakeActivity web =
                new FakeActivity()
                        .answer(ACTIVITY, status, activity(push('a', 'b', "2026-10-27T11:00:00Z")));

        assertThat(run(web, env(), 26)).as("HTTP " + status + " is no answer").isEqualTo(2);
        assertThat(web.asked).as("one request, nothing followed").hasSize(1);
        assertThat(checked).as("its body is not read as activity").isEmpty();
        assertThat(text(err)).as("the status is named").contains(": HTTP " + status);
    }

    // Prevents a body read two ways, a duplicate key or text after the list, from deciding which
    // range is checked.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "[{\"id\":1,\"before\":\"%1$s\",\"after\":\"%2$s\",\"after\":\"%1$s\","
                        + "\"ref\":\"refs/heads/main\",\"timestamp\":\"2026-10-27T11:00:00Z\","
                        + "\"activity_type\":\"push\"}]",
                "[] []"
            })
    void anAnswerThatIsNoPlainJsonIsUnanswered(String body) {
        final FakeActivity web =
                new FakeActivity().serve(ACTIVITY, body.formatted(sha('a'), sha('b')));

        assertThat(run(web, env(), 26)).as("an ambiguous body").isEqualTo(2);
        assertThat(checked).as("no range is read from it").isEmpty();
    }

    // Prevents the token from being sent to a GitHub Enterprise server or another forge that
    // claims to be GitHub.
    @Test
    void aForeignServerIsUnansweredWithoutARequest() {
        final Map<String, String> foreign = new HashMap<>(env());
        foreign.put("GITHUB_SERVER_URL", "https://git.example.org");
        final FakeActivity untouched = new FakeActivity();

        assertThat(run(untouched, foreign, 26)).as("another server").isEqualTo(2);
        assertThat(untouched.asked).as("nothing was asked").isEmpty();
        assertThat(text(err)).as("the server is named").contains("git.example.org");

        final Map<String, String> github = new HashMap<>(env());
        github.put("GITHUB_SERVER_URL", "https://github.com");
        assertThat(run(new FakeActivity().serve(ACTIVITY, "[]"), github, 26))
                .as("GitHub's own server is read")
                .isZero();
    }

    // Prevents an owner or name of dots from moving the request, and the token, to another path
    // of the API.
    @ParameterizedTest
    @ValueSource(strings = {"../porelo-x", "porelo-ai/..", "./porelo-x", "porelo-ai/.", ".x/y"})
    void anOwnerOrNameOfDotsIsUnansweredWithoutARequest(String repository) {
        final Map<String, String> dotted = new HashMap<>(env());
        dotted.put("GITHUB_REPOSITORY", repository);
        final FakeActivity untouched = new FakeActivity();

        assertThat(run(untouched, dotted, 26)).as(repository + " is refused").isEqualTo(2);
        assertThat(untouched.asked).as("nothing was asked").isEmpty();
    }

    // Prevents the real reader from following a redirect, which would carry the token's request to
    // wherever the answer points.
    @Test
    void theRealReaderDoesNotFollowARedirect() throws IOException {
        final AtomicInteger elsewhere = new AtomicInteger();
        final HttpServer server = server();
        server.createContext(
                "/start",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "/elsewhere");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/elsewhere",
                exchange -> {
                    elsewhere.incrementAndGet();
                    respond(exchange, "[]".getBytes(StandardCharsets.UTF_8));
                });
        server.start();
        try {
            final PushRules.Activity.Answer answer =
                    PushRules.Activity.overHttp().read(address(server, "/start"), Map.of());

            assertThat(answer.status()).as("the redirect itself is the answer").isEqualTo(302);
            assertThat(elsewhere.get()).as("its target is never asked").isZero();
        } finally {
            server.stop(0);
        }
    }

    // Prevents an endless or huge answer from filling the memory of the run that reads it.
    @Test
    void theRealReaderRefusesABodyOverFourMegabytes() throws IOException {
        final HttpServer server = server();
        server.createContext("/exact", exchange -> respond(exchange, new byte[FOUR_MEGABYTES]));
        server.createContext("/over", exchange -> respond(exchange, new byte[FOUR_MEGABYTES + 1]));
        server.start();
        try {
            final PushRules.Activity reader = PushRules.Activity.overHttp();

            assertThat(reader.read(address(server, "/exact"), Map.of()).body())
                    .as("four megabytes are read whole")
                    .hasSize(FOUR_MEGABYTES);
            assertThatThrownBy(() -> reader.read(address(server, "/over"), Map.of()))
                    .as("one byte more is no answer")
                    .isInstanceOf(IOException.class);
        } finally {
            server.stop(0);
        }
    }

    private int run(PushRules.Activity web, Map<String, String> env, int hours) {
        return new PushRules(
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        web,
                        env,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        range -> {
                            checked.add(range);
                            return answers.getOrDefault(range, 0);
                        })
                .check(Duration.ofHours(hours));
    }

    private static Map<String, String> env() {
        return Map.of(
                "GITHUB_API_URL",
                "https://api.github.com",
                "GITHUB_REPOSITORY",
                "porelo-ai/porelo-x",
                "GITHUB_TOKEN",
                TOKEN);
    }

    private static HttpServer server() throws IOException {
        final HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        return server;
    }

    private static URI address(HttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void respond(HttpExchange exchange, byte[] body) {
        try (OutputStream stream = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(200, body.length);
            stream.write(body);
        } catch (IOException gone) {
            // The reader hangs up once it has read enough; that is what is tested.
        } finally {
            exchange.close();
        }
    }

    private static String activity(String... pushes) {
        return "[" + String.join(",", pushes) + "]";
    }

    private static String push(char before, char after, String when) {
        return activity(sha(before), sha(after), "refs/heads/main", when, "push");
    }

    private static String activity(
            String before, String after, String ref, String when, String type) {
        return "{\"id\":1,\"before\":\""
                + before
                + "\",\"after\":\""
                + after
                + "\",\"ref\":\""
                + ref
                + "\",\"timestamp\":\""
                + when
                + "\",\"activity_type\":\""
                + type
                + "\"}";
    }

    private static String sha(char c) {
        return String.valueOf(c).repeat(40);
    }

    private static String range(char before, char after) {
        return sha(before) + ".." + sha(after);
    }

    private static String text(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }

    /** GitHub's API as the tests answer it, recording each request; an address not served fails. */
    private static final class FakeActivity implements PushRules.Activity {

        private final Map<String, Answer> answers = new HashMap<>();

        private final Map<String, String> failures = new HashMap<>();

        final List<URI> asked = new ArrayList<>();

        final List<Map<String, String>> headers = new ArrayList<>();

        FakeActivity serve(String address, String body) {
            return answer(address, 200, body);
        }

        FakeActivity answer(String address, int status, String body) {
            answers.put(address, new Answer(status, body.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        FakeActivity fail(String address, String message) {
            failures.put(address, message);
            return this;
        }

        @Override
        public Answer read(URI address, Map<String, String> sent) throws IOException {
            asked.add(address);
            headers.add(Map.copyOf(sent));
            final String failure = failures.get(address.toString());
            if (failure != null) {
                throw new IOException(failure);
            }
            final Answer answer = answers.get(address.toString());
            if (answer == null) {
                throw new AssertionError("asked an address the test does not serve: " + address);
            }
            return answer;
        }
    }
}

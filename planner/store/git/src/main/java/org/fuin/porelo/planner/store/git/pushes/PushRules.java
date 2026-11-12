package org.fuin.porelo.planner.store.git.pushes;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.git.stories.CommitRules;
import org.jspecify.annotations.Nullable;

/**
 * Holds every push to {@code main} of a recent window to the shared rules on commit messages, by
 * the push's own range, with the lines and exit codes the build tools' {@code check-pushes} gives.
 *
 * <p>A range picked by committer date misses a commit made long before its push, when that push
 * starts no Build of its own. GitHub's repository activity names each push with its {@code before}
 * and {@code after}, so the push is read as it happened. Only GitHub is asked: the token is read
 * from the environment and sent only as the {@code Authorization} header to GitHub's API; a
 * redirect is never followed, and every line said has the token taken out.
 */
public final class PushRules {

    /** The one API this command knows how to ask. */
    static final String GITHUB_API = "https://api.github.com";

    /** The one server whose API {@link #GITHUB_API} is. */
    static final String GITHUB_SERVER = "https://github.com";

    /** The most activity one page holds; a full page may hide more. */
    static final int PAGE = 100;

    /** The kinds of activity that move {@code main} to a new commit, its creation among them. */
    private static final Set<String> PUSHES =
            Set.of("push", "force_push", "pr_merge", "merge_queue_merge", "branch_creation");

    /** The branch this command reads. */
    private static final String MAIN = "refs/heads/main";

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}");

    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    /** How long a connection may take to open. */
    private static final Duration CONNECT = Duration.ofSeconds(10);

    /** How long the whole answer may take, its body read to the end. */
    private static final Duration DEADLINE = Duration.ofSeconds(60);

    /** The longest body read; a page of activity is far shorter. */
    private static final int LONGEST_BODY = 4 * 1024 * 1024;

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Checks one range's commits, as {@code check-commits} does, and answers its exit code. */
    @FunctionalInterface
    public interface Commits {

        /**
         * Checks a range.
         *
         * @param range {@code before..after}, or a head alone
         * @return {@code check-commits}' exit code
         */
        int check(String range);
    }

    /** Reads one address over HTTP, answering whatever status it gives and never following it. */
    @FunctionalInterface
    public interface Activity {

        /**
         * One answer.
         *
         * @param status the HTTP status
         * @param body the body, read only for a 2xx status
         */
        record Answer(int status, byte[] body) {}

        /**
         * Reads an address once.
         *
         * @param address what is asked
         * @param headers the request's headers
         * @return the answer, whatever its status
         * @throws IOException when no answer came, or one too long or too late
         */
        Answer read(URI address, Map<String, String> headers) throws IOException;

        /**
         * Reads over {@code java.net.http}: no redirect followed, a connection opened within 10
         * seconds, the whole answer within 60 seconds and a body of at most 4 MB.
         *
         * @return the reader
         */
        static Activity overHttp() {
            return new HttpActivity();
        }
    }

    /**
     * One push to {@code main}.
     *
     * @param range what {@code check-commits} reads: {@code before..after}, or {@code after} alone
     *     for the push that made {@code main}, whose every commit is new
     */
    private record Push(String range, String after, Instant when) {}

    private final PrintStream out;

    private final PrintStream err;

    private final Activity activity;

    private final Map<String, String> env;

    private final Clock clock;

    private final Commits commits;

    /**
     * A checker that reports on two streams.
     *
     * @param out where each push and its result go
     * @param err where an unanswered question is explained
     * @param activity where GitHub is read
     * @param env the environment: {@code GITHUB_API_URL}, {@code GITHUB_SERVER_URL}, {@code
     *     GITHUB_REPOSITORY} and {@code GITHUB_TOKEN}; the token goes to GitHub's API alone
     * @param clock what "now" is
     * @param commits checks one push's range
     */
    public PushRules(
            PrintStream out,
            PrintStream err,
            Activity activity,
            Map<String, String> env,
            Clock clock,
            Commits commits) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.env = Map.copyOf(env);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.commits = Objects.requireNonNull(commits, "commits");
    }

    /**
     * Checks every push to {@code main} younger than a window.
     *
     * @param window how far back pushes are read
     * @return 0 when every push holds to the rules, an empty window among them; {@link
     *     CommitRules#REFUSED} when a commit breaks one; {@link CommitRules#UNANSWERED} when a push
     *     or the activity could not be read
     */
    public int check(Duration window) {
        final List<Push> pushes;
        try {
            pushes = pushes(clock.instant().minus(window));
        } catch (Unanswered unanswered) {
            err.println(redacted(Objects.requireNonNullElse(unanswered.getMessage(), "")));
            return CommitRules.UNANSWERED;
        }
        if (pushes.isEmpty()) {
            out.println("no push to main in the last " + window.toHours() + " hours");
            return 0;
        }
        int worst = 0;
        for (final Push push : pushes) {
            out.println("push " + push.range() + " to " + push.after() + " at " + push.when());
            worst = Math.max(worst, commits.check(push.range()));
        }
        return worst;
    }

    /** The pushes to {@code main} since a moment, the oldest first. */
    private List<Push> pushes(Instant since) throws Unanswered {
        final String api = Objects.requireNonNullElse(env.get("GITHUB_API_URL"), GITHUB_API);
        if (!GITHUB_API.equals(api)) {
            throw new Unanswered(
                    "check-pushes reads GitHub's repository activity, and "
                            + api
                            + " is not GitHub's API; this forge's pushes are not read");
        }
        final String server = env.get("GITHUB_SERVER_URL");
        if (server != null && !GITHUB_SERVER.equals(server)) {
            throw new Unanswered(
                    "check-pushes reads GitHub's repository activity, and "
                            + server
                            + " is not GitHub; this forge's pushes are not read");
        }
        final String repository = Objects.requireNonNullElse(env.get("GITHUB_REPOSITORY"), "");
        if (!REPOSITORY.matcher(repository).matches()) {
            throw new Unanswered("GITHUB_REPOSITORY names no owner/name: '" + repository + "'");
        }
        // A part of "." or ".." would move the address to another path of the API.
        for (final String part : repository.split("/", -1)) {
            if (part.startsWith(".")) {
                throw new Unanswered(
                        "GITHUB_REPOSITORY names an owner or name starting with a dot: '"
                                + repository
                                + "'");
            }
        }
        final String token = env.get("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new Unanswered("GITHUB_TOKEN is not set, and the repository's activity needs it");
        }
        // A space or control character makes the header unsendable, and the client's refusal would
        // repeat the value.
        if (token.chars().anyMatch(c -> c <= ' ' || c == 0x7f)) {
            throw new Unanswered("GITHUB_TOKEN holds a character no header can carry");
        }
        final URI address =
                URI.create(
                        GITHUB_API
                                + "/repos/"
                                + repository
                                + "/activity?ref=refs%2Fheads%2Fmain&per_page="
                                + PAGE);
        final JsonNode listed = read(address, token);
        if (listed == null || !listed.isArray()) {
            throw new Unanswered(address + " did not list the repository's activity");
        }
        final List<Push> pushes = new ArrayList<>();
        @Nullable Instant oldest = null;
        for (final JsonNode each : listed) {
            if (!each.isObject()) {
                throw new Unanswered(address + " listed something that is no activity");
            }
            final Instant when = when(address, each.get("timestamp"));
            oldest = oldest == null || when.isBefore(oldest) ? when : oldest;
            if (when.isBefore(since)
                    || !MAIN.equals(text(each.get("ref")))
                    || !PUSHES.contains(
                            Objects.requireNonNullElse(text(each.get("activity_type")), ""))) {
                continue;
            }
            final String before = hash(address, each.get("before"));
            final String after = hash(address, each.get("after"));
            // The push that made main has no commit before it: every commit up to after is new.
            pushes.add(
                    new Push(
                            before.chars().allMatch(c -> c == '0') ? after : before + ".." + after,
                            after,
                            when));
        }
        // Only the first page is read: a full one whose oldest entry is still inside the window may
        // hide pushes on the next.
        if (listed.size() >= PAGE && oldest != null && !oldest.isBefore(since)) {
            throw new Unanswered(
                    address
                            + " answered a full page of "
                            + PAGE
                            + " still inside the window, so pushes may be missing");
        }
        pushes.sort(Comparator.comparing(Push::when));
        return pushes;
    }

    /** A message with the token taken out, whatever put it there. */
    private String redacted(String message) {
        final String token = env.get("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            return message;
        }
        // A token with a stray space or newline is refused, yet its core is still the secret.
        return message.replace(token, "<token>").replace(token.strip(), "<token>");
    }

    /** The headers for an address: the token only for GitHub's API over HTTPS. */
    private static Map<String, String> headers(URI address, String token) {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        if ("https".equals(address.getScheme())
                && "api.github.com".equals(address.getHost())
                && address.getPort() == -1
                && address.getUserInfo() == null) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    private static @Nullable String text(@Nullable JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Instant when(URI address, @Nullable JsonNode value) throws Unanswered {
        final String text = text(value);
        if (text != null) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ex) {
                throw new Unanswered(address + " gave a time that is none: '" + text + "'");
            }
        }
        throw new Unanswered(address + " gave an activity without a time");
    }

    private static String hash(URI address, @Nullable JsonNode value) throws Unanswered {
        final String text = text(value);
        if (text != null && HASH.matcher(text).matches()) {
            return text;
        }
        throw new Unanswered(address + " gave a push without a full commit hash");
    }

    private @Nullable JsonNode read(URI address, String token) throws Unanswered {
        final Activity.Answer answer;
        try {
            answer = activity.read(address, headers(address, token));
        } catch (IOException | RuntimeException ex) {
            throw new Unanswered(
                    address
                            + " could not be read: "
                            + Objects.requireNonNullElse(ex.getMessage(), ""));
        }
        if (answer.status() < 200 || answer.status() > 299) {
            throw new Unanswered(address + ": HTTP " + answer.status());
        }
        try {
            return JSON.readTree(answer.body());
        } catch (IOException ex) {
            // Jackson's message can quote the body, so it is not repeated.
            throw new Unanswered(address + " did not answer with JSON");
        }
    }

    /** A question that could not be answered, said as one line. */
    private static final class Unanswered extends Exception {

        private static final long serialVersionUID = 1L;

        Unanswered(String message) {
            super(message);
        }
    }

    /** The reader over {@code java.net.http}. */
    private static final class HttpActivity implements Activity {

        @Override
        public Answer read(URI address, Map<String, String> headers) throws IOException {
            final HttpRequest.Builder request =
                    HttpRequest.newBuilder(address).timeout(DEADLINE).GET();
            headers.forEach(request::header);
            final HttpClient client =
                    HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(CONNECT)
                            .build();
            final Reading reading = new Reading(client, request.build());
            final FutureTask<Answer> task = new FutureTask<>(reading);
            Thread.ofVirtual().name("push-rules-activity").start(task);
            try {
                return task.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                throw new IOException("no answer within " + DEADLINE.toSeconds() + " seconds");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while reading");
            } catch (ExecutionException ex) {
                final Throwable cause = ex.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                final @Nullable String why = cause == null ? null : cause.getMessage();
                throw new IOException(Objects.requireNonNullElse(why, "the read failed"));
            } finally {
                if (!task.isDone()) {
                    task.cancel(true);
                    reading.abandon();
                }
                client.shutdownNow();
            }
        }
    }

    /** One request and its body, read on its own thread so a deadline can give it up. */
    private static final class Reading implements Callable<Activity.Answer> {

        private final HttpClient client;

        private final HttpRequest request;

        private volatile @Nullable InputStream body;

        private volatile boolean abandoned;

        Reading(HttpClient client, HttpRequest request) {
            this.client = client;
            this.request = request;
        }

        @Override
        public Activity.Answer call() throws IOException, InterruptedException {
            final HttpResponse<InputStream> response =
                    client.send(request, BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                body = stream;
                // The deadline may have passed before the headers came, when there was no stream.
                if (abandoned) {
                    throw new IOException("abandoned after the deadline");
                }
                final int status = response.statusCode();
                if (status < 200 || status > 299) {
                    return new Activity.Answer(status, new byte[0]);
                }
                final byte[] read = stream.readNBytes(LONGEST_BODY + 1);
                if (read.length > LONGEST_BODY) {
                    throw new IOException("the answer is longer than " + LONGEST_BODY + " bytes");
                }
                return new Activity.Answer(status, read);
            }
        }

        /** Gives the read up, closing the body so a blocked read ends. */
        void abandon() {
            abandoned = true;
            final InputStream stream = body;
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // The read is given up anyway; a failed close leaves nothing to report.
                }
            }
        }
    }
}

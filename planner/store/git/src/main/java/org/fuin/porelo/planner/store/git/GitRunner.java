package org.fuin.porelo.planner.store.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one {@link GitCall} with no input unless one is given, a hard time limit and bounded output,
 * and tells a refused login from any other failure.
 */
public final class GitRunner {

    private static final Logger LOG = LoggerFactory.getLogger(GitRunner.class);

    /** A Git subcommand's name; anything else in its place is logged as {@code ?}. */
    private static final Pattern SUBCOMMAND = Pattern.compile("[a-z][a-z-]{0,31}");

    /** What Git prints when a login is missing, refused or would have needed a prompt. */
    private static final Pattern LOGIN =
            Pattern.compile(
                    "(?i)authentication failed|could not read (username|password)|unable to get password from user"
                            + "|permission denied \\(publickey|host key verification failed"
                            + "|invalid username or password|requested url returned error: 40[13]");

    /** What Git prints when a push is refused because the branch moved or is not as leased. */
    private static final Pattern REJECTED =
            Pattern.compile(
                    "\\[rejected\\]|\\(stale info\\)|\\(fetch first\\)|\\(non-fast-forward\\)");

    /**
     * User info in a URL, or before an scp-like host, up to its last {@code @} before the host,
     * whatever it holds short of a blank, so a slash, a quote or an {@code @} in a password goes
     * too; a query; and the token forms forges hand out, which Git may echo in an error.
     */
    private static final Pattern SECRET =
            Pattern.compile(
                    "(?<=://)\\S*@(?=[A-Za-z0-9\\[])"
                            + "|(?<![\\w.~/-])[\\w.~-]+:(?!//)\\S*@(?=[^\\s'\"@/]+:)"
                            + "|(?<=[\\w/.~-])\\?[^\\s'\"]*=[^\\s'\"]*"
                            + "|\\b(?:gh[pousr]_|github_pat_|glpat-)[A-Za-z0-9_-]+");

    /**
     * An authorization header with its scheme, if it names one, and every credential after it to
     * the line's end, since a Digest header carries its response in its last field.
     */
    private static final Pattern AUTHORIZATION =
            Pattern.compile(
                    "(?<header>(?i:(?:proxy-)?authorization):[ \\t]*)"
                            + "(?<scheme>\\S+)(?<credentials>[ \\t]+[^\\r\\n]*)?");

    /** A local path in quotes, taken whole, blanks and all: Git quotes the paths it names. */
    private static final Pattern QUOTED_PATH =
            Pattern.compile("(['\"])(?:file://|~/|/|[A-Za-z]:[\\\\/]|\\\\\\\\)[^'\"\\r\\n]*\\1");

    /**
     * A Windows path not in quotes, a drive's or a share's with either separator: a blank in an
     * account's name does not end it, so it runs to the line's end, or to the {@code ': } that
     * closes the path in Git's own messages.
     */
    private static final Pattern WINDOWS_PATH =
            Pattern.compile(
                    "(?<![\\w/.~:-])(?:[A-Za-z]:[\\\\/]|\\\\\\\\|//(?=[^\\s'\"/]+/))"
                            + "(?:(?!': )[^\\r\\n])*");

    /** A file URL, or a path from the home directory. */
    private static final Pattern HOME_PATH =
            Pattern.compile("file://[^\\s'\"]*|(?<![\\w/.~-])~/[^\\s'\"]+");

    /** An absolute Unix path not in quotes; never a URL's path after its host. */
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![\\w/.~-])/(?!/)[^\\s'\"]+");

    private static final int ERROR_BYTES = 16 * 1024;

    private final Duration limit;

    private final int outputBytes;

    /**
     * Makes a runner.
     *
     * @param limit how long one call may run before it is killed
     * @param outputBytes how much standard output one call may print
     */
    public GitRunner(final Duration limit, final int outputBytes) {
        this.limit = limit;
        this.outputBytes = outputBytes;
    }

    /**
     * Runs a call.
     *
     * @param call the call
     * @param directory where it runs, or {@code null} for the planner's own directory
     * @return its standard output
     * @throws GitFailure when it fails, runs too long or prints too much; its message is free of
     *     secrets
     */
    public byte[] run(final GitCall call, final @Nullable Path directory) {
        return run(call, directory, false, null);
    }

    /**
     * Runs a call fed an input, as {@code git cat-file --batch} reads its object ids.
     *
     * @param call the call
     * @param directory where it runs, or {@code null} for the planner's own directory
     * @param input what the call reads on its standard input, closed after it
     * @return its standard output
     * @throws GitFailure when it fails, runs too long, prints too much or does not take its whole
     *     input
     */
    public byte[] run(final GitCall call, final @Nullable Path directory, final byte[] input) {
        return run(call, directory, false, input.clone());
    }

    /**
     * Answers how much standard output one call may print.
     *
     * @return the bound in bytes
     */
    public int outputBytes() {
        return outputBytes;
    }

    /**
     * Runs a call that, as {@code git grep} does, exits with 1 and says nothing when it finds
     * nothing.
     *
     * @param call the call
     * @param directory where it runs, or {@code null} for the planner's own directory
     * @return its standard output, empty when it found nothing
     * @throws GitFailure when it fails otherwise, runs too long or prints too much
     */
    public byte[] find(final GitCall call, final @Nullable Path directory) {
        return run(call, directory, true, null);
    }

    private byte[] run(
            final GitCall call,
            final @Nullable Path directory,
            final boolean noneFoundIsEmpty,
            final byte @Nullable [] input) {
        final long start = System.nanoTime();
        try {
            final byte[] out = runOnce(call, directory, noneFoundIsEmpty, input);
            LOG.debug("git {} ended in {} ms", subcommand(call), millisSince(start));
            return out;
        } catch (final GitFailure e) {
            // Only fixed fields and the failure's scrubbed words: the call's line and environment
            // may hold a credential.
            LOG.debug(
                    "git {} failed in {} ms, {}: {}",
                    subcommand(call),
                    millisSince(start),
                    e.kind(),
                    LogText.of(e.getMessage()));
            throw e;
        }
    }

    /**
     * The subcommand of a call, after {@code git} and its options, such as {@code -c} and {@code
     * --git-dir=}.
     *
     * @param call the call
     * @return the subcommand's name, or {@code ?} when the place holds anything else
     */
    static String subcommand(final GitCall call) {
        final List<String> line = call.arguments();
        int i = 1;
        while (i < line.size() && line.get(i).startsWith("-")) {
            i += "-c".equals(line.get(i)) || "-C".equals(line.get(i)) ? 2 : 1;
        }
        return i < line.size() && SUBCOMMAND.matcher(line.get(i)).matches() ? line.get(i) : "?";
    }

    private static long millisSince(final long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private byte[] runOnce(
            final GitCall call,
            final @Nullable Path directory,
            final boolean noneFoundIsEmpty,
            final byte @Nullable [] input) {
        final ProcessBuilder builder = new ProcessBuilder(call.arguments());
        builder.environment().clear();
        builder.environment().putAll(call.environment());
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        final Process process;
        try {
            process = builder.start();
        } catch (final IOException e) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED,
                    "git could not be started: " + scrubbed(e.getMessage()));
        }
        try {
            // Nothing to read, and no file: /dev/null is C:\dev\null on Windows, which any account
            // may make.
            final FutureTask<Void> fed =
                    input == null ? null : write(process.getOutputStream(), input, process);
            if (fed == null) {
                process.getOutputStream().close();
            }
            final FutureTask<byte[]> output = read(process.getInputStream(), outputBytes, process);
            final FutureTask<byte[]> errors = read(process.getErrorStream(), ERROR_BYTES, process);
            if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new GitFailure(
                        GitFailure.Kind.UNREACHABLE,
                        "no answer within " + limit.toSeconds() + " s");
            }
            final byte[] out = finished(output, process);
            final String err = new String(finished(errors, process), StandardCharsets.UTF_8);
            if (noneFoundIsEmpty && process.exitValue() == 1 && out.length == 0 && err.isBlank()) {
                return out;
            }
            if (process.exitValue() != 0) {
                final GitFailure.Kind kind =
                        LOGIN.matcher(err).find()
                                ? GitFailure.Kind.LOGIN
                                : REJECTED.matcher(err).find()
                                        ? GitFailure.Kind.REJECTED
                                        : GitFailure.Kind.FAILED;
                throw new GitFailure(
                        kind, "git exited with " + process.exitValue() + ": " + scrubbed(err));
            }
            if (fed != null) {
                taken(fed, process);
            }
            return out;
        } catch (final IOException e) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED,
                    "git's input could not be closed: " + scrubbed(e.getMessage()));
        } catch (final InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new GitFailure(GitFailure.Kind.UNREACHABLE, "interrupted");
        } finally {
            kill(process);
        }
    }

    /**
     * Takes every secret form and every local path Git may echo out of a text.
     *
     * @param text the text, if any
     * @return the text with each secret replaced by {@code ***} and each local path by {@code
     *     <path>}
     */
    static String scrubbed(final @Nullable String text) {
        // Secrets first: a path taken out of a URL could take its '@' and leave its password.
        return UNIX_PATH.matcher(withoutLocalPaths(withoutSecrets(text))).replaceAll("<path>");
    }

    /**
     * Takes every local path out of a text but an unquoted absolute Unix one, which in a planning
     * index's fault is a pointer to a field, such as {@code /size}: a quoted path, a Windows path,
     * a file URL and a path from the home directory.
     *
     * @param text the text
     * @return the text with each such path replaced by {@code <path>}
     */
    static String withoutLocalPaths(final String text) {
        final String unquoted = QUOTED_PATH.matcher(text).replaceAll("$1<path>$1");
        final String noWindows = WINDOWS_PATH.matcher(unquoted).replaceAll("<path>");
        return HOME_PATH.matcher(noWindows).replaceAll("<path>");
    }

    /**
     * Takes every secret form Git may echo out of a text, and leaves its paths, for a text whose
     * slashes name fields rather than files.
     *
     * @param text the text, if any
     * @return the text, stripped, with each secret replaced by {@code ***}
     */
    static String withoutSecrets(final @Nullable String text) {
        if (text == null) {
            return "";
        }
        final String noSecrets = SECRET.matcher(text.strip()).replaceAll("***");
        return AUTHORIZATION
                .matcher(noSecrets)
                .replaceAll(
                        match ->
                                Matcher.quoteReplacement(
                                        match.group("header")
                                                + (match.group("credentials") == null
                                                        ? "***"
                                                        : match.group("scheme") + " ***")));
    }

    private static FutureTask<byte[]> read(
            final InputStream stream, final int bound, final Process process) {
        final FutureTask<byte[]> task =
                new FutureTask<>(
                        () -> {
                            final ByteArrayOutputStream kept = new ByteArrayOutputStream();
                            final byte[] buffer = new byte[8192];
                            int n;
                            while ((n = stream.read(buffer)) >= 0) {
                                if (kept.size() + n > bound) {
                                    // Stop it now: waiting for its end would let it print until the
                                    // time limit.
                                    kill(process);
                                    throw new IOException("more than " + bound + " bytes");
                                }
                                kept.write(buffer, 0, n);
                            }
                            return kept.toByteArray();
                        });
        Thread.ofVirtual().start(task);
        return task;
    }

    /** Writes the input on a thread of its own, so a call printing while it reads cannot block. */
    private static FutureTask<Void> write(
            final OutputStream stream, final byte[] input, final Process process) {
        final FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            try (OutputStream out = stream) {
                                out.write(input);
                            } catch (final IOException e) {
                                kill(process);
                                throw e;
                            }
                            return null;
                        });
        Thread.ofVirtual().start(task);
        return task;
    }

    /** A call that ended without taking its whole input answered something else than asked. */
    private void taken(final Future<Void> fed, final Process process) throws InterruptedException {
        try {
            fed.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final ExecutionException e) {
            kill(process);
            throw new GitFailure(
                    GitFailure.Kind.FAILED,
                    "git did not take its whole input: "
                            + scrubbed(e.getCause() == null ? null : e.getCause().getMessage()));
        } catch (final TimeoutException e) {
            kill(process);
            throw new GitFailure(
                    GitFailure.Kind.UNREACHABLE, "no answer within " + limit.toSeconds() + " s");
        }
    }

    private byte[] finished(final Future<byte[]> stream, final Process process)
            throws InterruptedException {
        try {
            return stream.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final ExecutionException e) {
            kill(process);
            final Throwable cause = e.getCause();
            if (cause instanceof IOException io
                    && io.getMessage() != null
                    && io.getMessage().startsWith("more than")) {
                throw new GitFailure(GitFailure.Kind.FAILED, "git printed " + io.getMessage());
            }
            throw new UncheckedIOException(new IOException("reading git's output failed", cause));
        } catch (final TimeoutException e) {
            kill(process);
            throw new GitFailure(
                    GitFailure.Kind.UNREACHABLE, "no answer within " + limit.toSeconds() + " s");
        }
    }

    private static void kill(final Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}

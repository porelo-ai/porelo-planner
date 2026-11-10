package org.fuin.porelo.planner.store.git;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Runs one {@link GitCall} with no input, a hard time limit and bounded output, and tells a refused
 * login from any other failure.
 */
public final class GitRunner {

    /** What Git prints when a login is missing, refused or would have needed a prompt. */
    private static final Pattern LOGIN =
            Pattern.compile(
                    "(?i)authentication failed|could not read (username|password)|unable to get password from user"
                            + "|permission denied \\(publickey|host key verification failed"
                            + "|invalid username or password|requested url returned error: 40[13]");

    /** User info in a URL, and the token forms forges hand out, which Git may echo in an error. */
    private static final Pattern SECRET =
            Pattern.compile(
                    "(?<=://)[^/@\\s']+@|\\b(gh[pousr]_|github_pat_|glpat-)[A-Za-z0-9_-]+"
                            + "|(?i)(authorization:\\s*\\S+\\s+)\\S+");

    private static final File NO_INPUT = new File("/dev/null");

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
        final ProcessBuilder builder = new ProcessBuilder(call.arguments());
        builder.environment().clear();
        builder.environment().putAll(call.environment());
        builder.redirectInput(NO_INPUT);
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
            if (process.exitValue() != 0) {
                final GitFailure.Kind kind =
                        LOGIN.matcher(err).find() ? GitFailure.Kind.LOGIN : GitFailure.Kind.FAILED;
                throw new GitFailure(
                        kind, "git exited with " + process.exitValue() + ": " + scrubbed(err));
            }
            return out;
        } catch (final InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new GitFailure(GitFailure.Kind.UNREACHABLE, "interrupted");
        } finally {
            kill(process);
        }
    }

    /**
     * Takes every secret form Git may echo out of a text.
     *
     * @param text the text, if any
     * @return the text with each secret replaced by {@code ***}
     */
    static String scrubbed(final @Nullable String text) {
        if (text == null) {
            return "";
        }
        return SECRET.matcher(text.strip())
                .replaceAll(
                        match ->
                                match.group(2) != null
                                        ? Matcher.quoteReplacement(match.group(2)) + "***"
                                        : "***");
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

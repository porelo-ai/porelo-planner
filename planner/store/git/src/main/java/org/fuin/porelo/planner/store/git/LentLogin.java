package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.planner.store.StoreException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The login Porelo's vault lends to Git for a project's https centrals, through Porelo's own
 * credential helper, {@code <porelo> vault credential --for <address>}, named by {@code
 * PORELO_EXECUTABLE}. Without the variable the person's own Git configuration is the only helper; a
 * login lent makes Porelo's helper the only one Git asks or tells about the address, and the token
 * passes only through a pipe: Git's to the helper, or the planner's own for a forge's API.
 */
final class LentLogin {

    /** The variable the window sets to Porelo's executable. */
    static final String VARIABLE = "PORELO_EXECUTABLE";

    /** Lends nothing: the variable is not set. */
    static final LentLogin NONE = new LentLogin(null, null, Map.of());

    /** How long Porelo's helper may take to answer for a token. */
    static final Duration LIMIT = Duration.ofSeconds(15);

    /** The most the helper may write for one token; a credential answer is a few lines. */
    static final int BOUND = 64 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(LentLogin.class);

    private static final boolean WINDOWS =
            !FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /** The checked executable, or null when nothing is lent. */
    private final @Nullable String executable;

    /** Why the executable was refused, or null when it was not. */
    private final @Nullable String refusal;

    /** The planner's environment, the one the helper runs in when asked for a token. */
    private final Map<String, String> environment;

    private LentLogin(
            final @Nullable String executable,
            final @Nullable String refusal,
            final Map<String, String> environment) {
        this.executable = executable;
        this.refusal = refusal;
        this.environment = environment;
    }

    /**
     * The login the planner lends: Porelo's, through the executable {@code PORELO_EXECUTABLE} names
     * once the platform's own check passes it.
     *
     * @param environment the planner's environment
     * @return the login lent, none without the variable; a refused one when the check refuses
     */
    static LentLogin of(final Map<String, String> environment) {
        return of(environment, PoreloExecutable.of(environment, WINDOWS));
    }

    /**
     * Reads the variable and checks the executable it names with the check given.
     *
     * @param environment the planner's environment
     * @param check the check of the executable
     * @return the login lent, none without the variable; a refused one when the check refuses or
     *     cannot run
     */
    static LentLogin of(final Map<String, String> environment, final PoreloExecutable check) {
        final String value = variable(environment);
        if (value == null) {
            return NONE;
        }
        try {
            final String path = check.checked(value);
            quoted(path);
            return new LentLogin(path, null, new HashMap<>(environment));
        } catch (final IllegalStateException | IllegalArgumentException | UncheckedIOException e) {
            final String why = GitRunner.scrubbed(e.getMessage());
            LOG.warn("Porelo's executable is refused, no login is lent: {}", LogText.of(why));
            return new LentLogin(null, why, Map.of());
        }
    }

    /**
     * The configuration a call to a central carries ahead of its own arguments.
     *
     * @param url the central the call reaches
     * @return none for an ssh central or without the variable; for an https central, the entry
     *     clearing every helper collected before, Porelo's helper, the path in its request, and no
     *     redirect
     * @throws GitFailure when the executable was refused, so no other helper lends a login instead
     */
    List<String> config(final CloneUrl url) {
        final String address = url.value();
        if ((executable == null && refusal == null) || !address.startsWith("https://")) {
            return List.of();
        }
        if (executable == null) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED,
                    "Porelo's executable is refused, so no login is lent: " + refusal);
        }
        final String key = "credential." + address;
        return List.of(
                "-c",
                key + ".helper=",
                "-c",
                key + ".helper=" + helper(executable, address),
                "-c",
                key + ".useHttpPath=true",
                "-c",
                "http.followRedirects=false");
    }

    /**
     * Asks Porelo's helper for the token of an https address, as Git would ask it, for a forge's
     * API: the executable run directly, the request on its input, the answer bounded in size and
     * time. The token is never logged, never put in a message and never kept beyond the call.
     *
     * @param address the https address the token is for
     * @param name the repository's name a refusal reports
     * @param limit how long the helper may take
     * @return the token
     * @throws StoreException.CredentialRefused when nothing is lent, the executable was refused,
     *     the address is not https, or the helper fails, takes too long, writes too much or answers
     *     no password
     */
    String token(final String address, final String name, final Duration limit) {
        final URI uri = https(address, name);
        if (executable == null) {
            throw refused(
                    name,
                    refusal == null
                            ? VARIABLE + " is not set"
                            : "Porelo's executable is refused: " + refusal);
        }
        final String path =
                uri.getRawPath().startsWith("/") ? uri.getRawPath().substring(1) : uri.getRawPath();
        final ProcessBuilder builder =
                new ProcessBuilder(executable, "vault", "credential", "--for", address, "get");
        builder.environment().clear();
        builder.environment().putAll(environment);
        final Process process;
        try {
            process = builder.start();
        } catch (final IOException e) {
            throw refused(name, "Porelo's executable cannot be run");
        }
        try {
            // Each output on a thread of its own, so a full pipe never blocks the helper.
            final FutureTask<byte[]> out = bounded(process.getInputStream());
            Thread.ofVirtual().start(discarded(process.getErrorStream()));
            try (OutputStream in = process.getOutputStream()) {
                in.write(
                        ("protocol=https\nhost="
                                        + uri.getRawAuthority()
                                        + "\npath="
                                        + path
                                        + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
            } catch (final IOException e) {
                throw refused(name, "Porelo's helper did not take the request");
            }
            final long deadline = System.nanoTime() + limit.toNanos();
            final byte[] answer;
            try {
                answer = out.get(limit.toNanos(), TimeUnit.NANOSECONDS);
            } catch (final ExecutionException e) {
                throw refused(name, "Porelo's helper's answer cannot be read");
            } catch (final TimeoutException e) {
                throw refused(name, "Porelo's helper did not answer in time");
            }
            if (answer.length > BOUND) {
                throw refused(name, "Porelo's helper wrote more than " + BOUND + " bytes");
            }
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw refused(name, "Porelo's helper did not end in time");
            }
            if (process.exitValue() != 0) {
                throw refused(name, "Porelo's helper exited " + process.exitValue());
            }
            return password(answer, name);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw refused(name, "the wait for Porelo's helper was interrupted");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /** The address, refused unless it is a plain https one: no login, query or fragment in it. */
    private static URI https(final String address, final String name) {
        final URI uri;
        try {
            uri = new URI(address);
        } catch (final URISyntaxException e) {
            throw refused(name, "the address is not a URI");
        }
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw refused(name, "the address is not a plain https address");
        }
        return uri;
    }

    /** The value of the answer's {@code password=} line, refused when there is none. */
    private static String password(final byte[] answer, final String name) {
        for (final String line : new String(answer, StandardCharsets.UTF_8).split("\n", -1)) {
            if (line.startsWith("password=")) {
                final String value = line.substring("password=".length()).strip();
                if (value.isEmpty()) {
                    break;
                }
                return value;
            }
        }
        throw refused(name, "Porelo's helper answered no password");
    }

    /** Logs why, which never holds the token, and answers the refusal naming the repository. */
    private static StoreException.CredentialRefused refused(final String name, final String why) {
        LOG.warn("No login of Porelo's for {}: {}", LogText.of(name), LogText.of(why));
        return new StoreException.CredentialRefused(name);
    }

    /** Reads at most one byte more than the bound, so more is seen without reading it all. */
    private static FutureTask<byte[]> bounded(final InputStream stream) {
        final FutureTask<byte[]> task =
                new FutureTask<>(
                        () -> {
                            try (stream) {
                                return stream.readNBytes(BOUND + 1);
                            }
                        });
        Thread.ofVirtual().start(task);
        return task;
    }

    /** Drains the helper's error output unread: it may name the secret. */
    private static Runnable discarded(final InputStream stream) {
        return () -> {
            try (stream) {
                stream.transferTo(OutputStream.nullOutputStream());
            } catch (final IOException e) {
                // Nothing to report: the output is never read.
            }
        };
    }

    /**
     * The helper's line, run by the shell Git runs helpers in, Git for Windows' too.
     *
     * @param executable the checked executable
     * @param address the central, as {@link CloneUrl} checked it
     * @return the line, each of the two a single-quoted argument
     * @throws IllegalArgumentException when either holds a {@code '}, which would end its quoting
     */
    static String helper(final String executable, final String address) {
        return "!" + quoted(executable) + " vault credential --for " + quoted(address);
    }

    private static String quoted(final String value) {
        if (value.indexOf('\'') >= 0) {
            throw new IllegalArgumentException(
                    "a ' would end the quoting of the helper's arguments");
        }
        return "'" + value + "'";
    }

    /** The variable's value; Windows reads a variable's name in any case. */
    private static @Nullable String variable(final Map<String, String> environment) {
        final String value = environment.get(VARIABLE);
        if (value != null || !WINDOWS) {
            return value;
        }
        for (final Map.Entry<String, String> variable : environment.entrySet()) {
            if (variable.getKey().equalsIgnoreCase(VARIABLE)) {
                return variable.getValue();
            }
        }
        return null;
    }
}

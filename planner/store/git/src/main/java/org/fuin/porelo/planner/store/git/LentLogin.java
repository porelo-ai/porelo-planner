package org.fuin.porelo.planner.store.git;

import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The login Porelo's vault lends to Git for a project's https centrals, through Porelo's own
 * credential helper, {@code <porelo> vault credential --for <address>}, named by {@code
 * PORELO_EXECUTABLE}. Without the variable nothing is lent and the person's own Git configuration
 * is the only helper; with it, Porelo's helper is the only one Git asks or tells about the address,
 * and the token passes only through Git's pipe to the helper.
 */
final class LentLogin {

    /** The variable the window sets to Porelo's executable. */
    static final String VARIABLE = "PORELO_EXECUTABLE";

    /** Lends nothing: the variable is not set. */
    static final LentLogin NONE = new LentLogin(null, null);

    private static final Logger LOG = LoggerFactory.getLogger(LentLogin.class);

    private static final boolean WINDOWS =
            !FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /** The checked executable, or null when nothing is lent. */
    private final @Nullable String executable;

    /** Why the executable was refused, or null when it was not. */
    private final @Nullable String refusal;

    private LentLogin(final @Nullable String executable, final @Nullable String refusal) {
        this.executable = executable;
        this.refusal = refusal;
    }

    /**
     * The login the planner lends: none yet, so the person's own Git configuration applies as it
     * did.
     *
     * <p>Porelo has no command yet that answers Git for a login, so a helper naming it would fail
     * every read; lending starts with that command, through {@link #of(Map, PoreloExecutable)}.
     *
     * @param environment the planner's environment
     * @return no login
     */
    static LentLogin of(final Map<String, String> environment) {
        return NONE;
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
            return new LentLogin(path, null);
        } catch (final IllegalStateException | IllegalArgumentException | UncheckedIOException e) {
            final String why = GitRunner.scrubbed(e.getMessage());
            LOG.warn("Porelo's executable is refused, no login is lent: {}", LogText.of(why));
            return new LentLogin(null, why);
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

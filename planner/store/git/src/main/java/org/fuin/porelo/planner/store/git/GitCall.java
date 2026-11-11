package org.fuin.porelo.planner.store.git;

import java.nio.file.FileSystems;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The command line and environment of one {@code git} call that can never prompt, run a hook, a
 * filter or a protocol other than HTTPS and SSH, whatever the caller's environment holds.
 *
 * @param arguments the command line, {@code git} first
 * @param environment the whole environment the call runs with
 */
public record GitCall(List<String> arguments, Map<String, String> environment) {

    /**
     * Whether this is Windows, where Git takes {@code /dev/null} as a path on the current drive.
     */
    private static final boolean WINDOWS =
            !FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /** The configuration every call gets on Linux, ahead of its own arguments. */
    static final List<String> SAFE_CONFIG = safeConfig("/dev/null");

    private static List<String> safeConfig(final String hooksPath) {
        return List.of(
                "-c", "core.hooksPath=" + hooksPath,
                "-c", "core.fsmonitor=false",
                "-c", "protocol.allow=never",
                "-c", "protocol.https.allow=always",
                "-c", "protocol.ssh.allow=always",
                "-c", "credential.interactive=never");
    }

    /**
     * The Git variables of the person's environment that are kept: which configuration files apply.
     */
    private static final List<String> KEPT =
            List.of("GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM", "GIT_CONFIG_NOSYSTEM");

    /** Copies the lists. */
    public GitCall {
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
    }

    /**
     * Makes a call.
     *
     * @param inherited the planner's own environment
     * @param connectTimeout how long ssh waits for a host
     * @param arguments the call's arguments after {@code git} and the safe configuration
     * @return the call
     */
    public static GitCall of(
            final Map<String, String> inherited,
            final Duration connectTimeout,
            final List<String> arguments) {
        return of(inherited, connectTimeout, arguments, WINDOWS);
    }

    /**
     * Makes a call for the platform given.
     *
     * @param inherited the planner's own environment
     * @param connectTimeout how long ssh waits for a host
     * @param arguments the call's arguments after {@code git} and the safe configuration
     * @param windows whether the call runs on Windows
     * @return the call
     */
    static GitCall of(
            final Map<String, String> inherited,
            final Duration connectTimeout,
            final List<String> arguments,
            final boolean windows) {
        final List<String> line = new ArrayList<>();
        line.add("git");
        line.addAll(windows ? safeConfig(nowhere(inherited)) : SAFE_CONFIG);
        line.addAll(arguments);
        return new GitCall(line, environment(inherited, connectTimeout, windows));
    }

    /**
     * A name in the planner's own directory that is never made: Git finds no hook there and cannot
     * run it as a program, and only this account could put anything there. Git for Windows takes
     * {@code /dev/null} and {@code /bin/false} as paths on the current drive, where any account may
     * make a folder.
     */
    private static String nowhere(final Map<String, String> inherited) {
        return (WindowsPrivateFiles.drivePath(inherited, "LOCALAPPDATA") + "\\porelo-planner\\none")
                .replace('\\', '/');
    }

    /**
     * Makes a call that works on an index file of its own, as a save builds its commit.
     *
     * @param inherited the planner's own environment
     * @param connectTimeout how long ssh waits for a host
     * @param index the index file, never the clone's own
     * @param arguments the call's arguments after {@code git} and the safe configuration
     * @return the call
     */
    public static GitCall withIndex(
            final Map<String, String> inherited,
            final Duration connectTimeout,
            final java.nio.file.Path index,
            final List<String> arguments) {
        final GitCall call = of(inherited, connectTimeout, arguments, WINDOWS);
        final Map<String, String> variables = new TreeMap<>(call.environment());
        // Set after the person's GIT_ variables are gone, so only this one names an index.
        variables.put("GIT_INDEX_FILE", index.toString());
        return new GitCall(call.arguments(), variables);
    }

    private static Map<String, String> environment(
            final Map<String, String> inherited,
            final Duration connectTimeout,
            final boolean windows) {
        final Map<String, String> variables = new TreeMap<>(inherited);
        // Any other GIT_ variable can point Git at another repository, program or configuration;
        // Windows reads a variable's name in any case.
        variables
                .keySet()
                .removeIf(
                        name -> {
                            final String upper = name.toUpperCase(Locale.ROOT);
                            return upper.startsWith("GIT_") && !KEPT.contains(name)
                                    || upper.equals("SSH_ASKPASS")
                                    || upper.equals("SSH_ASKPASS_REQUIRE")
                                    || upper.equals("GCM_INTERACTIVE")
                                    || upper.equals("LC_ALL")
                                    || upper.equals("LANGUAGE");
                        });
        variables.put("GIT_TERMINAL_PROMPT", "0");
        // Set, not unset: an unset GIT_ASKPASS lets core.askPass from the person's configuration
        // open a dialog.
        variables.put("GIT_ASKPASS", windows ? nowhere(inherited) : "/bin/false");
        variables.put("SSH_ASKPASS_REQUIRE", "never");
        variables.put("GCM_INTERACTIVE", "never");
        variables.put("GIT_EDITOR", ":");
        variables.put(
                "GIT_SSH_COMMAND",
                "ssh -o BatchMode=yes -o ConnectTimeout="
                        + Math.max(1, connectTimeout.toSeconds()));
        // Git's messages in English, so a refused login is told from an unreachable host.
        variables.put("LC_ALL", "C");
        variables.put("LANGUAGE", "C");
        return variables;
    }
}

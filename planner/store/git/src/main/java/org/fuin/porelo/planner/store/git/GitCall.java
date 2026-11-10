package org.fuin.porelo.planner.store.git;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    /** The configuration every call gets, ahead of its own arguments. */
    static final List<String> SAFE_CONFIG =
            List.of(
                    "-c", "core.hooksPath=/dev/null",
                    "-c", "core.fsmonitor=false",
                    "-c", "protocol.allow=never",
                    "-c", "protocol.https.allow=always",
                    "-c", "protocol.ssh.allow=always",
                    "-c", "credential.interactive=never");

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
        final List<String> line = new ArrayList<>();
        line.add("git");
        line.addAll(SAFE_CONFIG);
        line.addAll(arguments);
        return new GitCall(line, environment(inherited, connectTimeout));
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
        final GitCall call = of(inherited, connectTimeout, arguments);
        final Map<String, String> variables = new TreeMap<>(call.environment());
        // Set after the person's GIT_ variables are gone, so only this one names an index.
        variables.put("GIT_INDEX_FILE", index.toString());
        return new GitCall(call.arguments(), variables);
    }

    private static Map<String, String> environment(
            final Map<String, String> inherited, final Duration connectTimeout) {
        final Map<String, String> variables = new TreeMap<>(inherited);
        // Any other GIT_ variable can point Git at another repository, program or configuration.
        variables.keySet().removeIf(name -> name.startsWith("GIT_") && !KEPT.contains(name));
        variables.remove("SSH_ASKPASS");
        variables.put("GIT_TERMINAL_PROMPT", "0");
        // Set, not unset: an unset GIT_ASKPASS lets core.askPass from the person's configuration
        // open a dialog.
        variables.put("GIT_ASKPASS", "/bin/false");
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

package org.fuin.porelo.planner.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.git.stories.CommitRules;
import org.fuin.porelo.wire.LimitFactors;

/**
 * The repository's commit check, with the same lines and exit codes as the build tools give. It
 * starts no server and reads no planner configuration.
 *
 * <p>Exit 0 is an answer of yes, {@value #REFUSED} a refusal with each fault said, and {@value
 * #UNANSWERED} no answer: something could not be read or written, or the arguments are no command.
 */
final class WorkCommands {

    /** The exit code of a refusal: each fault is said. */
    static final int REFUSED = 1;

    /** The exit code when there is no answer: an input or an output failed, or no command. */
    static final int UNANSWERED = 2;

    private static final String COMMITS = "check-commits RANGE [--outside] | check-commits --event";

    /** These names must still exit before Spring, so an old script cannot start a server. */
    private static final Set<String> REMOVED = Set.of("issues", "stories", "stage", "check-pushes");

    /** How long one git call may run, before the factor {@code machines}. */
    private static final java.time.Duration GIT_LIMIT = java.time.Duration.ofSeconds(60);

    private final PrintStream out;

    private final PrintStream err;

    /** The factors the commands' time limits follow. */
    private final LimitFactors limits;

    private WorkCommands(final PrintStream out, final PrintStream err, final LimitFactors limits) {
        this.out = out;
        this.err = err;
        this.limits = limits;
    }

    /**
     * Whether the arguments name a work tool rather than the server.
     *
     * @param args the command line
     * @return whether the first argument names the commit check or a removed command
     */
    static boolean named(final String... args) {
        return args.length > 0 && (args[0].equals("check-commits") || REMOVED.contains(args[0]));
    }

    /**
     * Runs a work tool with every limit at its default.
     *
     * @param args the command line, its first argument one of the work commands' names
     * @param out where an answer goes
     * @param err where each fault and every failure goes
     * @return the exit code: 0, {@value #REFUSED} or {@value #UNANSWERED}
     */
    static int run(final List<String> args, final PrintStream out, final PrintStream err) {
        return run(args, out, err, LimitFactors.DEFAULT);
    }

    /**
     * Runs a work tool.
     *
     * @param args the command line after the limits line, its first argument one of the work
     *     commands' names
     * @param out where an answer goes
     * @param err where each fault and every failure goes
     * @param limits the factors the commands' time limits follow
     * @return the exit code: 0, {@value #REFUSED} or {@value #UNANSWERED}
     */
    static int run(
            final List<String> args,
            final PrintStream out,
            final PrintStream err,
            final LimitFactors limits) {
        final WorkCommands commands = new WorkCommands(out, err, limits);
        try {
            if (REMOVED.contains(args.getFirst())) {
                err.println(args.getFirst() + " is no longer part of the planner");
                return UNANSWERED;
            }
            final List<String> rest = args.subList(1, args.size());
            return args.getFirst().equals("check-commits")
                    ? commands.checkCommits(rest)
                    : commands.usage(COMMITS);
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError ex) {
            // The JVM's own exit 1 would read as a refusal of the files checked.
            err.println(
                    "internal error - this is not an answer to the question asked: " + said(ex));
            return UNANSWERED;
        }
    }

    /**
     * Checks the commits of a range in the repository the command runs in, at its root, or those
     * the workflow's event names; each line said with its control characters escaped, since it
     * quotes what a commit says of itself.
     */
    private int checkCommits(final List<String> rest) {
        String range = null;
        int positional = 0;
        boolean outside = false;
        boolean event = false;
        for (final String arg : rest) {
            if (arg.equals("--outside")) outside = true;
            else if (arg.equals("--event")) event = true;
            else if (arg.startsWith("--")) return usage(COMMITS);
            else {
                positional++;
                range = arg;
            }
        }
        final Path here = Path.of(".");
        if (event) {
            if (positional != 0 || outside) return usage(COMMITS);
            final Map<String, String> environment = System.getenv();
            return said((sayer, faulter) -> checkEvent(environment, here, sayer, faulter, limits));
        }
        if (positional != 1 || range == null) return usage(COMMITS);
        final String checked = range;
        final boolean fromOutside = outside;
        return said(
                (sayer, faulter) ->
                        new CommitRules(sayer, faulter, CommitRules.Git.in(here, Map.of(), limits))
                                .check(checked, fromOutside));
    }

    /**
     * Checks the commits a workflow's event names, the range and whether they come from outside
     * decided from the event alone: the runner's variables and the event file {@code
     * GITHUB_EVENT_PATH}.
     *
     * @param environment the process's environment
     * @param here the repository's root
     * @param out where the result goes
     * @param err where a refusal goes
     * @param limits the factors the git calls' limits follow
     * @return as a range's check; {@value #REFUSED} when the event names no range, {@value
     *     #UNANSWERED} when it cannot be read
     */
    private static int checkEvent(
            final Map<String, String> environment,
            final Path here,
            final PrintStream out,
            final PrintStream err,
            final LimitFactors limits) {
        final String path = environment.get("GITHUB_EVENT_PATH");
        if (path == null || path.isBlank()) {
            err.println(
                    "GITHUB_EVENT_PATH is not set; run check-commits --event in a workflow's step");
            return UNANSWERED;
        }
        final CommitRange decided;
        try {
            decided =
                    CommitRange.decide(
                            GitHubEvent.read(
                                    environment,
                                    Files.readString(Path.of(path), StandardCharsets.UTF_8)),
                            hash -> holds(here, hash, limits.machines(GIT_LIMIT)));
        } catch (IOException ex) {
            err.println("could not read the event: " + ex.getMessage());
            return UNANSWERED;
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            return REFUSED;
        }
        out.println(
                "NOTE  the event's commits: "
                        + decided.range()
                        + (decided.outside() ? ", from outside" : ", from inside"));
        return new CommitRules(out, err, CommitRules.Git.in(here, Map.of(), limits))
                .check(decided.range(), decided.outside());
    }

    /**
     * Whether the checkout holds a commit, as git finds it; false when git cannot tell.
     *
     * @param here the repository's root
     * @param hash a full commit hash, never an option
     * @param limit how long git may take
     * @return true when git finds that commit
     */
    private static boolean holds(
            final Path here, final String hash, final java.time.Duration limit) {
        final ProcessBuilder builder =
                new ProcessBuilder(
                                "git", "-C", here.toString(), "cat-file", "-e", hash + "^{commit}")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("LC_ALL", "C");
        try {
            final Process git = builder.start();
            if (!git.waitFor(limit.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                git.destroyForcibly();
                return false;
            }
            return git.exitValue() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A command that says its lines to two streams and answers an exit code. */
    @FunctionalInterface
    private interface Saying {
        int run(PrintStream out, PrintStream err);
    }

    /** Runs a command on captured streams, then says each of its lines escaped. */
    private int said(final Saying command) {
        final ByteArrayOutputStream said = new ByteArrayOutputStream();
        final ByteArrayOutputStream faults = new ByteArrayOutputStream();
        final int code;
        try (PrintStream sayer = new PrintStream(said, true, StandardCharsets.UTF_8);
                PrintStream faulter = new PrintStream(faults, true, StandardCharsets.UTF_8)) {
            code = command.run(sayer, faulter);
        }
        escaped(said, out);
        escaped(faults, err);
        return code;
    }

    private static void escaped(final ByteArrayOutputStream lines, final PrintStream to) {
        final String text = lines.toString(StandardCharsets.UTF_8);
        if (text.isEmpty()) return;
        for (final String line : text.split("\n", -1)) {
            if (!line.isEmpty()) to.println(shown(line));
        }
    }

    private int usage(final String expected) {
        err.println("usage: " + expected);
        return UNANSWERED;
    }

    private static String said(final Throwable failure) {
        final String message = failure.getMessage();
        return shown(message == null ? failure.getClass().getSimpleName() : message);
    }

    /**
     * A text from a repository as it may reach a terminal: every control, format and line or
     * paragraph separator character written as its escape, so no file can move the cursor, hide a
     * line or turn the text around.
     *
     * @param text the text
     * @return the text, each such character as {@code \\uXXXX}
     */
    static String shown(final String text) {
        final StringBuilder shown = new StringBuilder(text.length());
        text.codePoints()
                .forEach(
                        point -> {
                            final int type = Character.getType(point);
                            if (Character.isISOControl(point)
                                    || type == Character.FORMAT
                                    || type == Character.LINE_SEPARATOR
                                    || type == Character.PARAGRAPH_SEPARATOR) {
                                for (final char unit : Character.toChars(point))
                                    shown.append(String.format("\\u%04x", (int) unit));
                            } else shown.appendCodePoint(point);
                        });
        return shown.toString();
    }
}

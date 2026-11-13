package org.fuin.porelo.planner.cleanup;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.fuin.porelo.planner.store.git.stories.Project;

/**
 * The clean-up's two commands, each writing its answer to a file of its own only once the whole
 * answer stands, so a refusal leaves no partial answer for the workflow to act on.
 *
 * <ul>
 *   <li>{@code repositories <project.yml> <out>}: one line {@code <name> <https clone URL>} per
 *       repository the project file names, its own included.
 *   <li>{@code choose <listing.json> <project.yml> <checkouts> <out>}: one line {@code
 *       porelo-files/porelo-planner/<version>/} per snapshot build to delete.
 * </ul>
 */
public final class Main {

    /** The exit code of a refusal: a listing could not be read whole. */
    public static final int UNREADABLE = 1;

    /** The exit code of a wrong command line. */
    public static final int USAGE = 2;

    private final Clock clock;

    private final PrintStream out;

    private final PrintStream err;

    /**
     * Constructor.
     *
     * @param clock the time a choice is made at
     * @param out where what was chosen is logged
     * @param err where a refusal goes
     */
    public Main(final Clock clock, final PrintStream out, final PrintStream err) {
        this.clock = clock;
        this.out = out;
        this.err = err;
    }

    /**
     * Runs a command and exits with its code.
     *
     * @param args the command and its arguments
     */
    public static void main(final String[] args) {
        System.exit(new Main(Clock.systemUTC(), System.out, System.err).run(args));
    }

    /**
     * Runs a command.
     *
     * @param args the command and its arguments
     * @return 0, {@link #UNREADABLE} or {@link #USAGE}
     */
    public int run(final String... args) {
        try {
            if (args.length == 3 && args[0].equals("repositories")) {
                write(Path.of(args[2]), repositories(project(Path.of(args[1]))));
                return 0;
            }
            if (args.length == 5 && args[0].equals("choose")) {
                write(
                        Path.of(args[4]),
                        choose(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])));
                return 0;
            }
        } catch (final Unreadable e) {
            err.println("cleanup: nothing is chosen: " + e.getMessage());
            return UNREADABLE;
        }
        err.println(
                "usage: repositories <project.yml> <out>"
                        + " | choose <listing.json> <project.yml> <checkouts> <out>");
        return USAGE;
    }

    private List<String> repositories(final Project project) throws Unreadable {
        final List<String> lines = new ArrayList<>();
        for (final String name : project.repositories().keySet()) {
            final String web;
            try {
                web = project.web(name);
            } catch (final IllegalArgumentException e) {
                throw new Unreadable("project.yml names repository " + name, e);
            }
            if (!web.startsWith("https://")) {
                throw new Unreadable("repository " + name + " is at " + web + ", not https://");
            }
            lines.add(name + " " + web + ".git");
        }
        return lines;
    }

    private List<String> choose(final Path listing, final Path projectFile, final Path checkouts)
            throws Unreadable {
        final List<Build> files = Listing.read(text(listing));
        final Set<String> pins = Pins.read(checkouts, project(projectFile).repositories().keySet());
        final List<String> chosen = Choice.of(files, pins, clock.instant());
        final List<String> lines = new ArrayList<>();
        for (final String version : chosen) {
            lines.add(Listing.PREFIX + "/" + version + "/");
        }
        out.println(
                "cleanup: "
                        + files.size()
                        + " file(s) listed, "
                        + pins.size()
                        + " snapshot version(s) pinned, "
                        + chosen.size()
                        + " build(s) chosen");
        lines.forEach(line -> out.println("  chosen  " + line));
        return lines;
    }

    private static Project project(final Path file) throws Unreadable {
        try {
            return Project.read(text(file), Set.of());
        } catch (final IllegalArgumentException e) {
            throw new Unreadable(file + " is no project file", e);
        }
    }

    private static String text(final Path file) throws Unreadable {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new Unreadable(file + " could not be read", e);
        }
    }

    // A file left by an earlier run is refused rather than replaced, so it is never acted on.
    private static void write(final Path file, final List<String> lines) throws Unreadable {
        final StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append('\n'));
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (final IOException e) {
            throw new Unreadable(file + " could not be written", e);
        }
    }
}

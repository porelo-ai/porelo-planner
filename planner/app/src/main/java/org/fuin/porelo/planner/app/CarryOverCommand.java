package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.git.CheckoutProject;
import org.fuin.porelo.planner.store.git.CloneUrl;
import org.fuin.porelo.planner.store.git.ForgeTokens;
import org.fuin.porelo.planner.store.git.PrivateFiles;
import org.fuin.porelo.planner.store.git.ProjectFile;
import org.fuin.porelo.planner.store.git.stories.IssueHead;
import org.fuin.porelo.planner.store.git.stories.StageRecords;
import org.fuin.porelo.planner.store.github.CarryOver;
import org.fuin.porelo.planner.store.github.CarryOverCalls;
import org.fuin.porelo.planner.store.github.CarryOverMap;
import org.fuin.porelo.planner.store.github.CarryOverPace;
import org.fuin.porelo.planner.store.github.CarryOverPlan;
import org.fuin.porelo.planner.store.github.ProjectConfig;
import org.fuin.porelo.planner.store.github.ProjectQueries;
import org.fuin.porelo.planner.store.github.ProjectRef;
import org.fuin.porelo.planner.store.github.ProjectWrites;
import org.fuin.porelo.planner.store.github.Scrubbed;
import org.jspecify.annotations.Nullable;

/**
 * Carries a Markdown project's open stories and issues over to a GitHub project. The dry run reads
 * the project from its checkouts, needs no token, writes nothing and says one line per item it
 * would write. The real run makes every item on GitHub with the person's own access, keeps its map
 * of old id to new reference in a file of its own under the planner's state while it copies, goes
 * on from that file when run again, and once every item is carried writes {@code tracker} and the
 * velocity seed into {@code project.yml} of the own checkout, without committing it. A project or a
 * file it cannot read stops it before anything is written. No secret is said.
 */
final class CarryOverCommand {

    /** The command's form. */
    static final String USAGE =
            "carry-over --project <dir> --to <owner>/<number> [--dry-run] [--seed <csv>]"
                    + " [--state <file>] [--per-minute <n>]\n"
                    + "  --seed   rows repository,size,minutes under that header; written into"
                    + " project.yml as velocity.seed\n"
                    + "  --state  the run's map; by default carry-over/<owner>-<number>.json under"
                    + " the planner's state directory ($XDG_STATE_HOME/porelo-planner, or"
                    + " %LOCALAPPDATA%\\porelo-planner on Windows)\n"
                    + "  --per-minute  the issues and comments made a minute, from 1 to 60 (60);"
                    + " at most 450 in any hour either way";

    /** GitHub's web address, which every repository of a carried project stands under. */
    private static final String GITHUB = "https://github.com/";

    /** GitHub's API, to which the clients add each call's path. */
    private static final URI GITHUB_API = URI.create("https://api.github.com");

    /** The forge the token is asked for. */
    private static final URI FORGE = URI.create("https://github.com");

    /** The calls a real run makes and the time it tells; the forge client's, or a test's. */
    record Remote(
            ProjectQueries queries,
            ProjectWrites writes,
            CarryOverCalls calls,
            TokenSource tokens,
            InstantSource clock,
            CarryOverPace.Sleeper sleeper) {}

    private CarryOverCommand() {}

    /**
     * Runs the command against GitHub.
     *
     * @param rest the arguments after {@code carry-over}
     * @param environment the environment Git searches the checkouts with and the token is lent in
     * @param out where the plan's lines go
     * @param err where a fault or a failure goes
     * @return 0 when the plan is whole or every item was carried, 1 when an item cannot be or was
     *     not carried, 2 when nothing could be planned or the run stopped
     */
    static int run(
            final List<String> rest,
            final Map<String, String> environment,
            final PrintStream out,
            final PrintStream err) {
        return run(
                rest,
                environment,
                out,
                err,
                PrivateFiles.of(environment),
                () -> {
                    final ForgeTokens tokens = ForgeTokens.of(environment);
                    // GitHub's answers are never followed elsewhere, so the token goes to its API
                    // alone.
                    final HttpClient http =
                            HttpClient.newBuilder()
                                    .followRedirects(HttpClient.Redirect.NEVER)
                                    .connectTimeout(Duration.ofSeconds(10))
                                    .build();
                    final GitHubGraphQl graphQl = new GitHubGraphQl(GITHUB_API, http);
                    final GitHubClient client = new GitHubClient(GITHUB_API, http);
                    return new Remote(
                            ProjectQueries.over(graphQl),
                            ProjectWrites.over(graphQl, client),
                            CarryOverCalls.over(client),
                            origin -> tokens.token(origin, origin.getHost()),
                            InstantSource.system(),
                            wait -> Thread.sleep(wait));
                });
    }

    /** Makes the remote only when a real run needs it. */
    @FunctionalInterface
    interface Remotes {
        Remote remote();
    }

    /** The parsed command line. */
    private record Arguments(
            Path project,
            ProjectRef target,
            boolean dry,
            @Nullable Path seed,
            @Nullable Path state,
            int perMinute) {}

    /**
     * Runs the command.
     *
     * @param rest the arguments after {@code carry-over}
     * @param environment the environment Git searches the checkouts with
     * @param out where the plan's lines go
     * @param err where a fault or a failure goes
     * @param files the platform's private files, which the map is kept in
     * @param remotes the calls a real run makes
     * @return the exit code
     */
    static int run(
            final List<String> rest,
            final Map<String, String> environment,
            final PrintStream out,
            final PrintStream err,
            final PrivateFiles files,
            final Remotes remotes) {
        final List<String> secrets = new ArrayList<>();
        try {
            return run(rest, environment, out, err, files, remotes, secrets);
        } catch (final RuntimeException e) {
            say(err, secrets, "failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return WorkCommands.UNANSWERED;
        }
    }

    private static int run(
            final List<String> rest,
            final Map<String, String> environment,
            final PrintStream out,
            final PrintStream err,
            final PrivateFiles files,
            final Remotes remotes,
            final List<String> secrets) {
        final Arguments arguments = arguments(rest, err);
        if (arguments == null) {
            return WorkCommands.UNANSWERED;
        }
        final Path root;
        try {
            root = arguments.project().toRealPath();
        } catch (final IOException e) {
            say(err, secrets, arguments.project() + " could not be read: " + e.getMessage());
            return WorkCommands.UNANSWERED;
        }
        if (arguments.dry()) {
            return dryRun(root, arguments, environment, out, err, secrets);
        }
        final Path stateFile;
        try {
            stateFile =
                    arguments.state() != null
                            ? arguments.state()
                            : CarryOverState.defaultFile(files, environment, arguments.target());
        } catch (final IllegalStateException | IllegalArgumentException e) {
            say(err, secrets, "the planner's state directory is refused: " + e.getMessage());
            return WorkCommands.UNANSWERED;
        }
        try (CarryOverState state = CarryOverState.open(stateFile, files)) {
            return realRun(root, arguments, environment, out, err, state, remotes, secrets);
        } catch (final IOException e) {
            say(err, secrets, "the map " + stateFile + " is refused: " + e.getMessage());
            return WorkCommands.UNANSWERED;
        }
    }

    private static @Nullable Arguments arguments(final List<String> rest, final PrintStream err) {
        String project = null;
        String to = null;
        String seed = null;
        String state = null;
        String perMinute = null;
        boolean dry = false;
        for (int i = 0; i < rest.size(); i++) {
            final String arg = rest.get(i);
            final boolean valued = i + 1 < rest.size();
            if (arg.equals("--project") && project == null && valued) {
                project = rest.get(++i);
            } else if (arg.equals("--to") && to == null && valued) {
                to = rest.get(++i);
            } else if (arg.equals("--seed") && seed == null && valued) {
                seed = rest.get(++i);
            } else if (arg.equals("--state") && state == null && valued) {
                state = rest.get(++i);
            } else if (arg.equals("--per-minute") && perMinute == null && valued) {
                perMinute = rest.get(++i);
            } else if (arg.equals("--dry-run") && !dry) {
                dry = true;
            } else {
                usage(err);
                return null;
            }
        }
        if (project == null || to == null || (dry && state != null)) {
            usage(err);
            return null;
        }
        int pace = CarryOverPace.MOST_PER_MINUTE;
        if (perMinute != null) {
            if (!perMinute.matches("[1-9][0-9]?")
                    || Integer.parseInt(perMinute) > CarryOverPace.MOST_PER_MINUTE) {
                err.println(
                        "carry-over: --per-minute: a whole number from 1 to "
                                + CarryOverPace.MOST_PER_MINUTE);
                return null;
            }
            pace = Integer.parseInt(perMinute);
        }
        final ProjectRef target;
        try {
            target = ProjectRef.parse(to);
        } catch (final IllegalArgumentException e) {
            err.println("carry-over: --to: " + e.getMessage());
            return null;
        }
        return new Arguments(
                Path.of(project),
                target,
                dry,
                seed == null ? null : Path.of(seed),
                state == null ? null : Path.of(state),
                pace);
    }

    private static int dryRun(
            final Path root,
            final Arguments arguments,
            final Map<String, String> environment,
            final PrintStream out,
            final PrintStream err,
            final List<String> secrets) {
        final Read read = read(root, environment, false, err, secrets);
        if (read == null) {
            return WorkCommands.UNANSWERED;
        }
        if (arguments.seed() != null && seed(arguments, read, err, secrets) == null) {
            return WorkCommands.UNANSWERED;
        }
        if (!onTarget(read.project(), arguments, err, secrets)) {
            return WorkCommands.UNANSWERED;
        }
        final CarryOverPlan plan = read.planner().apply(CarryOverPlan.References.BY_OLD_ID);
        plan.items().forEach(item -> out.println(item.line(true)));
        asides(read.project(), out);
        final long stories = plan.items().stream().filter(CarryOverPlan.Item::story).count();
        final long cannot = plan.items().stream().filter(item -> item.problem() != null).count();
        out.println(
                "DRY RUN  "
                        + plan.items().size()
                        + " item(s) to "
                        + arguments.target().owner()
                        + "/"
                        + arguments.target().number()
                        + ": "
                        + stories
                        + " story(ies), "
                        + (plan.items().size() - stories)
                        + " issue(s); nothing was written");
        if (cannot > 0) {
            err.println(
                    "carry-over: " + cannot + " item(s) cannot be carried; the plan is not whole");
            return WorkCommands.REFUSED;
        }
        return 0;
    }

    private static int realRun(
            final Path root,
            final Arguments arguments,
            final Map<String, String> environment,
            final PrintStream out,
            final PrintStream err,
            final CarryOverState state,
            final Remotes remotes,
            final List<String> secrets) {
        final String target = arguments.target().owner() + "/" + arguments.target().number();
        final CarryOverMap kept;
        try {
            kept = state.read();
        } catch (final IOException | IllegalArgumentException e) {
            say(err, secrets, "the map " + state.file() + " is refused: " + e.getMessage());
            return WorkCommands.UNANSWERED;
        }
        if (kept != null
                && (!kept.root().equals(root.toString()) || !kept.target().equals(target))) {
            say(
                    err,
                    secrets,
                    "the map "
                            + state.file()
                            + " was made for another checkout or GitHub project; nothing was"
                            + " written");
            return WorkCommands.UNANSWERED;
        }
        final Path projectFile = root.resolve("project.yml");
        final String before;
        try {
            before = text(projectFile);
        } catch (final IOException e) {
            say(err, secrets, projectFile + " could not be read: " + e.getMessage());
            return WorkCommands.UNANSWERED;
        }
        // A run that wrote project.yml and stopped before removing its map knows the change.
        final boolean written =
                kept != null
                        && kept.projectFile() != null
                        && sha256(before).equals(kept.projectFile());
        final Read read = read(root, environment, written, err, secrets);
        if (read == null) {
            return WorkCommands.UNANSWERED;
        }
        try {
            if (!written && read.project().projectFileChanged()) {
                say(
                        err,
                        secrets,
                        projectFile
                                + " has changes not committed; commit or drop them first, since"
                                + " the run writes it");
                return WorkCommands.UNANSWERED;
            }
        } catch (final IOException e) {
            say(err, secrets, root + " could not be asked for its changes: " + e.getMessage());
            return WorkCommands.UNANSWERED;
        }
        if (!written && CheckoutProject.names(before, "velocity") && arguments.seed() != null) {
            say(
                    err,
                    secrets,
                    projectFile + " names a velocity already; no seed is written over it");
            return WorkCommands.UNANSWERED;
        }
        final Map<String, Map<String, Integer>> seed;
        if (arguments.seed() != null) {
            seed = seed(arguments, read, err, secrets);
            if (seed == null) {
                return WorkCommands.UNANSWERED;
            }
        } else {
            seed = Map.of();
        }
        // The project.yml an earlier run wrote ends in what it added; this run would add the same.
        if (written && !before.endsWith(changed("", target, seed))) {
            say(
                    err,
                    secrets,
                    projectFile
                            + " was written by an earlier run of this carry-over with another seed"
                            + " than this run names; run it again as before, with the same --seed"
                            + " or none, and change the velocity in project.yml afterwards");
            return WorkCommands.UNANSWERED;
        }
        if (!onTarget(read.project(), arguments, err, secrets)) {
            return WorkCommands.UNANSWERED;
        }

        final Remote remote = remotes.remote();
        final TokenSource tokens =
                origin -> {
                    final String token = remote.tokens().token(origin);
                    secrets.add(token);
                    return token;
                };
        final CarryOverMap map =
                kept != null
                        ? kept
                        : CarryOverMap.begin(root.toString(), target, remote.clock().instant());
        final Board board = read.project().board();
        final ProjectConfig config =
                new ProjectConfig(
                        FORGE,
                        arguments.target(),
                        "carry-over",
                        board.workflow(),
                        board.work(),
                        board.repositories(),
                        Map.of(),
                        Map.of());
        // Each wait is said as it begins, so a person sees where an unattended run stands.
        final CarryOverPace pace =
                new CarryOverPace(
                        remote.clock(),
                        remote.sleeper(),
                        line -> say(err, secrets, line),
                        arguments.perMinute());
        final CarryOver.Outcome outcome;
        try {
            outcome =
                    new CarryOver(
                                    pace.queries(remote.queries()),
                                    pace.writes(remote.writes()),
                                    pace.calls(remote.calls()),
                                    tokens,
                                    remote.clock())
                            .run(config, read.planner(), map, state);
        } catch (final CarryOver.Stopped | StoreException e) {
            say(err, secrets, "stopped: " + e.getMessage());
            say(
                    err,
                    secrets,
                    "the map is kept in "
                            + state.file()
                            + "; run the same command again to go on from it");
            return WorkCommands.UNANSWERED;
        }
        for (final CarryOverPlan.Item item : outcome.plan().items()) {
            final String reference = outcome.references().get(item.id());
            out.println(
                    item.line(false)
                            + "  -> "
                            + GITHUB
                            + arguments.target().owner()
                            + "/"
                            + item.repository()
                            + "/issues/"
                            + (reference == null
                                    ? "?"
                                    : reference.substring(reference.indexOf('#') + 1)));
        }
        asides(read.project(), out);
        if (!outcome.whole()) {
            outcome.failures().forEach(failure -> say(err, secrets, "not carried: " + failure));
            say(
                    err,
                    secrets,
                    outcome.failures().size()
                            + " item(s) were not carried whole; project.yml was not written, and"
                            + " the map is kept in "
                            + state.file());
            return WorkCommands.REFUSED;
        }
        if (!written) {
            final String after = changed(before, target, seed);
            map.projectFile(sha256(after));
            state.save(map);
            try {
                replace(projectFile, after);
            } catch (final IOException e) {
                say(err, secrets, projectFile + " could not be written: " + e.getMessage());
                say(err, secrets, "the map is kept in " + state.file() + "; run again to write it");
                return WorkCommands.UNANSWERED;
            }
        }
        try {
            state.remove();
        } catch (final IOException e) {
            say(
                    err,
                    secrets,
                    "the map " + state.file() + " could not be removed: " + e.getMessage());
            return WorkCommands.REFUSED;
        }
        out.println(
                "CARRIED  "
                        + outcome.plan().items().size()
                        + " item(s) to "
                        + target
                        + "; the map "
                        + state.file()
                        + " was removed");
        out.println(
                "project.yml now names the tracker"
                        + (seed.isEmpty() ? "" : " and the velocity seed")
                        + " and is not committed: review it, sign it with `porelo project sign`"
                        + " and commit it");
        return 0;
    }

    /**
     * Checks that every repository stands under the target's owner on github.com, by its own name,
     * and is named by an address the tracked project accepts, since the GitHub store writes to a
     * repository by the name {@code project.yml} gives it and the tracked project opens only by the
     * tracked project's own rule.
     */
    private static boolean onTarget(
            final CheckoutProject project,
            final Arguments arguments,
            final PrintStream err,
            final List<String> secrets) {
        final String owner = arguments.target().owner().toLowerCase(Locale.ROOT);
        final URI home = URI.create(GITHUB + owner);
        final List<String> elsewhere = new ArrayList<>();
        project.centrals()
                .forEach(
                        (name, central) -> {
                            final String web = project.webs().get(name);
                            if (!home.equals(trackedHome(name, central))
                                    || web == null
                                    || !web.toLowerCase(Locale.ROOT)
                                            .equals(
                                                    GITHUB
                                                            + owner
                                                            + "/"
                                                            + name.toLowerCase(Locale.ROOT))) {
                                elsewhere.add(name);
                            }
                        });
        if (elsewhere.isEmpty()) {
            return true;
        }
        say(
                err,
                secrets,
                "repositories not on github.com under "
                        + arguments.target().owner()
                        + " by an https or ssh address <user>@github.com:<owner>/<repository>: "
                        + String.join(", ", elsewhere.stream().sorted().toList())
                        + "; nothing was written");
        return false;
    }

    /** The host and owner a tracked project takes a central for, or null when it refuses it. */
    private static @Nullable URI trackedHome(final String name, final String central) {
        try {
            return ProjectFile.home(name, new CloneUrl(central));
        } catch (final StoreException | IllegalArgumentException e) {
            return null;
        }
    }

    /** What the checkouts hold and how to plan their carry-over. */
    private record Read(
            CheckoutProject project, Function<CarryOverPlan.References, CarryOverPlan> planner) {}

    private static @Nullable Read read(
            final Path root,
            final Map<String, String> environment,
            final boolean trackerWritten,
            final PrintStream err,
            final List<String> secrets) {
        try {
            final CheckoutProject read = CheckoutProject.read(root, environment, trackerWritten);
            return new Read(read, planner(read));
        } catch (final IOException | IllegalArgumentException | StoreException e) {
            say(err, secrets, root + " could not be read whole: " + e.getMessage());
            return null;
        } catch (final Unread e) {
            // A fault may name a link a person wrote, with a login or a token in it.
            e.faults.forEach(fault -> say(err, secrets, fault));
            say(
                    err,
                    secrets,
                    e.faults.size()
                            + " thing(s) of "
                            + root
                            + " could not be read; no plan is given");
            return null;
        }
    }

    private static @Nullable Map<String, Map<String, Integer>> seed(
            final Arguments arguments,
            final Read read,
            final PrintStream err,
            final List<String> secrets) {
        try {
            return CarryOverSeed.read(
                    java.util.Objects.requireNonNull(arguments.seed()),
                    read.project().board().repositories());
        } catch (final IOException e) {
            say(err, secrets, "--seed: the file could not be read: " + e.getMessage());
            return null;
        } catch (final IllegalArgumentException e) {
            say(err, secrets, String.valueOf(e.getMessage()));
            return null;
        }
    }

    private static void asides(final CheckoutProject project, final PrintStream out) {
        if (!project.asides().isEmpty()) {
            out.println("FAULTS OF THE BOARD THAT CONCERN NO CARRIED ITEM");
            project.asides().forEach(aside -> out.println(CheckoutProject.withoutSecrets(aside)));
        }
    }

    /** The board's faults, which leave the plan unwhole before it is made. */
    private static final class Unread extends Exception {
        private static final long serialVersionUID = 1L;

        private final transient List<String> faults;

        private Unread(final List<String> faults) {
            super(faults.size() + " fault(s)", null, false, false);
            this.faults = List.copyOf(faults);
        }
    }

    private static Function<CarryOverPlan.References, CarryOverPlan> planner(
            final CheckoutProject project) throws IOException, Unread {
        final Board board = project.board();
        if (!project.faults().isEmpty()) {
            throw new Unread(project.faults());
        }
        final Map<String, CarryOverPlan.IssueSource> sources = new LinkedHashMap<>();
        for (final Issue issue : CarryOverPlan.carried(board)) {
            final IssueHead head = project.head(issue);
            final StageRecords.Carried stages = project.stages(issue);
            sources.put(
                    issue.id(),
                    new CarryOverPlan.IssueSource(
                            issue,
                            project.path(issue),
                            head.priority(),
                            head.rank(),
                            head.covers(),
                            project.below(issue),
                            stages.stages(),
                            stages.moves(),
                            stages.estimate()));
        }
        final Map<String, String> texts = new LinkedHashMap<>();
        for (final Story story : board.stories()) {
            texts.put(story.id(), CheckoutProject.storyText(story));
        }
        final Map<String, String> webs = project.webs();
        // Planned once here, so a board that cannot be planned stops the run before any call.
        CarryOverPlan.of(board, sources, texts, webs, CarryOverPlan.References.BY_OLD_ID);
        return references -> CarryOverPlan.of(board, sources, texts, webs, references);
    }

    /** {@code project.yml} with the tracker and the seed added below what the person wrote. */
    private static String changed(
            final String before,
            final String target,
            final Map<String, Map<String, Integer>> seed) {
        final StringBuilder after = new StringBuilder(before);
        if (!before.isEmpty() && !before.endsWith("\n")) {
            after.append('\n');
        }
        after.append("\n# The tracker that keeps the stories and issues since the carry-over.\n")
                .append("tracker:\n    kind: \"github\"\n    project: \"")
                .append(target)
                .append("\"\n");
        if (!seed.isEmpty()) {
            after.append("\n# The velocity's starting minutes per repository and size.\n")
                    .append(CarryOverSeed.yaml(seed));
        }
        return after.toString();
    }

    private static String text(final Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a plain file");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Replaces a file whole, keeping its permissions, so a stop never leaves it half written. */
    private static void replace(final Path file, final String text) throws IOException {
        final Path written = file.resolveSibling(file.getFileName() + ".carry-over");
        Files.deleteIfExists(written);
        Files.writeString(
                written,
                text,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            final Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(written, permissions);
        }
        Files.move(
                written, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(final String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-256", e);
        }
    }

    /** Says a line on the error stream, free of every token taken and every secret form. */
    private static void say(final PrintStream err, final List<String> secrets, final String line) {
        err.println("carry-over: " + CheckoutProject.withoutSecrets(Scrubbed.text(line, secrets)));
    }

    private static void usage(final PrintStream err) {
        err.println("usage: " + USAGE);
    }
}

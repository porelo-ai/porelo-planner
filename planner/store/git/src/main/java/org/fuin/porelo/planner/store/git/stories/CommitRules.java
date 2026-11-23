package org.fuin.porelo.planner.store.git.stories;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds each commit of a range to the shared rules on commit messages, with the fault lines and
 * exit codes the build tools' {@code check-commits} gives.
 *
 * <p>A subject of one line of at most 72 characters, {@code [skip ci]} counted; below it only a
 * blank line and trailers; no requirement number, since the issue is closed in its tracker; and
 * nobody named as having asked for or made the change, as far as {@link Who} can tell. An issue
 * number is told by its shape or as a GitHub reference; a {@code Porelo-Issue} trailer per issue,
 * in GitHub's reference form, is the one place a number may stand, and no closing keyword may
 * precede an issue reference anywhere in the message, so no push closes an issue. A commit
 * Dependabot or GitHub's Actions bot authored, or GitHub committed in its web interface, is exempt
 * from these checks on a push or a pull request from the repository itself, whose writer may push
 * anyway; a pull request from outside sets the names it records itself, so there no commit is
 * exempt. No measurement is read from a commit's tree: a task's measurements are fields of its item
 * in the tracker.
 */
public final class CommitRules {

    /** The exit code of a range a commit of which breaks a rule. */
    public static final int REFUSED = 1;

    /** The exit code of a range that could not be checked: never a pass. */
    public static final int UNANSWERED = 2;

    /** The longest subject, every character counted. */
    static final int LONGEST_SUBJECT = 72;

    /** The authors whose commits are exempt: the bots GitHub runs. */
    static final Set<String> EXEMPT_AUTHORS = Set.of("dependabot[bot]", "github-actions[bot]");

    /** The committer of a merge made in GitHub's web interface. */
    static final String WEB_COMMITTER = "GitHub";

    /**
     * The one line kind allowed below the subject: a trailer of a key the shared rules name, its
     * key in any case, as git reads one. Porelo's own commits carry two more, each held to its
     * form: its change id, one token, and the file a commit signs anew, a path inside the
     * repository: no leading {@code /}, no {@code ..} part, and only letters, digits and {@code . _
     * - /}.
     */
    private static final Pattern TRAILER =
            Pattern.compile(
                    "^(?:(?:Assisted-by|Signed-off-by): \\S.*"
                            + "|Porelo-Change-Id: \\S+"
                            + "|Porelo-Signs-Anew: (?!/)(?!(?:.*/)?\\.\\.(?:/|$))[A-Za-z0-9._/-]+)$",
                    Pattern.CASE_INSENSITIVE);

    /**
     * The trailer that names the issue a commit was made for: its key in any case, the reference
     * GitHub's {@code <owner>/<repository>#<n>}, the owner and repository as GitHub names them, a
     * repository never of dots alone, and the number from 1.
     */
    private static final Pattern PORELO_ISSUE =
            Pattern.compile(
                    "^Porelo-Issue: [A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?/(?!\\.+#)[A-Za-z0-9._-]+"
                            + "#[1-9]\\d*$",
                    Pattern.CASE_INSENSITIVE);

    /** That trailer's key, in any case, to tell a trailer of another form from a body line. */
    private static final Pattern PORELO_ISSUE_KEY =
            Pattern.compile("Porelo-Issue:", Pattern.CASE_INSENSITIVE);

    /**
     * A word GitHub closes an issue by when the commit reaches the default branch, followed by an
     * issue reference as GitHub reads one: {@code #<n>}, {@code GH-<n>}, {@code
     * <owner>/<repository>#<n>} or the issue's address.
     */
    private static final Pattern CLOSING =
            Pattern.compile(
                    "\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)(?::\\s*|\\s+)"
                            + "(?:#\\d+|GH-\\d+|[\\w.-]+/[\\w.-]+#\\d+"
                            + "|https?://(?:www\\.)?github\\.com/[\\w.-]+/[\\w.-]+/issues/\\d+)\\b",
                    Pattern.CASE_INSENSITIVE);

    /** A sign-off, its key in any case, and the address it names. */
    private static final Pattern SIGN_OFF =
            Pattern.compile("^Signed-off-by: .*<([^<>\\s]+)>$", Pattern.CASE_INSENSITIVE);

    /** A sign-off's key, in any case. */
    private static final Pattern SIGN_OFF_KEY =
            Pattern.compile("Signed-off-by:", Pattern.CASE_INSENSITIVE);

    /** The shape of an issue number. */
    private static final Pattern SHAPE = Pattern.compile("\\b[A-Z]{1,2}\\d{2,3}\\b");

    /**
     * A reference GitHub links to an issue of the project, the owner in any case: the issue's
     * address, with or without its scheme, {@code porelo-ai/<repository>#<n>}, or {@code GH-<n>}.
     */
    private static final Pattern GITHUB_REFERENCE =
            Pattern.compile(
                    "(?<!\\w)(?:(?:https?://)?(?:www\\.)?github\\.com/porelo-ai/[\\w.-]+/issues/\\d+"
                            + "|porelo-ai/[\\w.-]+#\\d+|GH-\\d+)\\b",
                    Pattern.CASE_INSENSITIVE);

    /**
     * A number GitHub links in a commit message to an issue of the same repository: {@code #<n>},
     * not part of a word, a path or a colour.
     */
    private static final Pattern BARE_REFERENCE = Pattern.compile("(?<![\\w&/#$])#\\d+\\b");

    /** Names of an issue number's shape that are something else: PKCE's challenge method. */
    private static final Set<String> NOT_NUMBERS = Set.of("S256");

    /** What git writes ahead of each commit, and between its fields. */
    private static final char RECORD = '\u001e';

    private static final char FIELD = '\u001f';

    private final PrintStream out;

    private final PrintStream err;

    private final Git git;

    /** Reads the log of a range. */
    @FunctionalInterface
    public interface Git {

        /**
         * Per commit: the record separator, then the short hash, author name and address, committer
         * name and address, and the raw message, apart by the unit separator.
         */
        String FORMAT = "%x1e%h%x1f%an%x1f%ae%x1f%cn%x1f%ce%x1f%B";

        /**
         * Returns git's log of a range in {@link #FORMAT}.
         *
         * @param range the range, never an option
         * @return the log, empty when the range holds nothing
         * @throws IOException when git cannot be run or refuses the range
         */
        String log(String range) throws IOException;

        /**
         * Counts the commits of a range as git counts them, to hold the log's records to.
         *
         * @param range the range, never an option
         * @return the count, or empty where this reader cannot count
         * @throws IOException when git cannot be run or refuses the range
         */
        default OptionalLong count(String range) throws IOException {
            return OptionalLong.empty();
        }

        /**
         * Runs git in a directory.
         *
         * @param directory the checkout
         * @param environment set for git on top of this process's own
         * @return the runner
         */
        public static Git in(Path directory, Map<String, String> environment) {
            return new Git() {
                /**
                 * Reads the requested range's log, refused when a name, an address or a message
                 * holds a field separator, which shifts or hides a commit's fields. This is
                 * stricter than the build tools' check, which compares only the number of commits
                 * and so misses a field shifted within one commit: such a log gets no answer, exit
                 * 2.
                 */
                @Override
                public String log(String range) throws IOException {
                    final String log = run(directory, environment, range);
                    final long records = log.chars().filter(c -> c == RECORD).count();
                    final long fields = log.chars().filter(c -> c == FIELD).count();
                    if (fields > 5 * records) {
                        throw new IOException(
                                "the log of "
                                        + records
                                        + " record(s) holds "
                                        + fields
                                        + " field separator(s): a name, address or message holds"
                                        + " one, so the commits cannot be told apart");
                    }
                    return log;
                }

                /** Counts the range's commits. */
                @Override
                public OptionalLong count(String range) throws IOException {
                    final String counted =
                            execute(
                                            directory,
                                            environment,
                                            List.of(
                                                    "rev-list",
                                                    "--count",
                                                    "--end-of-options",
                                                    range,
                                                    "--"))
                                    .strip();
                    if (!counted.matches("\\d{1,9}")) {
                        throw new IOException("git rev-list counted no number: " + counted);
                    }
                    return OptionalLong.of(Long.parseLong(counted));
                }
            };
        }

        private static String run(Path directory, Map<String, String> environment, String range)
                throws IOException {
            return execute(
                    directory,
                    environment,
                    List.of(
                            "-c",
                            "log.showSignature=false",
                            "log",
                            "--no-color",
                            "--format=" + FORMAT,
                            "--end-of-options",
                            range,
                            "--"));
        }

        private static String execute(
                Path directory, Map<String, String> environment, List<String> arguments)
                throws IOException {
            final List<String> command =
                    new ArrayList<>(List.of("git", "-C", directory.toString()));
            command.addAll(arguments);
            final ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(environment);
            // None of these commands runs a configured program, but the forge's token is git's
            // business in no case.
            builder.environment().remove("GITHUB_TOKEN");
            builder.environment().put("LC_ALL", "C");
            // To files rather than pipes: reading a pipe to its end would wait out a hang, not the
            // timeout.
            final Path output = Files.createTempFile("check-commits", ".out");
            final Path errors = Files.createTempFile("check-commits", ".err");
            try {
                final Process process =
                        builder.redirectOutput(output.toFile())
                                .redirectError(errors.toFile())
                                .start();
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException(
                            "git " + arguments.getFirst() + " did not finish within 60 seconds");
                }
                if (process.exitValue() != 0) {
                    throw new IOException(
                            "git "
                                    + arguments.getFirst()
                                    + " exited with "
                                    + process.exitValue()
                                    + ": "
                                    + Files.readString(errors, StandardCharsets.UTF_8).strip());
                }
                return Files.readString(output, StandardCharsets.UTF_8);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while git read the log", ex);
            } finally {
                Files.deleteIfExists(output);
                Files.deleteIfExists(errors);
            }
        }
    }

    /** One commit as git recorded it. */
    private record Commit(
            String hash,
            String author,
            String authorMail,
            String committer,
            String committerMail,
            String message) {

        boolean exempt() {
            return EXEMPT_AUTHORS.contains(author) || WEB_COMMITTER.equals(committer);
        }
    }

    /**
     * Constructor.
     *
     * @param out where the result goes
     * @param err where a refusal goes
     * @param git where the log is read
     */
    public CommitRules(PrintStream out, PrintStream err, Git git) {
        this.out = out;
        this.err = err;
        this.git = git;
    }

    /**
     * Checks every commit of a range.
     *
     * @param range a git range, such as {@code origin/main..HEAD}
     * @param outside true for a pull request from outside, whose every commit that is not exempt
     *     carries its author's {@code Signed-off-by:}
     * @return 0 when no commit breaks the rule, an empty range among them; {@link #REFUSED} when
     *     one does; {@link #UNANSWERED} when the log cannot be read
     */
    public int check(String range, boolean outside) {
        if (range.isBlank() || range.startsWith("-")) {
            err.println("a range names revisions, never an option: '" + range + "'");
            return UNANSWERED;
        }
        final List<Commit> commits;
        try {
            commits = parse(git.log(range));
            final OptionalLong counted = git.count(range);
            if (counted.isPresent() && counted.getAsLong() != commits.size()) {
                // A message holding git's record and field separators forges a commit or hides its
                // own body; the log is then no account of the range.
                throw new IOException(
                        "the log splits into "
                                + commits.size()
                                + " commit(s) where git counts "
                                + counted.getAsLong()
                                + "; a message holds git's separators");
            }
        } catch (IOException ex) {
            err.println("could not read the commits of " + range + ": " + ex.getMessage());
            return UNANSWERED;
        }
        final List<String> faults = new ArrayList<>();
        int exempt = 0;
        int breaking = 0;
        for (final Commit commit : commits) {
            // From outside, a recorded name is the writer's own word, which anyone can write.
            if (!outside && commit.exempt()) {
                exempt++;
                continue;
            }
            final List<String> broken = faults(commit, outside);
            if (!broken.isEmpty()) {
                breaking++;
            }
            broken.forEach(fault -> faults.add(commit.hash() + "  " + fault));
        }
        if (faults.isEmpty()) {
            out.println(
                    "OK    "
                            + commits.size()
                            + " commit(s) in "
                            + range
                            + ", "
                            + exempt
                            + " exempt");
            return 0;
        }
        faults.forEach(err::println);
        err.println(
                faults.size()
                        + " fault(s) in "
                        + breaking
                        + " of "
                        + commits.size()
                        + " commit(s) in "
                        + range
                        + ": commit messages must follow the shared rules");
        return REFUSED;
    }

    /** The faults of one commit's message. */
    private static List<String> faults(Commit commit, boolean outside) {
        final List<String> faults = new ArrayList<>();
        final String[] lines = commit.message().split("\n", -1);
        final String subject = lines[0];
        final int length = subject.codePointCount(0, subject.length());
        if (length > LONGEST_SUBJECT) {
            faults.add("subject is " + length + " characters, at most " + LONGEST_SUBJECT);
        }
        if (lines.length > 1 && !lines[1].isEmpty()) {
            faults.add("the subject is not followed by a blank line");
        }
        final StringBuilder words = new StringBuilder(subject);
        final List<String> trailers = new ArrayList<>();
        final int first = lines.length > 1 && lines[1].isEmpty() ? 2 : 1;
        for (int i = first; i < lines.length; i++) {
            if (TRAILER.matcher(lines[i]).matches()) {
                trailers.add(lines[i]);
            } else if (PORELO_ISSUE.matcher(lines[i]).matches()) {
                // The one place a number may stand, so the number rule reads it not.
                continue;
            } else if (PORELO_ISSUE_KEY.matcher(lines[i]).lookingAt()) {
                faults.add(
                        "a Porelo-Issue trailer not of the form"
                                + " 'Porelo-Issue: <owner>/<repository>#<number>': "
                                + lines[i]);
            } else {
                faults.add(
                        "below the subject, a line that is not a trailer: "
                                + (lines[i].isBlank() ? "(a blank line)" : lines[i]));
                words.append('\n').append(lines[i]);
            }
        }
        // A trailer is read too: none of them may carry a number, and only a sign-off, which names
        // its author by design, may name a person.
        final StringBuilder named = new StringBuilder(words);
        for (final String trailer : trailers) {
            words.append('\n').append(trailer);
            if (!SIGN_OFF_KEY.matcher(trailer).lookingAt()) {
                named.append('\n').append(trailer);
            }
        }
        for (final Pattern shape : List.of(SHAPE, GITHUB_REFERENCE, BARE_REFERENCE)) {
            final Matcher number = shape.matcher(words);
            while (number.find()) {
                if (!NOT_NUMBERS.contains(number.group())) {
                    faults.add("names a requirement number: " + number.group());
                }
            }
        }
        // Only the person sets Finished, never a push.
        final Matcher closing = CLOSING.matcher(commit.message());
        while (closing.find()) {
            faults.add("closes an issue on push: " + closing.group());
        }
        Who.named(named.toString(), List.of(commit.author(), commit.committer()))
                .forEach(party -> faults.add("says who: " + party));
        if (outside && !signedOffByAuthor(trailers, commit.authorMail())) {
            faults.add(
                    "no Signed-off-by: naming its author <"
                            + commit.authorMail()
                            + ">, which a pull request from outside carries on every commit");
        }
        return faults;
    }

    private static boolean signedOffByAuthor(List<String> trailers, String mail) {
        for (final String trailer : trailers) {
            final Matcher signOff = SIGN_OFF.matcher(trailer);
            if (signOff.matches()
                    && signOff.group(1)
                            .toLowerCase(Locale.ROOT)
                            .equals(mail.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<Commit> parse(String log) throws IOException {
        final List<Commit> commits = new ArrayList<>();
        for (final String record : log.split(String.valueOf(RECORD), -1)) {
            if (record.isBlank()) {
                continue;
            }
            final String[] fields = record.split(String.valueOf(FIELD), 6);
            if (fields.length != 6) {
                throw new IOException(
                        "git wrote a commit of "
                                + fields.length
                                + " field(s), not 6: "
                                + abbreviated(record));
            }
            commits.add(
                    new Commit(
                            fields[0],
                            fields[1],
                            fields[2],
                            fields[3],
                            fields[4],
                            fields[5].stripTrailing()));
        }
        return commits;
    }

    private static String abbreviated(String text) {
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}

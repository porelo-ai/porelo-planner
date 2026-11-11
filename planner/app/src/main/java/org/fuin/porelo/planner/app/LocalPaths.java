package org.fuin.porelo.planner.app;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Writes the local paths in a refusal's words relative to the base they were resolved from - the
 * home directory as {@code ~}, the state directory as {@code $XDG_STATE_HOME} or {@code
 * %LOCALAPPDATA%} - so no absolute path, and with it no account name, reaches an answer.
 */
final class LocalPaths {

    /** What a path under no known base is named instead of itself. */
    static final String OUTSIDE = "a path outside the home directory";

    /**
     * An absolute POSIX, drive or UNC path not continuing a word or another path, taking along each
     * word after a space that holds a separator, since it continues a name with a space in it.
     */
    private static final Pattern ABSOLUTE =
            Pattern.compile(
                    "(?<![\\w.~$%/\\\\-])(?:/|[A-Za-z]:\\\\|\\\\\\\\)[^\\s'\"]*"
                            + "(?: +[^\\s'\"/\\\\]*[/\\\\][^\\s'\"]*)*");

    /** Punctuation that ends a sentence or clause rather than the path before it. */
    private static final Pattern TRAILING = Pattern.compile("[.,;:)]+$");

    /** A drive's directory below its root. */
    private static final Pattern WINDOWS_BASE = Pattern.compile("[A-Za-z]:\\\\.+");

    private static final Pattern UNSHOWN =
            Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\p{Co}\\p{Cs}]");

    private final List<Base> bases;

    private record Base(Pattern start, String name) {}

    private LocalPaths(final List<Base> bases) {
        this.bases = bases;
    }

    /**
     * The bases of this platform, read from the planner's environment.
     *
     * @param environment the planner's environment
     * @param windows whether the paths are Windows paths
     * @return the paths' writer
     */
    static LocalPaths of(final Map<String, String> environment, final boolean windows) {
        final Map<String, String> named =
                windows
                        ? Map.of("LOCALAPPDATA", "%LOCALAPPDATA%", "USERPROFILE", "~")
                        : Map.of("XDG_STATE_HOME", "$XDG_STATE_HOME", "HOME", "~");
        final List<Map.Entry<String, String>> found = new ArrayList<>();
        named.forEach(
                (variable, name) -> {
                    final String value = variable(environment, variable, windows);
                    final String plain = value == null ? null : plain(value, windows);
                    if (plain != null) {
                        found.add(Map.entry(plain, name));
                    }
                });
        // The deepest base names a path, so a state directory under the home keeps its own name.
        found.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()));
        final List<Base> bases = new ArrayList<>();
        for (final Map.Entry<String, String> base : found.reversed()) {
            final String end = windows ? "(?![^\\\\\\s'\",;:)])" : "(?![^/\\s'\",;:)])";
            bases.add(
                    new Base(
                            Pattern.compile(
                                    "(?<![\\w.~$%/\\\\-])" + Pattern.quote(base.getKey()) + end,
                                    windows ? Pattern.CASE_INSENSITIVE : 0),
                            base.getValue()));
        }
        return new LocalPaths(List.copyOf(bases));
    }

    /**
     * Writes the words with each path relative to its base, one under no base left unnamed, and
     * control and format characters replaced.
     *
     * @param words a check's or the system's words
     * @return the words an answer may carry
     */
    String relative(final String words) {
        String text = UNSHOWN.matcher(words).replaceAll("?");
        for (final Base base : bases) {
            text = base.start().matcher(text).replaceAll(Matcher.quoteReplacement(base.name()));
        }
        return ABSOLUTE.matcher(text)
                .replaceAll(
                        path -> {
                            final Matcher trailing = TRAILING.matcher(path.group());
                            return Matcher.quoteReplacement(
                                    OUTSIDE + (trailing.find() ? trailing.group() : ""));
                        });
    }

    private static @Nullable String variable(
            final Map<String, String> environment, final String name, final boolean windows) {
        final String value = environment.get(name);
        if (value != null || !windows) {
            return value;
        }
        // Windows reads a variable's name in any case; a copied environment may not.
        for (final Map.Entry<String, String> variable : environment.entrySet()) {
            if (variable.getKey().equalsIgnoreCase(name)) {
                return variable.getValue();
            }
        }
        return null;
    }

    /**
     * A base in the form the checks print it, or null for one not worth naming: relative, or the
     * root, which every path lies under.
     */
    private static @Nullable String plain(final String value, final boolean windows) {
        if (windows) {
            final String trimmed = value.replaceAll("\\\\+$", "");
            return WINDOWS_BASE.matcher(trimmed).matches() ? trimmed : null;
        }
        try {
            final Path path = Path.of(value);
            return path.isAbsolute() && path.getParent() != null ? path.toString() : null;
        } catch (final InvalidPathException invalid) {
            return null;
        }
    }
}

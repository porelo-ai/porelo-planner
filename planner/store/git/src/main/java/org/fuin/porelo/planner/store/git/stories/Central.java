package org.fuin.porelo.planner.store.git.stories;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.git.CloneUrl;
import org.jspecify.annotations.Nullable;

/**
 * A repository's central as {@code project.yml} gives it, read as a clone URL: {@code
 * https://host[:port]/path}, {@code ssh://[user@]host[:port]/path} or {@code user@host:path}, on
 * any host and under any owner.
 *
 * @param name the repository's name: the last part of the path, without {@code .git}
 * @param web where the repository's pages are linked: {@code https://}, the central's host in lower
 *     case, its port for {@code https://}, and its path without {@code .git}
 */
record Central(String name, String web) {

    private static final String HOST = "[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?";

    private static final String PORT = "(?::(?<port>[0-9]{1,5}))?";

    private static final String USER = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    // No segment starts with a dot, so neither "." nor ".." can climb out of the owner's path, nor
    // with a dash, which Git could read as an option.
    private static final String PATH =
            "[A-Za-z0-9_~][A-Za-z0-9._~-]*(?:/[A-Za-z0-9_~][A-Za-z0-9._~-]*)*";

    private static final Pattern HTTPS =
            Pattern.compile("https://(?<host>" + HOST + ")" + PORT + "/(?<path>" + PATH + ")");

    private static final Pattern SSH =
            Pattern.compile(
                    "ssh://(?:"
                            + USER
                            + "@)?(?<host>"
                            + HOST
                            + ")"
                            + PORT
                            + "/(?<path>"
                            + PATH
                            + ")");

    private static final Pattern SCP =
            Pattern.compile(USER + "@(?<host>" + HOST + "):/?(?<path>" + PATH + ")");

    /**
     * The names the store takes for a repository's clone, so an accepted central never fails it.
     */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");

    /** The most characters of a value a refusal repeats. */
    private static final int SHOWN = 80;

    /** What a refusal shows of a value that cannot be told apart from a credential. */
    static final String NOT_SHOWN = "<not shown>";

    /**
     * Reads a central, refusing anything that is no clone URL of one of the three shapes, or one
     * the store would not hand to Git.
     *
     * @param where where in {@code project.yml} the central stands, for the refusal
     * @param key the key it is given under, for the refusal
     * @param value the central as written
     * @return the repository's name and pages
     * @throws IllegalArgumentException naming {@code where}, {@code key} and the value without any
     *     user, password, query or fragment, cut and with control characters escaped
     */
    static Central read(String where, String key, String value) {
        final Matcher match = matched(value);
        if (match != null && !value.contains("..") && port(match) && cloned(value)) {
            final String path = match.group("path");
            final String last = path.substring(path.lastIndexOf('/') + 1);
            final String name = last.endsWith(".git") ? last.substring(0, last.length() - 4) : last;
            if (NAME.matcher(name).matches()) {
                final String repository = path.substring(0, path.length() - last.length()) + name;
                return new Central(name, web(match) + "/" + repository);
            }
        }
        throw new IllegalArgumentException(
                where
                        + ": "
                        + key
                        + " "
                        + shown(safe(value))
                        + " is no clone URL (https://host/path, ssh://host/path or user@host:path,"
                        + " its last part a repository name of lowercase letters, digits, '.', '_'"
                        + " and '-')");
    }

    private static @Nullable Matcher matched(String value) {
        for (final Pattern shape : new Pattern[] {HTTPS, SSH, SCP}) {
            final Matcher match = shape.matcher(value);
            if (match.matches()) {
                return match;
            }
        }
        return null;
    }

    private static boolean port(Matcher match) {
        if (match.pattern() == SCP || match.group("port") == null) {
            return true;
        }
        final int port = Integer.parseInt(match.group("port"));
        return port >= 1 && port <= 65535;
    }

    // The store hands the central to Git as a CloneUrl; one rule keeps a central read here from
    // failing there.
    private static boolean cloned(String value) {
        try {
            new CloneUrl(value);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    // An SSH port is no port of the pages, so only an https:// central keeps its port.
    private static String web(Matcher match) {
        final String host = match.group("host").toLowerCase(Locale.ROOT);
        final String port =
                match.pattern() == HTTPS && match.group("port") != null
                        ? ":" + Integer.parseInt(match.group("port"))
                        : "";
        return "https://" + host + port;
    }

    /**
     * A central as a refusal may show it: everything up to the last {@code @} before the host, and
     * any query and fragment, left out, since each may hold a credential.
     *
     * @param value a central as written
     * @return the central without user, password, query or fragment, or {@link #NOT_SHOWN} when
     *     those cannot be told apart
     */
    static String safe(String value) {
        final Matcher scheme = SCHEME.matcher(value);
        final String prefix = scheme.find() ? scheme.group() : "";
        String rest = value.substring(prefix.length());
        final int query = firstOf(rest, '?', '#');
        if (query >= 0) {
            // An '@' after a '?' or '#' may belong to a password or to a query's credential.
            if (rest.indexOf('@', query) >= 0) {
                return NOT_SHOWN;
            }
            rest = rest.substring(0, query);
        }
        return prefix + rest.substring(rest.lastIndexOf('@') + 1);
    }

    private static int firstOf(String text, char one, char other) {
        final int first = text.indexOf(one);
        final int second = text.indexOf(other);
        return first < 0 ? second : second < 0 ? first : Math.min(first, second);
    }

    /**
     * A value as a refusal may show it: cut after {@value #SHOWN} characters, a control, format or
     * separator character escaped as its UTF-16 units.
     *
     * @param value a value read from {@code project.yml}, a central already made {@link #safe}
     * @return the value to show
     */
    static String shown(String value) {
        final boolean cut = value.codePointCount(0, value.length()) > SHOWN;
        final String kept = cut ? value.substring(0, value.offsetByCodePoints(0, SHOWN)) : value;
        final StringBuilder shown = new StringBuilder(kept.length() + 3);
        kept.codePoints()
                .forEach(
                        point -> {
                            if (escaped(point)) {
                                for (final char unit : Character.toChars(point)) {
                                    shown.append(String.format("\\u%04x", (int) unit));
                                }
                            } else {
                                shown.appendCodePoint(point);
                            }
                        });
        return cut ? shown.append("...").toString() : shown.toString();
    }

    private static boolean escaped(int point) {
        final int type = Character.getType(point);
        return Character.isISOControl(point)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}

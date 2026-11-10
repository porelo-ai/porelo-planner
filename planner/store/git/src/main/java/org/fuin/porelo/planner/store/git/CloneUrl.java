package org.fuin.porelo.planner.store.git;

import java.util.regex.Pattern;

/**
 * A clone URL the planner hands to Git: {@code https://}, {@code ssh://} or {@code user@host:path},
 * with no password in it and nothing Git could read as an option or a local path.
 *
 * @param value the URL as checked
 */
public record CloneUrl(String value) {

    private static final String HOST =
            "[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?(:[0-9]{1,5})?";

    private static final String USER = "[A-Za-z0-9._-]{1,64}";

    private static final String PATH = "[A-Za-z0-9._~/-]{1,512}";

    private static final Pattern HTTPS = Pattern.compile("https://" + HOST + "/" + PATH);

    private static final Pattern SSH =
            Pattern.compile("ssh://(" + USER + "@)?" + HOST + "/" + PATH);

    private static final Pattern SCP =
            Pattern.compile(
                    USER
                            + "@[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?:"
                            + "[A-Za-z0-9._~/]"
                            + PATH);

    /** Refuses every other form; the refusal never repeats the URL, which may hold a password. */
    public CloneUrl {
        if (!(HTTPS.matcher(value).matches()
                        || SSH.matcher(value).matches()
                        || SCP.matcher(value).matches())
                || value.contains("..")) {
            throw new IllegalArgumentException(
                    "Not a clone URL the planner takes (https://host/path, ssh://[user@]host/path or"
                            + " user@host:path, without a password or '..'), "
                            + value.length()
                            + " characters");
        }
    }

    /** Never the URL itself, so a log line cannot show what a caller passed. */
    @Override
    public String toString() {
        return "CloneUrl[" + value.replaceFirst("^[a-z]+://", "").replaceFirst("[:/].*", "") + "]";
    }
}

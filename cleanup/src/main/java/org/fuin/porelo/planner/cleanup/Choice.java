package org.fuin.porelo.planner.cleanup;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses the snapshot builds the clean-up deletes. A repository sets or moves its pin only to a
 * build younger than 7 days, so a build older than {@link #OLDER} is never pinned anew between
 * reading the pins and deleting, and the two need no lock.
 */
public final class Choice {

    /** How many of the newest snapshot builds always stay. */
    public static final int NEWEST = 10;

    /** How old a snapshot build must be before it may go. */
    public static final Duration OLDER = Duration.ofDays(30);

    /** A snapshot build's version, as the Build publishes it; nothing else is ever chosen. */
    private static final Pattern SNAPSHOT =
            Pattern.compile("([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})~snapshot\\.([0-9]{1,9})");

    private Choice() {}

    /**
     * Chooses the builds to delete.
     *
     * @param files every file of the listing, by its folder
     * @param pinned every snapshot version a repository's default branch names
     * @param now the time the choice is made at
     * @return the versions of the snapshot builds to delete, the newest first
     */
    public static List<String> of(
            final List<Build> files, final Set<String> pinned, final Instant now) {
        final Map<String, Instant> builds = new HashMap<>();
        for (final Build file : files) {
            if (SNAPSHOT.matcher(file.folder()).matches()) {
                builds.merge(file.folder(), file.time(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        final List<Map.Entry<String, Instant>> newestFirst = new ArrayList<>(builds.entrySet());
        newestFirst.sort(
                Comparator.comparing((Map.Entry<String, Instant> build) -> build.getValue())
                        .thenComparing(build -> run(build.getKey()))
                        .reversed());
        final Instant before = now.minus(OLDER);
        final List<String> chosen = new ArrayList<>();
        for (int i = NEWEST; i < newestFirst.size(); i++) {
            final Map.Entry<String, Instant> build = newestFirst.get(i);
            if (build.getValue().isBefore(before) && !pinned.contains(build.getKey())) {
                chosen.add(build.getKey());
            }
        }
        return List.copyOf(chosen);
    }

    private static long run(final String version) {
        final Matcher match = SNAPSHOT.matcher(version);
        return match.matches() ? Long.parseLong(match.group(4)) : -1;
    }
}

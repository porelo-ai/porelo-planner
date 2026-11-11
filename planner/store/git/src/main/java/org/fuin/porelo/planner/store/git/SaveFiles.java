package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The files of one save: a repository's planning files written out from its fetched commit, the
 * changed ones found after the edits and the indexes, and the commit's subject.
 */
final class SaveFiles {

    /** What check-commits takes for a requirement number; a state named so is left out. */
    private static final Pattern NUMBER = Pattern.compile("\\b[A-Z]{1,3}\\d+\\b");

    private static final Pattern PLAIN_STATE = Pattern.compile("[A-Za-z][A-Za-z ]{0,30}");

    private static final int SUBJECT = 72;

    /** Ends the subject of a commit no build needs; GitHub reads it from the pushed head. */
    private static final String SKIP_CI = " [skip ci]";

    private SaveFiles() {}

    /**
     * Writes a repository's issues and stories, their indexes and workflows, as its commit holds
     * them.
     *
     * @param remote reads the commit
     * @param repository the repository's name
     * @param commit the fetched commit
     * @param root the repository's directory in the save's scratch directory
     * @return each written file's text, by its path
     */
    static Map<String, String> write(
            final Remote remote, final String repository, final String commit, final Path root) {
        final List<String> files = new ArrayList<>();
        // The stories index reads the project's repositories and prefixes from it.
        if (remote.read(repository, commit, "project.yml").isPresent()) {
            files.add("project.yml");
        }
        for (final String top : List.of("issues", "stories")) {
            files.addAll(remote.list(repository, commit, top));
            for (final String group : remote.directories(repository, commit, top)) {
                files.addAll(remote.list(repository, commit, group));
            }
        }
        final Map<String, String> written = new LinkedHashMap<>();
        try {
            Files.createDirectories(root);
            for (final String file : files) {
                final String text =
                        remote.read(repository, commit, file)
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        file + " vanished from its commit"));
                final Path target = root.resolve(RepoPath.checked(file));
                Files.createDirectories(target.getParent());
                Files.writeString(target, text, StandardCharsets.UTF_8);
                written.put(file, text);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("the save's files could not be written", e);
        }
        return written;
    }

    /**
     * Answers the files a save may commit that differ from the commit: the edited heads and the
     * indexes, never anything else a library wrote; a head the save deleted is among them.
     *
     * @param root the repository's directory in the scratch directory
     * @param before each file's text at the commit, by path
     * @param heads the heads the save edited
     * @return the changed paths, sorted
     */
    static List<String> changed(
            final Path root, final Map<String, String> before, final Collection<String> heads) {
        final Set<String> allowed = new HashSet<>(heads);
        allowed.add("issues/README.md");
        allowed.add("stories/README.md");
        before.keySet().stream()
                .filter(path -> path.matches("issues/[^/]+/README\\.md"))
                .forEach(allowed::add);
        final List<String> changed = new ArrayList<>();
        for (final String path : allowed) {
            final Path file = root.resolve(path);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (before.containsKey(path)) {
                    changed.add(path);
                }
                continue;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(path + " is no plain file");
            }
            final String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            if (!text.equals(before.get(path))) {
                changed.add(path);
            }
        }
        changed.sort(null);
        return changed;
    }

    /**
     * Words a commit's subject: what moved and where to, how many issues' workers and stories'
     * priorities it sets, never which item, so it names no requirement number; a commit of planning
     * files alone ends with {@code [skip ci]}, so it starts no build, the whole within the
     * subject's limit.
     *
     * @param issues the issues the commit moves, with their new states
     * @param stories the stories whose head the commit changes, with their new stated states, or an
     *     empty text where the stated state goes
     * @param workers how many issues' workers the commit sets
     * @param idea whether the save adds an idea
     * @param paths the paths the commit changes
     * @return the subject
     */
    static String subject(
            final Map<String, String> issues,
            final Map<String, String> stories,
            final int workers,
            final boolean idea,
            final List<String> paths) {
        return subject(issues, stories, workers, 0, idea, paths);
    }

    /**
     * Words a commit's subject as {@link #subject(Map, Map, int, boolean, List)} does, the stories'
     * priorities it sets counted too.
     *
     * @param issues the issues the commit moves, with their new states
     * @param stories the stories whose stated state the commit changes, empty where it goes
     * @param workers how many issues' workers the commit sets
     * @param priorities how many stories' priorities the commit sets
     * @param idea whether the save adds an idea
     * @param paths the paths the commit changes
     * @return the subject
     */
    static String subject(
            final Map<String, String> issues,
            final Map<String, String> stories,
            final int workers,
            final int priorities,
            final boolean idea,
            final List<String> paths) {
        final boolean planning = !paths.isEmpty() && paths.stream().allMatch(SaveFiles::planning);
        final int limit = planning ? SUBJECT - SKIP_CI.length() : SUBJECT;
        final String words =
                idea && issues.isEmpty() && stories.isEmpty()
                        ? "Add an idea"
                        : words(issues, stories, workers, priorities, limit);
        return planning ? words + SKIP_CI : words;
    }

    /** Whether a path is one of the planning files no build reads. */
    private static boolean planning(final String path) {
        final List<String> parts = List.of(path.split("/", -1));
        // A path that leaves its directory again could name a file a build reads.
        return parts.size() > 1
                && List.of("issues", "stories", "stats").contains(parts.getFirst())
                && parts.stream().noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."));
    }

    private static String words(
            final Map<String, String> issues,
            final Map<String, String> stories,
            final int workers,
            final int priorities,
            final int limit) {
        final List<String> sets = new ArrayList<>();
        if (workers > 0) {
            sets.add("who works " + (workers == 1 ? "an issue" : workers + " issues"));
        }
        if (priorities > 0) {
            sets.add("the priority of " + (priorities == 1 ? "a story" : priorities + " stories"));
        }
        if (sets.isEmpty()) {
            return moved(issues, stories, limit);
        }
        final String set = "set " + String.join(" and ", sets);
        if (issues.isEmpty() && stories.isEmpty()) {
            return fitted("S" + set.substring(1), workers, priorities, limit);
        }
        final String both = moved(issues, stories, limit) + " and " + set;
        return both.length() <= limit
                ? both
                : fitted(
                        "Move " + (issues.size() + stories.size()) + " items and " + set,
                        workers,
                        priorities,
                        limit);
    }

    /** The words, or a shorter count of what changed where they exceed the limit. */
    private static String fitted(
            final String words, final int workers, final int priorities, final int limit) {
        return words.length() <= limit
                ? words
                : "Set " + workers + " workers and " + priorities + " priorities";
    }

    /**
     * Words what a commit moves.
     *
     * @param issues the issues the commit moves, with their new states
     * @param stories the stories whose head the commit changes, with their new stated states, or an
     *     empty text where the stated state goes
     * @param limit the longest the words may be
     * @return the subject
     */
    private static String moved(
            final Map<String, String> issues, final Map<String, String> stories, final int limit) {
        final int count = issues.size() + stories.size();
        if (count == 0) {
            return "Rewrite the planning indexes";
        }
        final String noun;
        final Collection<String> states;
        if (stories.isEmpty()) {
            noun = issues.size() == 1 ? "an issue" : issues.size() + " issues";
            states = issues.values();
        } else if (issues.isEmpty()) {
            noun = stories.size() == 1 ? "a story" : stories.size() + " stories";
            states = stories.values();
        } else {
            return "Move " + count + " items";
        }
        final Set<String> distinct = new HashSet<>(states);
        final String state = distinct.size() == 1 ? distinct.iterator().next() : "";
        final String subject = "Move " + noun + " to " + state;
        // A state that reads like a requirement number, or no single state, is left out.
        if (state.isEmpty()
                || !PLAIN_STATE.matcher(state).matches()
                || NUMBER.matcher(state).find()
                || subject.length() > limit) {
            return "Move " + noun;
        }
        return subject;
    }

    /**
     * Deletes a save's scratch directory and everything in it, never following a link.
     *
     * @param root the directory
     */
    static void delete(final Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> all = Files.walk(root)) {
            for (final Path path : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("the save's scratch directory could not be deleted", e);
        }
    }
}

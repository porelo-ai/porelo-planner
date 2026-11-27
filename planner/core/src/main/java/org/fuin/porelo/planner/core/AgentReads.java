package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.jspecify.annotations.Nullable;

/**
 * Reads of the board the planner holds that an agent asks for: the board in short, one issue by the
 * parts of its text, the breaches of the stories' rules and a search by words. Each reads the board
 * it is given and nothing else, and says of an item what the board says of it.
 */
public final class AgentReads {

    /** The most items a search answers. */
    public static final int MOST_MATCHES = 200;

    /** Where a story's text, or a text whose parts cannot be told apart, is found by a search. */
    public static final String TEXT = "text";

    /** Where a title is found by a search. */
    public static final String TITLE = "title";

    private AgentReads() {}

    /** Splits an issue's text into its named parts; the format of the text is the caller's. */
    @FunctionalInterface
    public interface Splitter {

        /**
         * Splits a text.
         *
         * @param text an issue's text
         * @return its parts, or the first thing to repair when they cannot be told apart
         */
        Split split(String text);
    }

    /**
     * An issue's text split.
     *
     * @param parts each part the text holds under its name, in the names' order; null when the
     *     sections cannot be told apart for certain
     * @param fault the first thing to repair, given only when the parts are null
     */
    public record Split(@Nullable Map<String, String> parts, @Nullable String fault) {

        /** Copies the parts, keeping their order. */
        public Split {
            if (parts != null) {
                parts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parts));
            }
        }

        /**
         * A text split into parts.
         *
         * @param parts each part under its name
         * @return the split
         */
        public static Split of(final Map<String, String> parts) {
            return new Split(parts, null);
        }

        /**
         * A text whose sections cannot be told apart.
         *
         * @param fault the first thing to repair
         * @return the split
         */
        public static Split ambiguous(final String fault) {
            return new Split(null, fault);
        }
    }

    /**
     * An issue in short.
     *
     * @param id the issue's id
     * @param title its title
     * @param state its state
     * @param size its size, or null
     * @param priority its priority, or null
     */
    public record OutlineIssue(
            String id,
            String title,
            String state,
            @Nullable String size,
            @Nullable String priority) {}

    /**
     * A story in short.
     *
     * @param id the story's id
     * @param title its title
     * @param state its state
     * @param priority its priority
     * @param issues the issues it names that the board holds, in its order
     */
    public record OutlineStory(
            String id, String title, String state, String priority, List<OutlineIssue> issues) {}

    /**
     * The board in short.
     *
     * @param stories each story
     * @param issues the issues no story names that are not sub-issues of an issue a story names
     */
    public record Outline(List<OutlineStory> stories, List<OutlineIssue> issues) {}

    /**
     * One issue with its text by parts.
     *
     * @param issue the issue as the board holds it
     * @param story the id of the story that names the issue, or null
     * @param split its text's parts, or the fault that prevents them
     */
    public record IssueParts(Issue issue, @Nullable String story, Split split) {}

    /**
     * A breach of a rule, grouped by the rule and the story it concerns.
     *
     * @param rule the rule broken
     * @param story the story it concerns, or null for an issue in no story
     * @param issues the issues it names, in the board's order
     * @param words what is broken, in fixed words and ids
     */
    public record Breach(
            StoryRules.Rule rule, @Nullable String story, List<String> issues, String words) {}

    /** What kind of item a search found. */
    public enum Kind {
        /** A story. */
        STORY,
        /** An issue. */
        ISSUE
    }

    /**
     * One item a search found.
     *
     * @param id the item's id
     * @param kind a story or an issue
     * @param title its title
     * @param state its state
     * @param found where words were found: {@code title}, a part's name or {@code text}, each once
     */
    public record Match(String id, Kind kind, String title, String state, List<String> found) {}

    /**
     * What a search found.
     *
     * @param matches at most {@value #MOST_MATCHES} items, stories first
     * @param more whether others matched
     */
    public record Found(List<Match> matches, boolean more) {}

    /**
     * Shortens the board.
     *
     * @param board the board
     * @return each story with its issues, then the issues in no story
     */
    public static Outline outline(final Board board) {
        final List<OutlineStory> stories = new ArrayList<>();
        for (final Story story : board.stories()) {
            final List<OutlineIssue> issues = new ArrayList<>();
            for (final String id : story.issues()) {
                // An id on two issues shows each, as the board's answer does.
                board.issues().stream()
                        .filter(issue -> issue.id().equals(id))
                        .forEach(issue -> issues.add(outline(issue)));
            }
            stories.add(
                    new OutlineStory(
                            story.id(), story.title(), story.state(), story.priority(), issues));
        }
        final Set<String> assigned = StoryRules.inStories(board);
        return new Outline(
                List.copyOf(stories),
                board.issues().stream()
                        .filter(issue -> !assigned.contains(issue.id()))
                        .map(AgentReads::outline)
                        .toList());
    }

    private static OutlineIssue outline(final Issue issue) {
        return new OutlineIssue(
                issue.id(), issue.title(), issue.state(), issue.size(), issue.priority());
    }

    /**
     * Reads one issue with its text by parts.
     *
     * @param board the board
     * @param id the issue's id
     * @param splitter splits the issue's text
     * @return the issue, the story that names it and its text's parts
     * @throws StoreException.NoSuchIssue when the board holds no one issue of the id
     */
    public static IssueParts parts(final Board board, final String id, final Splitter splitter) {
        final Issue issue = board.issue(id).orElseThrow(() -> new StoreException.NoSuchIssue(id));
        final @Nullable String story =
                board.stories().stream()
                        .filter(s -> s.issues().contains(id))
                        .map(Story::id)
                        .findFirst()
                        .orElse(null);
        return new IssueParts(issue, story, splitter.split(issue.text()));
    }

    /**
     * Lists the breaches of the stories' rules the board computes, grouped by rule and story.
     *
     * @param board the board
     * @return each group, in the order of the board's first breach of it
     */
    public static List<Breach> breaches(final Board board) {
        record Key(StoryRules.Rule rule, @Nullable String story) {}
        final Map<Key, List<StoryRules.Breach>> groups = new LinkedHashMap<>();
        for (final StoryRules.Breach breach : StoryRules.of(board)) {
            final String story =
                    breach.rule() == StoryRules.Rule.ISSUE_IN_NO_STORY ? null : breach.item();
            groups.computeIfAbsent(new Key(breach.rule(), story), k -> new ArrayList<>())
                    .add(breach);
        }
        final List<Breach> answer = new ArrayList<>();
        groups.forEach(
                (key, group) ->
                        answer.add(
                                new Breach(
                                        key.rule(),
                                        key.story(),
                                        group.stream()
                                                .flatMap(b -> b.issues().stream())
                                                .distinct()
                                                .toList(),
                                        String.join(
                                                " ",
                                                group.stream()
                                                        .map(StoryRules.Breach::words)
                                                        .toList()))));
        return List.copyOf(answer);
    }

    /**
     * Finds the stories and issues whose title or text holds every word, each as a whole word and
     * without regard to case: a word may stand in the title and another in the text.
     *
     * @param board the board
     * @param words the words, none blank
     * @param splitter splits an issue's text
     * @return the matches, stories first, then issues, each in the board's order
     */
    public static Found search(
            final Board board, final List<String> words, final Splitter splitter) {
        final List<Pattern> patterns = words.stream().map(AgentReads::whole).toList();
        final List<Match> all = new ArrayList<>();
        for (final Story story : board.stories()) {
            final Map<String, String> places = new LinkedHashMap<>();
            places.put(TITLE, story.title());
            places.put(TEXT, story.description());
            matched(story.id(), Kind.STORY, story.title(), story.state(), places, patterns, null)
                    .ifPresent(all::add);
        }
        for (final Issue issue : board.issues()) {
            final Map<String, String> places = new LinkedHashMap<>();
            places.put(TITLE, issue.title());
            final Split split = splitter.split(issue.text());
            if (split.parts() != null) {
                places.putAll(split.parts());
            }
            // A section of the person's own or a second paragraph is in no part, yet still text.
            matched(
                            issue.id(),
                            Kind.ISSUE,
                            issue.title(),
                            issue.state(),
                            places,
                            patterns,
                            issue.text())
                    .ifPresent(all::add);
        }
        final boolean more = all.size() > MOST_MATCHES;
        return new Found(List.copyOf(all.subList(0, Math.min(all.size(), MOST_MATCHES))), more);
    }

    private static Optional<Match> matched(
            final String id,
            final Kind kind,
            final String title,
            final String state,
            final Map<String, String> places,
            final List<Pattern> patterns,
            final @Nullable String rest) {
        final Set<String> found = new LinkedHashSet<>();
        for (final Pattern pattern : patterns) {
            boolean held = false;
            for (final Map.Entry<String, String> place : places.entrySet()) {
                if (pattern.matcher(place.getValue()).find()) {
                    found.add(place.getKey());
                    held = true;
                }
            }
            if (!held && rest != null && pattern.matcher(rest).find()) {
                found.add(TEXT);
                held = true;
            }
            if (!held) {
                return Optional.empty();
            }
        }
        // Each place once, in the order the item holds them, the rest of the text last.
        final List<String> ordered =
                new ArrayList<>(places.keySet().stream().filter(found::contains).toList());
        if (found.contains(TEXT) && !ordered.contains(TEXT)) {
            ordered.add(TEXT);
        }
        return Optional.of(new Match(id, kind, title, state, ordered));
    }

    private static Pattern whole(final String word) {
        return Pattern.compile(
                "(?<![\\p{L}\\p{N}_])" + Pattern.quote(word.strip()) + "(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}

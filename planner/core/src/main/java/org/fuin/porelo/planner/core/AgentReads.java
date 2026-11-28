package org.fuin.porelo.planner.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
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

    /** The largest complete JSON page, except an indivisible item answered alone. */
    public static final int PAGE_CHARACTERS = 60000;

    /** The page size when no limit is given. */
    public static final int DEFAULT_LIMIT = 50;

    /**
     * A page of whole top-level items.
     *
     * @param items the selected items, in their original order
     * @param next the continuation, or null at the end
     * @param <T> the whole top-level item type
     */
    public record Page<T>(List<T> items, @Nullable String next) {
        /** Copies the items so a size measurement and the returned page agree. */
        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * Alternatives within a key and conjunction across keys.
     *
     * @param states the states, none for all
     * @param priorities the priorities, none for all
     * @param repositories the repository names, none for all
     */
    public record Filters(List<String> states, List<String> priorities, List<String> repositories) {
        /** Canonicalizes selections so reordering alternatives does not change a cursor. */
        public Filters {
            states = states.stream().distinct().sorted().toList();
            priorities = priorities.stream().distinct().sorted().toList();
            repositories = repositories.stream().distinct().sorted().toList();
        }

        /** Selects a story by its own fields and the repositories of the issues it names. */
        public boolean matches(final Board board, final Story story) {
            return (states.isEmpty() || states.contains(story.state()))
                    && (priorities.isEmpty() || priorities.contains(story.priority()))
                    && (repositories.isEmpty()
                            || board.issues().stream()
                                    .anyMatch(
                                            issue ->
                                                    story.issues().contains(issue.id())
                                                            && repositories.contains(
                                                                    issue.repository())));
        }

        /** Selects an issue by its own fields. */
        public boolean matches(final Issue issue) {
            return (states.isEmpty() || states.contains(issue.state()))
                    && (priorities.isEmpty()
                            || issue.priority() != null && priorities.contains(issue.priority()))
                    && (repositories.isEmpty() || repositories.contains(issue.repository()));
        }
    }

    /**
     * Selects whole items by count and the size of the complete serialized answer.
     *
     * @param revision the store content revision
     * @param scope the caller's project, read and canonical selection identity
     * @param items all selected items in stable order
     * @param limit the maximum item count, from 1 to 200
     * @param after the preceding page's continuation, or null
     * @param characters measures the complete answer, including its continuation and envelope
     * @param <T> the whole top-level item type
     * @return a page that makes progress, including an oversized item alone when necessary
     */
    public static <T> Page<T> page(
            final String revision,
            final String scope,
            final List<T> items,
            final int limit,
            final @Nullable String after,
            final ToIntFunction<Page<T>> characters) {
        if (limit < 1 || limit > MOST_MATCHES) {
            throw WorkRefusal.invalidPage();
        }
        final int start = after == null ? 0 : position(after, revision, scope, items.size());
        Page<T> chosen = new Page<>(List.of(), null);
        if (characters.applyAsInt(chosen) > PAGE_CHARACTERS) {
            throw WorkRefusal.pageTooLarge();
        }
        final int end = start + Math.min(limit, items.size() - start);
        for (int stop = start + 1; stop <= end; stop++) {
            final Page<T> candidate =
                    new Page<>(
                            items.subList(start, stop),
                            stop < items.size() ? cursor(revision, scope, stop) : null);
            if (characters.applyAsInt(candidate) > PAGE_CHARACTERS) {
                if (chosen.items().isEmpty()) chosen = candidate;
                break;
            }
            chosen = candidate;
        }
        return chosen;
    }

    private static String cursor(final String revision, final String scope, final int position) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (final DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(1);
                out.writeUTF(revision);
                out.writeUTF(scope);
                out.writeInt(position);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (final IOException failure) {
            throw new IllegalStateException("The page continuation cannot be written.", failure);
        }
    }

    private static int position(
            final String after, final String revision, final String scope, final int count) {
        if (after.isEmpty() || after.length() > 4096 || !after.matches("[A-Za-z0-9_-]+")) {
            throw WorkRefusal.invalidPage();
        }
        try {
            final byte[] bytes = Base64.getUrlDecoder().decode(after);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(after)) {
                throw WorkRefusal.invalidPage();
            }
            try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (in.readUnsignedByte() != 1) throw WorkRefusal.invalidPage();
                final String heldRevision = in.readUTF();
                final String heldScope = in.readUTF();
                final int position = in.readInt();
                if (in.available() != 0 || !heldScope.equals(scope) || position < 1) {
                    throw WorkRefusal.invalidPage();
                }
                if (!heldRevision.equals(revision)) throw WorkRefusal.pageChanged();
                if (position >= count) throw WorkRefusal.invalidPage();
                return position;
            }
        } catch (final IOException | IllegalArgumentException failure) {
            throw WorkRefusal.invalidPage();
        }
    }

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
        return outline(board, new Filters(List.of(), List.of(), List.of()));
    }

    /**
     * Shortens the selected stories and loose issues, keeping every selected story whole.
     *
     * @param board the board
     * @param filters the item selection
     * @return selected stories followed by selected issues in no story
     */
    public static Outline outline(final Board board, final Filters filters) {
        final List<OutlineStory> stories = new ArrayList<>();
        for (final Story story : board.stories()) {
            if (!filters.matches(board, story)) continue;
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
                        .filter(issue -> !assigned.contains(issue.id()) && filters.matches(issue))
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
        return search(SearchIndex.of(board, splitter), words);
    }

    /**
     * Finds the stories and issues in a built search index whose title or text holds every word,
     * each as a whole word and without regard to case: a word may stand in the title and another in
     * the text.
     *
     * @param index the board's search index
     * @param words the words, none blank
     * @return the matches, stories first, then issues, each in the board's order
     */
    public static Found search(final SearchIndex index, final List<String> words) {
        final List<Match> all = searchAll(index, words);
        return new Found(
                List.copyOf(all.subList(0, Math.min(all.size(), MOST_MATCHES))),
                all.size() > MOST_MATCHES);
    }

    /**
     * Finds the complete ordered match list before a caller selects a bounded page.
     *
     * @param board the board
     * @param words the words, none blank
     * @param splitter splits an issue's text
     * @return every matching story, then every matching issue
     */
    public static List<Match> searchAll(
            final Board board, final List<String> words, final Splitter splitter) {
        return searchAll(SearchIndex.of(board, splitter), words);
    }

    /**
     * Finds the complete ordered match list from a built search index before a caller selects a
     * bounded page.
     *
     * @param index the board's search index
     * @param words the words, none blank
     * @return every matching story, then every matching issue
     */
    public static List<Match> searchAll(final SearchIndex index, final List<String> words) {
        return index.search(words);
    }
}

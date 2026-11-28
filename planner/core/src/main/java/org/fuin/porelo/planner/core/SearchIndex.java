package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Version;
import org.jspecify.annotations.Nullable;

/**
 * Case-folded places of a board's stories and issues held for whole-word search, built once per
 * board revision and updated incrementally by item version.
 */
public final class SearchIndex {

    private final Revision revision;

    private final List<Entry> entries;

    private final int maxPlaces;

    private SearchIndex(final Revision revision, final List<Entry> entries, final int maxPlaces) {
        this.revision = revision;
        this.entries = entries;
        this.maxPlaces = maxPlaces;
    }

    /**
     * Builds a search index for a board from scratch.
     *
     * @param board the board
     * @param splitter splits an issue's text into named parts
     * @return the index for {@code board}
     */
    public static SearchIndex of(final Board board, final AgentReads.Splitter splitter) {
        return build(null, board, splitter);
    }

    /**
     * Builds a search index for a board, reusing the indexed places of every story and issue whose
     * version is unchanged from this index.
     *
     * @param board the board
     * @param splitter splits an issue's text into named parts when its version changed
     * @return the index for {@code board}
     */
    public SearchIndex update(final Board board, final AgentReads.Splitter splitter) {
        final Map<ItemKey, IndexedPlaces> previous = HashMap.newHashMap(entries.size());
        for (final Entry entry : entries) {
            previous.put(
                    new ItemKey(entry.kind(), entry.repository(), entry.id(), entry.version()),
                    entry.places());
        }
        return build(previous, board, splitter);
    }

    /**
     * The board revision this index was built from.
     *
     * @return the revision
     */
    public Revision revision() {
        return revision;
    }

    /**
     * Finds every story and issue whose indexed places hold every word as a whole word without
     * regard to case.
     *
     * @param words the words, none blank
     * @return every matching story, then every matching issue, each in the board's order
     */
    List<AgentReads.Match> search(final List<String> words) {
        final List<String> needles = new ArrayList<>(words.size());
        for (final String word : words) {
            needles.add(fold(word.strip()));
        }
        final boolean[] hits = new boolean[maxPlaces];
        final List<AgentReads.Match> all = new ArrayList<>();
        for (final Entry entry : entries) {
            final AgentReads.@Nullable Match match = match(entry, needles, hits);
            if (match != null) {
                all.add(match);
            }
        }
        return List.copyOf(all);
    }

    private static SearchIndex build(
            final @Nullable Map<ItemKey, IndexedPlaces> previous,
            final Board board,
            final AgentReads.Splitter splitter) {
        final List<Entry> entries = new ArrayList<>(board.stories().size() + board.issues().size());
        int maxPlaces = 0;
        for (final Story story : board.stories()) {
            final @Nullable IndexedPlaces reused =
                    previous == null
                            ? null
                            : previous.get(
                                    new ItemKey(
                                            AgentReads.Kind.STORY,
                                            "",
                                            story.id(),
                                            story.version()));
            final IndexedPlaces places = reused != null ? reused : indexStory(story);
            maxPlaces = Math.max(maxPlaces, places.names().length);
            // An open story's state comes from its issues, outside its own version, so take it
            // from the current board.
            entries.add(
                    new Entry(
                            story.id(),
                            AgentReads.Kind.STORY,
                            "",
                            story.title(),
                            story.state(),
                            story.version(),
                            places));
        }
        for (final Issue issue : board.issues()) {
            final @Nullable IndexedPlaces reused =
                    previous == null
                            ? null
                            : previous.get(
                                    new ItemKey(
                                            AgentReads.Kind.ISSUE,
                                            issue.repository(),
                                            issue.id(),
                                            issue.version()));
            final IndexedPlaces places = reused != null ? reused : indexIssue(issue, splitter);
            maxPlaces = Math.max(maxPlaces, places.names().length);
            entries.add(
                    new Entry(
                            issue.id(),
                            AgentReads.Kind.ISSUE,
                            issue.repository(),
                            issue.title(),
                            issue.state(),
                            issue.version(),
                            places));
        }
        return new SearchIndex(board.revision(), List.copyOf(entries), maxPlaces);
    }

    private static IndexedPlaces indexStory(final Story story) {
        return new IndexedPlaces(
                new String[] {AgentReads.TITLE, AgentReads.TEXT},
                new String[] {fold(story.title()), fold(story.description())},
                null);
    }

    private static IndexedPlaces indexIssue(final Issue issue, final AgentReads.Splitter splitter) {
        final Map<String, String> places = new LinkedHashMap<>();
        places.put(AgentReads.TITLE, fold(issue.title()));
        final @Nullable Map<String, String> parts = splitter.split(issue.text()).parts();
        if (parts != null) {
            for (final Map.Entry<String, String> part : parts.entrySet()) {
                places.put(part.getKey(), fold(part.getValue()));
            }
        }
        // A section of the person's own or a second paragraph is in no part, yet still text.
        return new IndexedPlaces(
                places.keySet().toArray(String[]::new),
                places.values().toArray(String[]::new),
                fold(issue.text()));
    }

    private static AgentReads.@Nullable Match match(
            final Entry entry, final List<String> needles, final boolean[] hits) {
        final String[] names = entry.places().names();
        final String[] texts = entry.places().texts();
        final @Nullable String rest = entry.places().rest();
        final int placeCount = names.length;
        boolean dirty = false;
        boolean restHit = false;
        for (final String needle : needles) {
            boolean held = false;
            for (int i = 0; i < placeCount; i++) {
                if (containsWord(texts[i], needle)) {
                    if (!dirty) {
                        Arrays.fill(hits, 0, placeCount, false);
                        dirty = true;
                    }
                    hits[i] = true;
                    held = true;
                }
            }
            if (!held && rest != null && containsWord(rest, needle)) {
                restHit = true;
                held = true;
            }
            if (!held) {
                return null;
            }
        }
        // Each place once, in the order the item holds them, the rest of the text last.
        final List<String> found = new ArrayList<>();
        boolean hasText = false;
        if (dirty) {
            for (int i = 0; i < placeCount; i++) {
                if (hits[i]) {
                    found.add(names[i]);
                    if (AgentReads.TEXT.equals(names[i])) {
                        hasText = true;
                    }
                }
            }
        }
        if (restHit && !hasText) {
            found.add(AgentReads.TEXT);
        }
        return new AgentReads.Match(
                entry.id(), entry.kind(), entry.title(), entry.state(), List.copyOf(found));
    }

    private static boolean containsWord(final String haystack, final String needle) {
        final int needleLength = needle.length();
        final int haystackLength = haystack.length();
        int from = 0;
        while (from <= haystackLength) {
            final int pos = haystack.indexOf(needle, from);
            if (pos < 0) {
                return false;
            }
            final int end = pos + needleLength;
            final boolean beforeWord = pos > 0 && isWordChar(haystack.codePointBefore(pos));
            final boolean afterWord = end < haystackLength && isWordChar(haystack.codePointAt(end));
            if (!beforeWord && !afterWord) {
                return true;
            }
            from = pos + Character.charCount(haystack.codePointAt(pos));
        }
        return false;
    }

    /**
     * Folds each code point in the same way as Unicode case-insensitive matching, preserving
     * single-code-point length.
     */
    private static String fold(final String text) {
        final int length = text.length();
        int first = 0;
        while (first < length) {
            final char ch = text.charAt(first);
            if (ch >= 128 || (ch >= 'A' && ch <= 'Z')) {
                break;
            }
            first++;
        }
        if (first == length) {
            return text;
        }
        final StringBuilder out = new StringBuilder(length);
        out.append(text, 0, first);
        for (int i = first; i < length; ) {
            final char ch = text.charAt(i);
            if (ch < 128) {
                out.append(ch >= 'A' && ch <= 'Z' ? (char) (ch + 32) : ch);
                i++;
            } else {
                final int cp = text.codePointAt(i);
                out.appendCodePoint(Character.toLowerCase(Character.toUpperCase(cp)));
                i += Character.charCount(cp);
            }
        }
        return out.toString();
    }

    /** Whether a code point is a Unicode letter, a Unicode number or an underscore. */
    private static boolean isWordChar(final int codePoint) {
        if (codePoint < 128) {
            return (codePoint >= 'a' && codePoint <= 'z')
                    || (codePoint >= 'A' && codePoint <= 'Z')
                    || (codePoint >= '0' && codePoint <= '9')
                    || codePoint == '_';
        }
        return switch (Character.getType(codePoint)) {
            case Character.UPPERCASE_LETTER,
                    Character.LOWERCASE_LETTER,
                    Character.TITLECASE_LETTER,
                    Character.MODIFIER_LETTER,
                    Character.OTHER_LETTER,
                    Character.DECIMAL_DIGIT_NUMBER,
                    Character.LETTER_NUMBER,
                    Character.OTHER_NUMBER ->
                    true;
            default -> false;
        };
    }

    private record ItemKey(AgentReads.Kind kind, String repository, String id, Version version) {}

    private record IndexedPlaces(String[] names, String[] texts, @Nullable String rest) {}

    private record Entry(
            String id,
            AgentReads.Kind kind,
            String repository,
            String title,
            String state,
            Version version,
            IndexedPlaces places) {}
}

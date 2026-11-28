package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Proves {@link SearchIndex} matches whole-word regex semantics and reuses unchanged items. */
class SearchIndexTest {

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Backlog", "Implementing", "Review", "Finished"),
                    List.of(),
                    List.of("Finished"),
                    List.of("Implementing", "Review"));

    /** Parts a text names by a line {@code name: words}; a text that opens with ! is ambiguous. */
    private static final AgentReads.Splitter LINES =
            text -> {
                if (text.startsWith("!")) {
                    return AgentReads.Split.ambiguous("Line 1 is a heading.");
                }
                final Map<String, String> parts = new LinkedHashMap<>();
                for (final String line : text.split("\n")) {
                    final int colon = line.indexOf(':');
                    if (colon > 0) {
                        parts.put(line.substring(0, colon), line.substring(colon + 1).strip());
                    }
                }
                return AgentReads.Split.of(parts);
            };

    /**
     * Guards exact agreement between {@link SearchIndex} and Unicode case-insensitive whole-word
     * regular expressions across mixed case, umlauts, special case mappings, digits, underscores,
     * punctuation, supplementary characters and place edges.
     */
    @Test
    void indexAgreesWithWholeWordUnicodeRegexAcrossTrickyTextsAndEdges() {
        // Harm: indexed search silently misses or falsely matches items compared to whole-word
        // regex matching.
        final Board board =
                new Board(
                        new Revision("r1"),
                        WORKFLOW,
                        List.of(
                                new Story(
                                        "ST1",
                                        "Edge Cache Über İstanbul",
                                        "Backlog",
                                        "High",
                                        null,
                                        List.of("AB1"),
                                        "Leading edge, trailing EDGE.\n"
                                                + "foo_cache cache_1 2cache cache2 caches.\n"
                                                + "Straße vs STRASSE vs STRAẞE and σίγμα ς.\n"
                                                + "v1.0 c++ a,b (edge) edge-case a/edge/b.\n"
                                                + "x²cache cache² Ⅰcache cacheⅠ 😀edge😀.\n"
                                                + "\uD801\uDC00edge edge\uD801\uDC00 \uD801\uDC00.",
                                        null),
                                new Story(
                                        "ST2",
                                        "edge",
                                        "Idea",
                                        "Low",
                                        null,
                                        List.of(),
                                        "ÄRGER öffnen ıstanbul",
                                        null),
                                new Story(
                                        "ST3",
                                        "Folding",
                                        "Idea",
                                        "Low",
                                        null,
                                        List.of(),
                                        "\u212Aelvin kelvin KELVIN \u017Ftone STONE stone.\n"
                                                + "\uD83D\uDE00tail \uD83D\uDE00 tail x\uD83D\uDE00tail.\n"
                                                + "\uD801\uDC00ab \uD801\uDC00abc \uD801\uDC00ab.",
                                        null)),
                        List.of(
                                issue(
                                        "AB1",
                                        "example-lib",
                                        "CACHE in Title",
                                        "Backlog",
                                        "covers: edge\n"
                                                + "plan: Warm CaChE and ÜBER and ß and ẞ\n"
                                                + "Notes outside parts: latency_2 latency 42 v2"),
                                issue(
                                        "AB2",
                                        "example-lib",
                                        "Underscores and Digits",
                                        "Review",
                                        "covers: foo_bar _cache cache_ cache2 2cache\n"
                                                + "plan: überall ärger ÖFFNEN istanbul STRASSE"),
                                issue(
                                        "AB3",
                                        "example-lib",
                                        "Ambiguous Body",
                                        "Implementing",
                                        "!edge ΣΊΓΜΑ c++20 c++ v1.0"),
                                issue(
                                        "AB4",
                                        "example-lib",
                                        "Supplementary \uD801\uDC28",
                                        "Backlog",
                                        "plan: exact\uD801\uDC28 and 😀cache😀")),
                        new Work(Map.of(), List.of()));

        final SearchIndex index = SearchIndex.of(board, LINES);
        final List<List<String>> queries =
                List.of(
                        List.of("cache"),
                        List.of("CACHE"),
                        List.of("  cache  "),
                        List.of("edge"),
                        List.of("EDGE"),
                        List.of("über"),
                        List.of("ÜBER"),
                        List.of("ärger"),
                        List.of("öffnen"),
                        List.of("straße"),
                        List.of("STRASSE"),
                        List.of("straẞe"),
                        List.of("ß"),
                        List.of("ẞ"),
                        List.of("istanbul"),
                        List.of("İstanbul"),
                        List.of("ISTANBUL"),
                        List.of("ıstanbul"),
                        List.of("σίγμα"),
                        List.of("ΣΊΓΜΑ"),
                        List.of("ς"),
                        List.of("σ"),
                        List.of("42"),
                        List.of("v2"),
                        List.of("latency"),
                        List.of("latency_2"),
                        List.of("foo_bar"),
                        List.of("bar"),
                        List.of("v1.0"),
                        List.of("c++"),
                        List.of("a,b"),
                        List.of("edge-case"),
                        List.of("exact"),
                        List.of("\uD801\uDC00"),
                        List.of("\uD801\uDC28"),
                        List.of("\uD801\uDC00ab"),
                        List.of("tail"),
                        List.of("\uD83D\uDE00tail"),
                        List.of("kelvin"),
                        List.of("\u212Aelvin"),
                        List.of("KELVIN"),
                        List.of("stone"),
                        List.of("\u017Ftone"),
                        List.of("STONE"),
                        List.of("cache", "edge"),
                        List.of("edge", "cache"),
                        List.of("cache", "latency"),
                        List.of("über", "ß"),
                        List.of("missing"));

        final List<String> differences = new ArrayList<>();
        for (final List<String> query : queries) {
            final List<AgentReads.Match> expected = regexSearchAll(board, query, LINES);
            final List<AgentReads.Match> actual = AgentReads.searchAll(index, query);
            if (!actual.equals(expected)) {
                differences.add("query=" + query + " expected=" + expected + " actual=" + actual);
            }
            final AgentReads.Found expectedFound =
                    new AgentReads.Found(
                            List.copyOf(
                                    expected.subList(
                                            0, Math.min(expected.size(), AgentReads.MOST_MATCHES))),
                            expected.size() > AgentReads.MOST_MATCHES);
            final AgentReads.Found actualFound = AgentReads.search(index, query);
            if (!actualFound.equals(expectedFound)) {
                differences.add(
                        "found query="
                                + query
                                + " expected="
                                + expectedFound
                                + " actual="
                                + actualFound);
            }
        }
        assertThat(differences)
                .as("indexed search must agree with whole-word Unicode regex on every query")
                .isEmpty();
    }

    /**
     * Guards incremental re-indexing: unchanged items are not split again after another item
     * changes, while a changed issue is re-split and a story whose derived state changed reflects
     * its new state.
     */
    @Test
    void updatingAnIndexSplitsOnlyChangedIssuesAndRefreshesStoryState() {
        // Harm: editing one issue forces splitting the entire board again, or stale story state is
        // served after an issue moves.
        final AtomicInteger splits = new AtomicInteger();
        final List<String> splitTexts = new ArrayList<>();
        final AgentReads.Splitter counting =
                text -> {
                    splits.incrementAndGet();
                    splitTexts.add(text);
                    return LINES.split(text);
                };

        final Story st1Before =
                new Story(
                        "ST1",
                        "Gateway story",
                        "Backlog",
                        "High",
                        null,
                        List.of("AB1"),
                        "Story notes",
                        null);
        final Story st2 =
                new Story("ST2", "Other story", "Idea", "Low", null, List.of(), "Plain", null);
        final Issue ab1 = issue("AB1", "example-lib", "First", "Backlog", "plan: alpha");
        final Issue ab2 = issue("AB2", "example-lib", "Second", "Backlog", "plan: beta");
        final Issue ab3 = issue("AB3", "example-lib", "Third", "Backlog", "plan: gamma");
        final Board board1 =
                new Board(
                        new Revision("r1"),
                        WORKFLOW,
                        List.of(st1Before, st2),
                        List.of(ab1, ab2, ab3),
                        new Work(Map.of(), List.of()));

        final SearchIndex first = SearchIndex.of(board1, counting);
        assertThat(first.revision()).as("initial revision").isEqualTo(new Revision("r1"));
        assertThat(splits.get()).as("initial build splits every issue once").isEqualTo(3);

        splits.set(0);
        splitTexts.clear();

        final Story st1After =
                new Story(
                        "ST1",
                        "Gateway story",
                        "Implementing",
                        "High",
                        null,
                        List.of("AB1"),
                        "Story notes",
                        null);
        assertThat(st1After.version())
                .as("derived open-story state is outside the story's own version")
                .isEqualTo(st1Before.version());
        final Issue ab2Changed = issue("AB2", "example-lib", "Second", "Backlog", "plan: delta");
        final Board board2 =
                new Board(
                        new Revision("r2"),
                        WORKFLOW,
                        List.of(st1After, st2),
                        List.of(ab1, ab2Changed, ab3),
                        new Work(Map.of(), List.of()));

        final SearchIndex second = first.update(board2, counting);
        assertThat(second.revision()).as("updated revision").isEqualTo(new Revision("r2"));
        assertThat(splits.get()).as("only the one changed issue is split again").isEqualTo(1);
        assertThat(splitTexts).containsExactly("plan: delta");
        assertThat(AgentReads.searchAll(second, List.of("beta")))
                .as("removed word no longer matches")
                .isEmpty();
        assertThat(AgentReads.searchAll(second, List.of("delta")))
                .as("new word in changed issue matches")
                .containsExactly(
                        new AgentReads.Match(
                                "AB2",
                                AgentReads.Kind.ISSUE,
                                "Second",
                                "Backlog",
                                List.of("plan")));
        assertThat(AgentReads.searchAll(second, List.of("alpha")))
                .as("unchanged issue keeps its indexed parts")
                .containsExactly(
                        new AgentReads.Match(
                                "AB1", AgentReads.Kind.ISSUE, "First", "Backlog", List.of("plan")));
        assertThat(AgentReads.searchAll(second, List.of("gateway")))
                .as("story with unchanged version reports its current state")
                .containsExactly(
                        new AgentReads.Match(
                                "ST1",
                                AgentReads.Kind.STORY,
                                "Gateway story",
                                "Implementing",
                                List.of("title")));

        splits.set(0);
        splitTexts.clear();

        final Issue ab4 = issue("AB4", "example-lib", "Fourth", "Backlog", "covers: epsilon");
        final Board board3 =
                new Board(
                        new Revision("r3"),
                        WORKFLOW,
                        List.of(st1After, st2),
                        List.of(ab1, ab2Changed, ab4),
                        new Work(Map.of(), List.of()));

        final SearchIndex third = second.update(board3, counting);
        assertThat(splits.get()).as("only the newly added issue is split").isEqualTo(1);
        assertThat(splitTexts).containsExactly("covers: epsilon");
        assertThat(AgentReads.searchAll(third, List.of("gamma")))
                .as("removed issue is gone from the index")
                .isEmpty();
        assertThat(AgentReads.searchAll(third, List.of("epsilon")))
                .as("added issue is found")
                .containsExactly(
                        new AgentReads.Match(
                                "AB4",
                                AgentReads.Kind.ISSUE,
                                "Fourth",
                                "Backlog",
                                List.of("covers")));
    }

    /** Guards bound, {@code more}, ordering and duplicate-ID issues across updates. */
    @Test
    void indexPreservesBoundMoreAndDuplicateIdIssues() {
        // Harm: duplicate IDs overwrite each other in the cache, or the 200-match bound drifts.
        final List<Issue> many = new ArrayList<>();
        for (int i = 1; i <= AgentReads.MOST_MATCHES + 1; i++) {
            many.add(issue("AB" + i, "example-lib", "Common", "Backlog", ""));
        }
        final SearchIndex manyIndex =
                SearchIndex.of(
                        new Board(
                                new Revision("r1"),
                                WORKFLOW,
                                List.of(),
                                many,
                                new Work(Map.of(), List.of())),
                        LINES);
        assertThat(AgentReads.searchAll(manyIndex, List.of("common")))
                .as("unbounded search reaches every match in order")
                .hasSize(AgentReads.MOST_MATCHES + 1);
        final AgentReads.Found found = AgentReads.search(manyIndex, List.of("common"));
        assertThat(found.matches()).as("bounded to MOST_MATCHES").hasSize(AgentReads.MOST_MATCHES);
        assertThat(found.more()).as("more is true beyond MOST_MATCHES").isTrue();
        assertThat(found.matches().getFirst().id()).as("board order kept").isEqualTo("AB1");
        final SearchIndex exactIndex =
                SearchIndex.of(
                        new Board(
                                new Revision("r1"),
                                WORKFLOW,
                                List.of(),
                                many.subList(0, AgentReads.MOST_MATCHES),
                                new Work(Map.of(), List.of())),
                        LINES);
        assertThat(AgentReads.search(exactIndex, List.of("common")).more())
                .as("exactly MOST_MATCHES leaves nothing more")
                .isFalse();

        final AtomicInteger splits = new AtomicInteger();
        final AgentReads.Splitter counting =
                text -> {
                    splits.incrementAndGet();
                    return LINES.split(text);
                };

        final Issue left = twin("AB1", "repo-a", "plan: leftword");
        final Issue right = twin("AB1", "repo-b", "plan: rightword");
        final Board board1 =
                new Board(
                        new Revision("r1"),
                        WORKFLOW,
                        List.of(),
                        List.of(left, right),
                        new Work(Map.of(), List.of()));
        final SearchIndex first = SearchIndex.of(board1, counting);
        assertThat(splits.get()).isEqualTo(2);
        assertThat(AgentReads.searchAll(first, List.of("leftword"))).hasSize(1);
        assertThat(AgentReads.searchAll(first, List.of("rightword"))).hasSize(1);

        splits.set(0);
        final Issue rightChanged = twin("AB1", "repo-b", "plan: updated");
        final Board board2 =
                new Board(
                        new Revision("r2"),
                        WORKFLOW,
                        List.of(),
                        List.of(left, rightChanged),
                        new Work(Map.of(), List.of()));
        final SearchIndex second = first.update(board2, counting);
        assertThat(splits.get())
                .as("only the changed copy of the duplicate id is split")
                .isEqualTo(1);
        assertThat(AgentReads.searchAll(second, List.of("leftword"))).hasSize(1);
        assertThat(AgentReads.searchAll(second, List.of("updated"))).hasSize(1);
    }

    private static List<AgentReads.Match> regexSearchAll(
            final Board board, final List<String> words, final AgentReads.Splitter splitter) {
        final List<Pattern> patterns =
                words.stream()
                        .map(
                                word ->
                                        Pattern.compile(
                                                "(?<![\\p{L}\\p{N}_])"
                                                        + Pattern.quote(word.strip())
                                                        + "(?![\\p{L}\\p{N}_])",
                                                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                        .toList();
        final List<AgentReads.Match> all = new ArrayList<>();
        for (final Story story : board.stories()) {
            final Map<String, String> places = new LinkedHashMap<>();
            places.put(AgentReads.TITLE, story.title());
            places.put(AgentReads.TEXT, story.description());
            regexMatch(
                            story.id(),
                            AgentReads.Kind.STORY,
                            story.title(),
                            story.state(),
                            places,
                            patterns,
                            null)
                    .ifPresent(all::add);
        }
        for (final Issue issue : board.issues()) {
            final Map<String, String> places = new LinkedHashMap<>();
            places.put(AgentReads.TITLE, issue.title());
            final @Nullable Map<String, String> parts = splitter.split(issue.text()).parts();
            if (parts != null) {
                places.putAll(parts);
            }
            regexMatch(
                            issue.id(),
                            AgentReads.Kind.ISSUE,
                            issue.title(),
                            issue.state(),
                            places,
                            patterns,
                            issue.text())
                    .ifPresent(all::add);
        }
        return List.copyOf(all);
    }

    private static Optional<AgentReads.Match> regexMatch(
            final String id,
            final AgentReads.Kind kind,
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
                found.add(AgentReads.TEXT);
                held = true;
            }
            if (!held) {
                return Optional.empty();
            }
        }
        final List<String> ordered =
                new ArrayList<>(places.keySet().stream().filter(found::contains).toList());
        if (found.contains(AgentReads.TEXT) && !ordered.contains(AgentReads.TEXT)) {
            ordered.add(AgentReads.TEXT);
        }
        return Optional.of(new AgentReads.Match(id, kind, title, state, ordered));
    }

    private static Issue issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final String text) {
        return new Issue(
                id,
                repository,
                title,
                state,
                "S",
                List.of(),
                null,
                null,
                text,
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                "High",
                List.of(),
                List.of());
    }

    private static Issue twin(final String id, final String repository, final String text) {
        return new Issue(
                id,
                repository,
                "Twin " + repository,
                "Backlog",
                "S",
                List.of(),
                null,
                null,
                text,
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                null,
                List.of(Warning.idTwice()),
                List.of());
    }
}

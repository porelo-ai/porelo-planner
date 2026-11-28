package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The reads an agent asks of the board, proven over a board built in memory. */
class AgentReadsTest {

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

    /** Guards complete traversal without splitting stories. */
    @Test
    void pagesReachEveryWholeStoryAndLooseIssueExactlyOnce() {
        // Harm: a board read silently drops or duplicates items at page boundaries.
        final Board board =
                board(
                        List.of(
                                story("ST1", "First", "Backlog", "High", "AB1", "AB2"),
                                story("ST2", "Second", "Idea", "Low")),
                        issue("AB1", "One", "Backlog", "S", "High", ""),
                        issue("AB2", "Two", "Review", null, null, ""),
                        issue("AB3", "Loose", "Idea", "M", "Low", ""));
        final AgentReads.Outline outline = AgentReads.outline(board);
        final List<Object> items = new ArrayList<>(outline.stories());
        items.addAll(outline.issues());
        final List<Object> read = new ArrayList<>();
        @Nullable String after = null;
        do {
            final AgentReads.Page<Object> page =
                    AgentReads.page(
                            board.revision().value(), "outline", items, 1, after, value -> 100);
            read.addAll(page.items());
            after = page.next();
        } while (after != null);
        assertThat(read)
                .as("every top-level item, with the first story's two issues intact")
                .containsExactlyElementsOf(items);
        assertThat(((AgentReads.OutlineStory) read.getFirst()).issues())
                .as("a story never splits between pages")
                .hasSize(2);
    }

    /** Guards the answer bound and progress for an indivisible large item. */
    @Test
    void aPageEndsBeforeTheCompleteAnswerExceedsTheCharacterBound() {
        // Harm: a count-only page still exceeds the tool client's answer limit.
        final List<String> items = List.of("a", "b", "c");
        final AgentReads.Page<String> page =
                AgentReads.page(
                        "r1",
                        "stories",
                        items,
                        200,
                        null,
                        value -> 100 + value.items().size() * 30000);
        assertThat(page.items()).as("the envelope also consumes characters").containsExactly("a");
        assertThat(page.next()).as("the excluded items remain reachable").isNotNull();
        assertThat(
                        AgentReads.page(
                                        "r1",
                                        "stories",
                                        items,
                                        200,
                                        null,
                                        value -> 100 + value.items().size() * 70000)
                                .items())
                .as("an oversized indivisible item still makes progress")
                .containsExactly("a");
    }

    /** Guards oversized mandatory metadata, including an empty selection. */
    @Test
    void unavoidableMetadataCannotBypassThePageBoundEvenForAnEmptySelection() {
        // Harm: an unmatched filter returns oversized whole-board counts with no item to page.
        for (final List<String> items : List.of(List.<String>of(), List.of("one"))) {
            assertThatThrownBy(
                            () -> AgentReads.page("r1", "stories", items, 1, null, page -> 60001))
                    .as("metadata must fit even before items are added")
                    .isInstanceOf(WorkRefusal.class)
                    .extracting(e -> ((WorkRefusal) e).name())
                    .isEqualTo("PageTooLarge");
        }
        final AgentReads.Page<String> empty =
                AgentReads.page("r1", "stories", List.of(), 1, null, page -> 100);
        assertThat(empty.items()).as("an empty selection still has a terminal page").isEmpty();
        assertThat(empty.next()).as("no page follows an empty selection").isNull();
    }

    /** Guards continuation validation and revision consistency. */
    @Test
    void cursorsRefuseAnotherRevisionOrSelectionAndMalformedPositions() {
        // Harm: pages of different boards or selections are mixed into one board.
        final List<String> items = List.of("a", "b", "c");
        final @Nullable String next =
                AgentReads.page("r1", "stories:Backlog", items, 1, null, p -> 1).next();
        assertThatThrownBy(() -> AgentReads.page("r2", "stories:Backlog", items, 1, next, p -> 1))
                .as("a stale board must be restarted")
                .isInstanceOf(WorkRefusal.class)
                .extracting(e -> ((WorkRefusal) e).name())
                .isEqualTo("PageChanged");
        for (final String scope : List.of("outline:Backlog", "stories:Review")) {
            assertThatThrownBy(() -> AgentReads.page("r1", scope, items, 1, next, p -> 1))
                    .as("the cursor belongs to its original selection")
                    .isInstanceOf(WorkRefusal.class)
                    .extracting(e -> ((WorkRefusal) e).name())
                    .isEqualTo("InvalidName");
        }
        for (final String bad : List.of("", "not-a-cursor", "%%%", "A".repeat(5000))) {
            assertThatThrownBy(
                            () -> AgentReads.page("r1", "stories:Backlog", items, 1, bad, p -> 1))
                    .as("malformed cursors fail closed")
                    .isInstanceOf(WorkRefusal.class);
        }
        assertThatThrownBy(
                        () -> AgentReads.page("r1", "stories:Backlog", List.of(), 1, next, p -> 1))
                .as("an offset outside the selection is invalid")
                .isInstanceOf(WorkRefusal.class);
        for (final int limit : List.of(0, -1, 201)) {
            assertThatThrownBy(() -> AgentReads.page("r1", "stories", items, limit, null, p -> 1))
                    .as("a page limit is bounded")
                    .isInstanceOf(WorkRefusal.class);
        }
    }

    /** Guards story selection without losing its issues. */
    @Test
    void filtersSelectAStoryByItsOwnStateAndPriorityAndAnyIssuesRepository() {
        // Harm: filtering a story loses issues needed to plan its work.
        final Board board =
                board(
                        List.of(
                                story("ST1", "First", "Backlog", "High", "AB1", "AB2"),
                                story("ST2", "Second", "Idea", "Low")),
                        issue("AB1", "One", "Review", "S", "Low", ""),
                        issue("AB2", "Two", "Idea", "M", "High", ""));
        final AgentReads.Filters filters =
                new AgentReads.Filters(
                        List.of("Review", "Backlog"), List.of("High"), List.of("example-lib"));
        assertThat(board.stories().stream().filter(story -> filters.matches(board, story)).toList())
                .as("OR within a key, AND across keys, using the story's own fields")
                .containsExactly(board.stories().getFirst());
        assertThat(AgentReads.outline(board, filters).stories())
                .as("the selected story retains issues whose own state and priority do not match")
                .containsExactly(
                        new AgentReads.OutlineStory(
                                "ST1",
                                "First",
                                "Backlog",
                                "High",
                                List.of(
                                        new AgentReads.OutlineIssue(
                                                "AB1", "One", "Review", "S", "Low"),
                                        new AgentReads.OutlineIssue(
                                                "AB2", "Two", "Idea", "M", "High"))));
        assertThat(filters.matches(issue("AB3", "No priority", "Backlog", null, null, "")))
                .as("an issue without a priority does not match a priority filter")
                .isFalse();
        assertThat(
                        new AgentReads.Filters(List.of(), List.of(), List.of("other"))
                                .matches(board, board.stories().getFirst()))
                .as("the repository must occur")
                .isFalse();
    }

    /** Guards the loose issues kept or left out by each filter on its own. */
    @Test
    void aLooseIssueIsKeptOrLeftOutByItsOwnStatePriorityAndRepository() {
        // Harm: a filter hides a loose issue it matches or shows one it does not.
        final Board board =
                board(
                        List.of(story("ST1", "First", "Backlog", "High", "AB1")),
                        issue("AB1", "One", "Review", "S", "Low", ""),
                        issue("LO1", "example-lib", "Kept", "Review", "S", "High", ""),
                        issue("LO2", "other", "Left out", "Idea", "S", "Low", ""));
        final List<AgentReads.Filters> filters =
                List.of(
                        new AgentReads.Filters(List.of("Review"), List.of(), List.of()),
                        new AgentReads.Filters(List.of(), List.of("High"), List.of()),
                        new AgentReads.Filters(List.of(), List.of(), List.of("example-lib")));
        for (final AgentReads.Filters filter : filters) {
            assertThat(AgentReads.outline(board, filter).issues())
                    .as("the loose issues %s selects", filter)
                    .containsExactly(
                            new AgentReads.OutlineIssue("LO1", "Kept", "Review", "S", "High"));
        }
    }

    @Test
    void theOutlineHoldsWhatTheBoardSaysOfEachItemInTheBoardsOrder() {
        // Harm: an agent plans from a shorter view that disagrees with the board.
        final Board board =
                board(
                        List.of(
                                story("ST1", "First", "Backlog", "High", "AB1", "AB2"),
                                story("ST2", "Second", "Idea", "Low")),
                        issue("AB1", "One", "Backlog", "S", "High", ""),
                        issue("AB2", "Two", "Review", null, null, ""),
                        sub("AB2.1", "AB2", "Backlog"),
                        issue("AB3", "Three", "Idea", "M", "Low", ""));
        final AgentReads.Outline outline = AgentReads.outline(board);
        assertThat(outline.stories())
                .as("each story with its id, title, state and priority, in the board's order")
                .extracting(
                        AgentReads.OutlineStory::id,
                        AgentReads.OutlineStory::title,
                        AgentReads.OutlineStory::state,
                        AgentReads.OutlineStory::priority)
                .containsExactly(
                        tuple("ST1", "First", "Backlog", "High"),
                        tuple("ST2", "Second", "Idea", "Low"));
        assertThat(outline.stories().getFirst().issues())
                .as("the issues ST1 names, with their state, size and priority")
                .containsExactly(
                        new AgentReads.OutlineIssue("AB1", "One", "Backlog", "S", "High"),
                        new AgentReads.OutlineIssue("AB2", "Two", "Review", null, null));
        assertThat(outline.stories().get(1).issues()).as("ST2 names none").isEmpty();
        assertThat(outline.issues())
                .as("only the issue no story names and no named issue holds as its sub-issue")
                .containsExactly(new AgentReads.OutlineIssue("AB3", "Three", "Idea", "M", "Low"));
    }

    @Test
    void anIssuesPartsAreItsTextSplitAndTheStoryThatNamesIt() {
        final Board board =
                board(
                        List.of(story("ST1", "First", "Backlog", "High", "AB1")),
                        issue("AB1", "One", "Backlog", "S", "High", "covers: A\nplan: B"));
        final AgentReads.IssueParts read = AgentReads.parts(board, "AB1", LINES);
        assertThat(read.issue()).as("the board's issue").isSameAs(board.issues().getFirst());
        assertThat(read.story()).as("the story that names it").isEqualTo("ST1");
        assertThat(read.split().parts())
                .as("the parts the text holds, none for the others")
                .containsExactly(Map.entry("covers", "A"), Map.entry("plan", "B"));
    }

    @Test
    void aTextWhoseSectionsCannotBeToldApartHasNoPartsAndItsFault() {
        final Board board = board(List.of(), issue("AB1", "One", "Backlog", "S", "High", "!x"));
        final AgentReads.Split split = AgentReads.parts(board, "AB1", LINES).split();
        assertThat(split.parts()).as("no parts").isNull();
        assertThat(split.fault()).as("what to repair").isEqualTo("Line 1 is a heading.");
        assertThat(AgentReads.parts(board, "AB1", LINES).story())
                .as("an issue no story names")
                .isNull();
    }

    @Test
    void anIssueTheBoardLacksOrHoldsTwiceIsNoSuchIssue() {
        final Board twice = board(List.of(), twin("AB1", "One"), twin("AB1", "Again"));
        assertThatThrownBy(() -> AgentReads.parts(twice, "AB9", LINES))
                .as("an id the board lacks")
                .isInstanceOf(StoreException.NoSuchIssue.class);
        assertThatThrownBy(() -> AgentReads.parts(twice, "AB1", LINES))
                .as("an id two issues share")
                .isInstanceOf(StoreException.NoSuchIssue.class);
    }

    @Test
    void theBreachesAreTheBoardsRulesGroupedWithTheirIssues() {
        // Harm: a breach shown differently from the board's own, or lost with its issues.
        final Board board =
                board(
                        List.of(story("ST1", "First", "Backlog", "Low", "AB1")),
                        issue("AB1", "One", "Backlog", "S", "High", ""),
                        issue("AB2", "Two", "Implementing", "S", null, ""),
                        issue("AB3", "Three", "Review", "S", null, ""));
        final List<AgentReads.Breach> breaches = AgentReads.breaches(board);
        assertThat(breaches)
                .as("the story below its issue, and every issue in no story in one group")
                .extracting(
                        AgentReads.Breach::rule,
                        AgentReads.Breach::story,
                        AgentReads.Breach::issues)
                .containsExactly(
                        tuple(StoryRules.Rule.STORY_PRIORITY_BELOW_ISSUE, "ST1", List.of("AB1")),
                        tuple(StoryRules.Rule.ISSUE_IN_NO_STORY, null, List.of("AB2", "AB3")));
        assertThat(breaches.stream().map(AgentReads.Breach::words).toList())
                .as("the words the board's rules gave, once for each breach")
                .containsExactly(
                        StoryRules.of(board).get(0).words(),
                        StoryRules.of(board).get(1).words()
                                + " "
                                + StoryRules.of(board).get(2).words());
    }

    @Test
    void aBoardThatBreaksNoRuleListsNoBreach() {
        final Board board =
                board(
                        List.of(story("ST1", "First", "Backlog", "High", "AB1")),
                        issue("AB1", "One", "Backlog", "S", "High", ""));
        assertThat(AgentReads.breaches(board)).as("no breach").isEmpty();
    }

    @Test
    void aSearchMatchesAWholeWordInATitleAndInATextPartWithoutRegardToCase() {
        final Board board =
                board(
                        List.of(story("ST1", "Faster Search", "Backlog", "High", "AB1")),
                        issue("AB1", "Index the cache", "Backlog", "S", "High", "plan: Warm CACHE"),
                        issue("AB2", "Other", "Backlog", "S", "High", "covers: caches"));
        assertThat(AgentReads.search(board, List.of("cache"), LINES).matches())
                .as("a title word and a part word match, a longer word does not")
                .containsExactly(
                        new AgentReads.Match(
                                "AB1",
                                AgentReads.Kind.ISSUE,
                                "Index the cache",
                                "Backlog",
                                List.of("title", "plan")));
        assertThat(AgentReads.search(board, List.of("search"), LINES).matches())
                .as("a story's title")
                .extracting(AgentReads.Match::id, AgentReads.Match::kind)
                .containsExactly(tuple("ST1", AgentReads.Kind.STORY));
        assertThat(AgentReads.search(board, List.of("missing"), LINES).matches())
                .as("a word nothing holds")
                .isEmpty();
    }

    @Test
    void aSearchNeedsEveryWordAndFindsAStorysTextAndAnUnsplitTextAsText() {
        final Story story =
                new Story("ST1", "Plain", "Backlog", "High", null, List.of(), "Holds gamma.", null);
        final Board board =
                new Board(
                        new Revision("r1"),
                        WORKFLOW,
                        List.of(story),
                        List.of(issue("AB1", "Alpha", "Backlog", "S", "High", "!gamma beta")),
                        new Work(Map.of(), List.of()));
        assertThat(AgentReads.search(board, List.of("alpha", "beta"), LINES).matches())
                .as("one word in the title, one in a text that cannot be split")
                .extracting(AgentReads.Match::id, AgentReads.Match::found)
                .containsExactly(tuple("AB1", List.of("title", "text")));
        assertThat(AgentReads.search(board, List.of("alpha", "delta"), LINES).matches())
                .as("a word no place holds")
                .isEmpty();
        assertThat(AgentReads.search(board, List.of("gamma"), LINES).matches())
                .as("the story's text, then the issue's, stories first")
                .extracting(AgentReads.Match::id)
                .containsExactly("ST1", "AB1");
    }

    @Test
    void aSearchFindsAWordOutsideEveryPartOfASplitTextAsText() {
        // Harm: a word in a section of the person's own is never found, though the text holds it.
        final Board board =
                board(
                        List.of(),
                        issue("AB1", "One", "Backlog", "S", "High", "plan: Warm\nNotes on latency"),
                        issue("AB2", "Two", "Backlog", "S", "High", "plan: latency"));
        assertThat(AgentReads.search(board, List.of("latency"), LINES).matches())
                .as("the word in no part as text, the word in a part as that part alone")
                .extracting(AgentReads.Match::id, AgentReads.Match::found)
                .containsExactly(tuple("AB1", List.of("text")), tuple("AB2", List.of("plan")));
    }

    @Test
    void aSearchAnswersAtMostTwoHundredAndSaysThatOthersMatched() {
        final List<Issue> issues = new ArrayList<>();
        for (int i = 1; i <= 201; i++) {
            issues.add(issue("AB" + i, "Common", "Backlog", "S", "High", ""));
        }
        final Board many =
                new Board(
                        new Revision("r1"),
                        WORKFLOW,
                        List.of(),
                        issues,
                        new Work(Map.of(), List.of()));
        assertThat(AgentReads.searchAll(many, List.of("common"), LINES))
                .as("paging can still reach matches beyond the legacy first two hundred")
                .hasSize(201);
        final AgentReads.Found found = AgentReads.search(many, List.of("common"), LINES);
        assertThat(found.matches()).as("the bound").hasSize(AgentReads.MOST_MATCHES);
        assertThat(found.more()).as("one more matched").isTrue();
        assertThat(AgentReads.search(many, List.of("common"), LINES).matches().getFirst().id())
                .as("in the board's order")
                .isEqualTo("AB1");
        assertThat(
                        AgentReads.search(
                                        new Board(
                                                new Revision("r1"),
                                                WORKFLOW,
                                                List.of(),
                                                issues.subList(0, 200),
                                                new Work(Map.of(), List.of())),
                                        List.of("common"),
                                        LINES)
                                .more())
                .as("exactly 200 leave nothing more")
                .isFalse();
    }

    private static Board board(final List<Story> stories, final Issue... issues) {
        return new Board(
                new Revision("r1"),
                WORKFLOW,
                stories,
                List.of(issues),
                new Work(Map.of(), List.of()));
    }

    private static Story story(
            final String id,
            final String title,
            final String state,
            final String priority,
            final String... issues) {
        return new Story(id, title, state, priority, null, List.of(issues), "", null);
    }

    private static Issue issue(
            final String id,
            final String title,
            final String state,
            final @Nullable String size,
            final @Nullable String priority,
            final String text) {
        return issue(id, "example-lib", title, state, size, priority, text);
    }

    private static Issue issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final @Nullable String size,
            final @Nullable String priority,
            final String text) {
        return new Issue(
                id,
                repository,
                title,
                state,
                size,
                List.of(),
                null,
                null,
                text,
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                priority,
                List.of(),
                List.of());
    }

    private static Issue twin(final String id, final String title) {
        return new Issue(
                id,
                "example-lib",
                title,
                "Backlog",
                "S",
                List.of(),
                null,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                null,
                List.of(Warning.idTwice()),
                List.of());
    }

    private static Issue sub(final String id, final String parent, final String state) {
        return new Issue(
                id,
                "example-lib",
                "Sub",
                state,
                "S",
                List.of(),
                parent,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                null,
                List.of(),
                List.of());
    }
}

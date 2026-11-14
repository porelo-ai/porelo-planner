package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How the pages under {@code issues/} cite an issue, read as the Git store reads them. */
class IssueLinksTest {

    private static final String FRONTEND =
            "https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md";

    private static final String TWO_ROWS =
            "## Idea\n| <a id=\"b10\"></a>[B10](B10-A-Thing.md) | a thing |\n"
                    + "| <a id=\"b11\"></a>[B11](B11-Another.md) | another |\n";

    private final Map<String, String> pages = new LinkedHashMap<>();

    private final Map<String, String> elsewhere = new LinkedHashMap<>();

    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void aRepositoryWithTwoIssues() {
        pages.put("issues/README.md", TWO_ROWS);
        pages.put("issues/B10-A-Thing.md", "# B10 - A Thing\n");
        pages.put("issues/B11-Another.md", "# B11 - Another\n");
    }

    private List<String> check() throws Exception {
        final Workflow workflow = Workflow.read(Path.of("src/test/resources/workflow.yml"));
        final Map<String, Map<String, IssueIndex>> indexes = new LinkedHashMap<>();
        for (final Map.Entry<String, String> page : pages.entrySet()) {
            if (page.getKey().endsWith("/README.md")) {
                indexes.put(
                        page.getKey(),
                        IssueIndexes.read(page.getValue(), "porelo-sample", workflow));
            }
        }
        return IssueLinks.check(
                pages,
                indexes,
                path -> {
                    asked.add(path);
                    return Optional.ofNullable(elsewhere.get(path));
                });
    }

    @Test
    void acceptsIssuesCitingEachOtherByALinkToTheRow() throws Exception {
        pages.put("issues/B11-Another.md", "Needs **[B10](README.md#b10)**.\n");
        assertThat(check()).as("a link to the row").isEmpty();
    }

    @Test
    void proseExamplesSupplyNeitherRowsNorIssueAnchors() throws Exception {
        // Prose examples must not invent durable issue rows or demand row anchors.
        pages.put(
                "issues/README.md",
                TWO_ROWS
                        + "\n## Examples\n| B99 | An unanchored prose example |\n"
                        + "| <a id=\"b98\"></a>B98 | Another prose example |\n");
        pages.put("issues/B10-A-Thing.md", "After [B98](README.md#b98).\n");
        assertThat(check())
                .as("the link into a prose example reaches no row, and the example is no row")
                .contains("issues/B10-A-Thing.md:1: README.md#b98 reaches no row's anchor")
                .noneMatch(fault -> fault.contains("does not open with its anchor"));
    }

    @Test
    void acceptsTheIndexNamingIssueFilesAndAnIssueItsDesign() throws Exception {
        // The index names files by being one; a design is deleted together with its issue.
        pages.put(
                "issues/B10-A-Thing.md",
                "Needs [B11](README.md#b11) and [its design](../issues/B11_design.md).\n");
        assertThat(check()).as("the index's own links and a design").isEmpty();
    }

    @Test
    void refusesANumberOfThisRepositorysOwnAmongTheIssuesThatNamesNoIssue() throws Exception {
        // The index row or the "blocked by" its deletion missed.
        pages.put(
                "issues/README.md",
                "## Idea\n| <a id=\"b10\"></a>[B10](B10-A-Thing.md) | B12 | waits on the"
                        + " interface's consent |\n");
        assertThat(check())
                .as("an own number with neither file nor row")
                .containsExactly(
                        "issues/README.md:2: B12 names no issue of this repository, a mention its"
                                + " deletion missed");
    }

    @Test
    void refusesAnIndexRowWithoutItsAnchor() throws Exception {
        pages.put(
                "issues/README.md",
                "## Idea\n| <a id=\"b10\"></a>[B10](B10-A-Thing.md) | a thing |\n"
                        + "| [B11](B11-Another.md) | another |\n"
                        + "| <a id=\"b10\"></a>B12 | finished, named wrong |\n");
        assertThat(check())
                .as("a row without an anchor and a row under another number's anchor")
                .contains(
                        "issues/README.md:3: the row of B11 does not open with its anchor"
                                + " <a id=\"b11\"></a>",
                        "issues/README.md:4: the row of B12 does not open with its anchor"
                                + " <a id=\"b12\"></a>");
    }

    @Test
    void refusesANumberAmongTheIssuesThatIsNotALinkToItsRow() throws Exception {
        pages.put(
                "issues/B11-Another.md",
                "Follows B10.\nAnd [B10](B10-A-Thing.md#why), and [B10](README.md).\n");
        assertThat(check())
                .as("a bare number, a link to the file and a link to the index without its row")
                .containsExactly(
                        "issues/B11-Another.md:1: B10 is not a link to its index row",
                        "issues/B11-Another.md:2: B10 links to B10-A-Thing.md#why, not to its"
                                + " index row",
                        "issues/B11-Another.md:2: B10 links to README.md, not to its index row");
    }

    @Test
    void refusesALinkToARowThatIsNotThere() throws Exception {
        pages.put(
                "issues/B11-Another.md",
                "See [B10](README.md#b11) and [the other](README.md#b12).\n");
        assertThat(check())
                .as("a number linked to another's row, and a link to a row that is not there")
                .containsExactly(
                        "issues/B11-Another.md:1: B10 links to README.md#b11, another number's"
                                + " row",
                        "issues/B11-Another.md:1: README.md#b12 reaches no row's anchor");
    }

    @Test
    void leavesHeadingsCodeAndAMarkedLineAlone() throws Exception {
        pages.put(
                "issues/B11-Another.md",
                "# B11 - Another\n\nThe form is `[B10](README.md#b10)`.\n\n```\nB10\n```\n"
                        + "B10 as the parser reads it - not-a-citation\n");
        assertThat(check()).as("a heading, a code span, a fence and a marked line").isEmpty();
    }

    @Test
    void acceptsALinkToARowOnTheSamePage() throws Exception {
        pages.put(
                "issues/README.md",
                "## Idea\n| <a id=\"b10\"></a>[B10](B10-A-Thing.md) | a thing |\n"
                        + "| <a id=\"b11\"></a>[B11](B11-Another.md) | after [B10](#b10), not"
                        + " [B12](#b12) |\n");
        assertThat(check())
                .as("only the link to a row that is not there")
                .contains("issues/README.md:3: #b12 reaches no row's anchor")
                .noneMatch(fault -> fault.contains("#b10"));
    }

    @Test
    void acceptsAFinishedRowWhoseFileIsGone() throws Exception {
        pages.put(
                "issues/README.md",
                TWO_ROWS
                        + "\n## Finished\n\n| <a id=\"b09\"></a>B09 | Built: the first thing. |\n");
        pages.put("issues/B11-Another.md", "After [B09](README.md#b09).\n");
        assertThat(check()).as("the row outlives the file").isEmpty();
    }

    @Test
    void readsNothingOutsideItsOwnRepository() throws Exception {
        // Another repository's state must not decide this one's faults, nor be fetched for them.
        pages.put(
                "issues/B11-Another.md",
                "Its interface is [F31]("
                        + FRONTEND
                        + "#f31), not [F32]("
                        + FRONTEND
                        + "#f32).\nIt waits for F31 and PJ12, after [B10](README.md#b10).\n");
        assertThat(check()).as("links into another repository's index are left alone").isEmpty();
        assertThat(asked).as("no page read beyond the reading's").isEmpty();
    }

    @Test
    void refusesANumberOfAPrefixOnlyFinishedRowsCarryThatHasNoRow() throws Exception {
        // A number no row names cannot be linked, and would send a reader nowhere.
        pages.put(
                "issues/README.md",
                TWO_ROWS + "\n## Finished\n\n| <a id=\"xy05\"></a>XY05 | done |\n");
        pages.put("issues/B11-Another.md", "After [XY05](README.md#xy05), and XY06.\n");
        assertThat(check())
                .as("the number without a row, not the one linked to its row")
                .containsExactly(
                        "issues/B11-Another.md:1: XY06 has no row in an index, so it cannot be"
                                + " linked");
    }

    @Test
    void stillRefusesABrokenLinkWithinItsOwnIndexes() throws Exception {
        pages.put("issues/B11-Another.md", "After [B10](README.md#b12) and B10.\n");
        assertThat(check())
                .as("the link to another row and the bare number")
                .containsExactly(
                        "issues/B11-Another.md:1: B10 links to README.md#b12, another number's"
                                + " row",
                        "issues/B11-Another.md:1: B10 is not a link to its index row");
    }

    @Test
    void acceptsALinkNamedLikeAnIssueThatIsNone() throws Exception {
        pages.put("issues/B11-Another.md", "Its digest is [S256](https://example.org/digest).\n");
        assertThat(check()).as("a name of an issue number's shape that is none").isEmpty();
    }

    @Test
    void aPageNoReadingFetchedIsReadOnceWhenLinksNameIt() throws Exception {
        // Reading every page under issues/ on every board would cost a read each; only a named one
        // is read.
        elsewhere.put("issues/notes.md", "<a id=\"b10\"></a>The why.\n");
        pages.put(
                "issues/B11-Another.md",
                "See [the why](notes.md#b10), [again](notes.md#b10) and [gone](nowhere.md#b10).\n");
        assertThat(check())
                .as("the link to a page that is not there")
                .containsExactly("issues/B11-Another.md:1: nowhere.md#b10 reaches no row's anchor");
        assertThat(asked)
                .as("each named page read once")
                .containsExactly("issues/notes.md", "issues/nowhere.md");
    }

    @Test
    void aLinkToAStoryRowIsReadAgainstTheStoriesIndex() throws Exception {
        // A row that stands must not be named as missing, and one that does not must be.
        elsewhere.put(
                "stories/README.md",
                "| <a id=\"st5\"></a>[ST5](ST5-A.md) |\n| <a id=\"st11\"></a>[ST11](ST11-B.md) |\n");
        pages.put(
                "issues/B11-Another.md",
                "For [ST5](../stories/README.md#st5), [ST11](../stories/README.md#st11),"
                        + " not [ST12](../stories/README.md#st12) or [ST6](../stories/README.md#st6).\n");
        assertThat(check())
                .as("only the links to rows that do not stand, one digit and two")
                .containsExactly(
                        "issues/B11-Another.md:1: ../stories/README.md#st12 reaches no row's anchor",
                        "issues/B11-Another.md:1: ../stories/README.md#st6 reaches no row's anchor");
        assertThat(asked).as("the stories index read once").containsExactly("stories/README.md");
    }

    @Test
    void aPageOutsideTheIssuesAndStoriesIsNeverFetched() throws Exception {
        // Each such page would cost a Git process of its own, as many as one issue cares to link.
        elsewhere.put("doc/a.md", "<a id=\"st5\"></a>\n");
        pages.put("issues/B11-Another.md", "See [a](../doc/a.md#st5) and [b](../doc/b.md#b10).\n");
        assertThat(check())
                .as("no row stands outside the issues and stories, so neither link reaches one")
                .containsExactly(
                        "issues/B11-Another.md:1: ../doc/a.md#st5 reaches no row's anchor",
                        "issues/B11-Another.md:1: ../doc/b.md#b10 reaches no row's anchor");
        assertThat(asked).as("no page fetched outside the issues and stories").isEmpty();
    }

    @Test
    void aHeadingAnchorOfOneDigitIsNoRow() throws Exception {
        // A heading's own anchor has no <a id>, so taking it for a row would name a false finding.
        pages.put("issues/B11-Another.md", "See [v2](notes.md#v2) and [h1](#h1).\n");
        assertThat(check()).as("anchors of one digit that are no story's").isEmpty();
    }

    @Test
    void anIndexThatCannotBeReadLendsItsAnchorsAndIsNotReadForRows() throws Exception {
        // Its fault is named where the index is read; guessed rows would add faults of their own.
        pages.put("issues/README.md", TWO_ROWS);
        pages.put("issues/B11-Another.md", "After [B10](README.md#b10).\n");
        assertThat(IssueLinks.check(pages, Map.of(), path -> Optional.empty()))
                .as("no fault from the unread index, and the link reaches its anchor")
                .isEmpty();
    }
}

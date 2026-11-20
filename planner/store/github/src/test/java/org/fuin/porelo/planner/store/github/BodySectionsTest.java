package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** An issue's body on GitHub read as its parts, and a stage's result written into it. */
class BodySectionsTest {

    /** A made-up issue id. */
    private static final String ID = "AB01"; // not-a-citation

    private static final String BODY =
            "Something it covers.\n\n"
                    + "**What must be true.** Something.\n\n"
                    + "**Why this size.** A guess\nbefore it was read.\n\n"
                    + "## Why\n\nA reason.\n\n```\n## Plan\n```\n\n"
                    + "## Plan\n\n- Old plan.\n\n"
                    + "## Acceptance\n\n- Measured.\n\n"
                    + "## Open questions\n\n- None.\n\n"
                    + "## To be checked\n\n- Old check.\n";

    private static final String ANALYZED =
            "Something it covers.\n\n"
                    + "**What must be true.** Something.\n\n"
                    + "**Why this size.** one module\n\n"
                    + "## Why\n\nA reason.\n\n```\n## Plan\n```\n\n"
                    + "## Plan\n\n- Read it.\n\n```\n## a shell comment\n```\n\n"
                    + "## Acceptance\n\n- Measured.\n\n"
                    + "## Open questions\n\n- None.\n\n"
                    + "## To be checked\n\n- Measure it.\n\n"
                    + "## Findings\n\n- Found one.\n";

    @Test
    void aResultThatOverwritesThePlanLeavesAcceptanceAndEveryOtherByteAsItWas() {
        // Harm: a save nobody reviews losing an issue's acceptance or any other part of its body.
        assertThat(
                        BodySections.read(BODY)
                                .withResult(
                                        WorkRule.Result.ANALYSIS, analysis(List.of("Measure it.")))
                                .text())
                .as("the body with the result in it, every other byte as it was")
                .isEqualTo(ANALYZED);
        assertThat(
                        BodySections.read(BODY.replace("\n", "\r\n"))
                                .withResult(
                                        WorkRule.Result.ANALYSIS, analysis(List.of("Measure it.")))
                                .text())
                .as("a body GitHub keeps with CRLF keeps them in every line")
                .isEqualTo(ANALYZED.replace("\n", "\r\n"));
    }

    @Test
    void aHeadingInsideACodeBlockIsNoSection() {
        // Harm: a result written into a code block, or a code block read as the plan.
        final BodySections body = BodySections.read(BODY);

        assertThat(body.part(BodySections.Part.PLAN))
                .as("the plan below the code block, not the heading inside it")
                .contains("- Old plan.");
        assertThat(
                        BodySections.read("Covers.\n\n## Why\n\n~~~~\n## Acceptance\n~~~\n~~~~\n")
                                .part(BodySections.Part.ACCEPTANCE))
                .as("a heading inside a tilde fence, closed only by as long a fence")
                .isEmpty();
        assertThat(body.with(BodySections.Part.PLAN, "- New.").text())
                .as("the code block left as it was")
                .contains("```\n## Plan\n```\n\n## Plan\n\n- New.\n\n## Acceptance");
    }

    @Test
    void anEstimateLeavesWhatIsToBeCheckedAsItStands() {
        // Harm: an estimate dropping the checks an analysis left open, so they read as done.
        assertThat(
                        BodySections.read(BODY)
                                .withResult(WorkRule.Result.ESTIMATE, analysis(List.of()))
                                .part(BodySections.Part.TO_BE_CHECKED))
                .as("the open checks after an estimate")
                .contains("- Old check.");
        assertThat(
                        BodySections.read(BODY)
                                .withResult(WorkRule.Result.ANALYSIS, analysis(List.of()))
                                .text())
                .as("an analysis that finds nothing left to check removes the section")
                .doesNotContain("## To be checked")
                .endsWith("## Open questions\n\n- None.\n\n## Findings\n\n- Found one.\n");
    }

    @Test
    void aMissingPartIsAddedWhereTheSkeletonPutsItAndTheRestIsUntouched() {
        // Harm: a result dropped, or put where nobody reads it, in a body a person wrote sparsely.
        final String sparse =
                "Covers.\n\n**What must be true.** Something.\n\n"
                        + "## Acceptance\n\n- Measured.\n\n## Notes\n\nKept.";

        assertThat(
                        BodySections.read(sparse)
                                .withResult(WorkRule.Result.ANALYSIS, analysis(List.of()))
                                .text())
                .as("each part in its place")
                .isEqualTo(
                        "Covers.\n\n**Why this size.** one module\n\n"
                                + "**What must be true.** Something.\n\n"
                                + "## Plan\n\n- Read it.\n\n```\n## a shell comment\n```\n\n"
                                + "## Acceptance\n\n- Measured.\n\n"
                                + "## Findings\n\n- Found one.\n\n"
                                + "## Notes\n\nKept.");
    }

    @Test
    void aBodyLongerThanGitHubTakesIsRefusedWithoutEchoingIt() {
        // Harm: a body's text, which may hold anything a person pasted, leaking into a message.
        final String body = "covers-text-" + "x".repeat(BodySections.LONGEST);

        assertThatThrownBy(() -> BodySections.read(body))
                .as("an oversized body")
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain("covers-text-");
        final String near = "covers-text-" + "x".repeat(BodySections.LONGEST - 20);
        assertThatThrownBy(
                        () ->
                                BodySections.read(near)
                                        .with(BodySections.Part.PLAN, "- grown-plan-text"))
                .as("a write that grows the body past the limit")
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain("covers-text-")
                .doesNotContain("grown-plan-text");
    }

    @Test
    void aPartWhoseTextWouldOpenASectionOfItsOwnIsRefused() {
        // Harm: a written part splitting the body, so the next read finds sections nobody wrote.
        final BodySections body = BodySections.read(BODY);
        for (final String text : List.of("- a\n## Acceptance\n- forged", "```\nopen")) {
            assertThatThrownBy(() -> body.with(BodySections.Part.PLAN, text))
                    .as("the plan %s", text)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> body.with(BodySections.Part.WHY_THIS_SIZE, "one\n\ntwo"))
                .as("a lead paragraph that would end early")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inlineCodeAtALinesStartOpensNoCodeBlockAndHidesNoSectionAfterIt() {
        // Harm: a person's Acceptance and every section after the plan lost on a save, when the
        // plan holds inline code at a line's start.
        // The inline code is followed by a code block of its own, which a fence it opened would
        // close.
        final String plan = "```foo``` inline code";
        final String tail =
                "\n\n## Acceptance\n\n- Measured.\n\n## Notes\n\nKept.\n\n```\nkept\n```\n";
        final String body = "Covers.\n\n## Plan\n\n" + plan + tail;
        final BodySections read = BodySections.read(body);
        assertThat(read.part(BodySections.Part.ACCEPTANCE))
                .as("the acceptance after the inline code")
                .contains("- Measured.");
        assertThat(read.with(BodySections.Part.PLAN, "- New.").text())
                .as("the plan written over the inline code")
                .isEqualTo("Covers.\n\n## Plan\n\n- New." + tail);
        assertThat(read.with(BodySections.Part.ACCEPTANCE, "- Judged.").text())
                .as("the acceptance written after the inline code, in its own place")
                .isEqualTo(body.replace("- Measured.", "- Judged."));
    }

    @Test
    void aBodyWhoseSectionsCannotBeToldApartForCertainIsNeverWrittenAndSaysWhatToRepair() {
        // Harm: a write deletes a person's sections that a code block left open by hand, or a
        // repeated heading, makes the planner read as part of the section it writes.
        final String tail = "\n\n## Acceptance\n\n- secret-acceptance\n\n## Notes\n\nKept.\n";
        final Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put(
                "an unclosed fence followed by a closed block",
                "Covers.\n\n## Plan\n\n```\nsecret-left-open"
                        + tail
                        + "\n```java\nsecret-code\n```\n");
        bodies.put(
                "an unclosed fence followed by a closed bare block",
                "Covers.\n\n## Plan\n\n```\nsecret-left-open" + tail + "\n```\nsecret-code\n```\n");
        bodies.put(
                "a fence left open to the end",
                "Covers.\n\n## Plan\n\n~~~\nsecret-left-open" + tail);
        bodies.put(
                "a heading twice",
                "Covers.\n\n## Plan\n\n- secret-plan" + tail + "\n## Plan\n\n- secret-second\n");
        bodies.put(
                "a leading paragraph twice",
                "Covers.\n\n**Why this size.** secret-why\n\n**Why this size.** secret-again"
                        + tail);
        bodies.put(
                "a stray fence that pairs with a later bare block",
                "Covers.\n\n## Plan\n\n```\nsecret-old"
                        + tail
                        + "\n```\nsecret-code\n\n## Findings\n\n- secret-finding\n");
        for (final String shaped :
                List.of(
                        " ## secret-indented",
                        "##\tsecret-tab",
                        "##",
                        "## Plan ##",
                        "# secret-first-level",
                        "   # secret-indented-first",
                        "##  secret-two-spaces")) {
            bodies.put(
                    "a heading written as " + shaped.replace("secret", "s"),
                    "Covers.\n\n## Plan\n\n- secret-plan\n\n" + shaped + "\n\nsecret-mine" + tail);
        }
        bodies.put(
                "an HTML comment holding a heading",
                "Covers.\n\n<!--\n## Plan\n\nsecret-old\n-->\n\n## Plan\n\n- secret-plan" + tail);
        bodies.put(
                "an HTML comment holding a heading of a person's own",
                "Covers.\n\n## Plan\n\n- p\n\n<!-- secret-note\n\n## secret-mine\n\n-->" + tail);
        bodies.put(
                "an HTML comment never closed",
                "Covers.\n\n## Plan\n\n- p" + tail + "\n<!-- secret-note\n");
        bodies.put(
                "a pre block holding a heading",
                "Covers.\n\n## Plan\n\n<PRE>\n## secret-mine\n</pre>" + tail);
        bodies.put(
                "a details block holding a heading",
                "Covers.\n\n## Plan\n\n<details><summary>secret</summary>\n\n## secret-mine\n\n"
                        + "</details>"
                        + tail);
        for (final Map.Entry<String, String> each : bodies.entrySet()) {
            final BodySections read = BodySections.read(each.getValue());
            assertThat(read.ambiguity()).as("what to repair in %s", each.getKey()).isPresent();
            assertThat(read.ambiguity().orElseThrow())
                    .as("what to repair in %s, without the body's text", each.getKey())
                    .doesNotContain("secret");
            assertThatThrownBy(() -> read.with(BodySections.Part.PLAN, "- New."))
                    .as("the plan written into %s", each.getKey())
                    .isInstanceOf(BodySections.Ambiguous.class)
                    .message()
                    .doesNotContain("secret");
            assertThatThrownBy(
                            () ->
                                    read.withResult(
                                            WorkRule.Result.ANALYSIS, analysis(List.of("Check."))))
                    .as("a result written into %s", each.getKey())
                    .isInstanceOf(BodySections.Ambiguous.class);
            assertThatThrownBy(() -> read.without(BodySections.Part.TO_BE_CHECKED))
                    .as("a part removed from %s", each.getKey())
                    .isInstanceOf(BodySections.Ambiguous.class);
            assertThat(read.text())
                    .as("%s byte for byte", each.getKey())
                    .isEqualTo(each.getValue());
        }
        assertThat(BodySections.read(bodies.get("a fence left open to the end")).ambiguity())
                .as("the repair named for a fence left open")
                .contains("the code block opened in line 5 under Plan never closes");
        assertThat(BodySections.read(BODY).ambiguity())
                .as("a body whose code block holds a heading-shaped line and nothing more")
                .isEmpty();
        assertThat(
                        BodySections.read(
                                        bodies.get(
                                                "a stray fence that pairs with a later bare block"))
                                .ambiguity())
                .as("the repair named for a stray fence under a section of the skeleton")
                .hasValueSatisfying(
                        repair ->
                                assertThat(repair).as("the repair").contains("line 5 under Plan"));
    }

    @Test
    void aBodyThatFollowsTheSkeletonIsWrittenAndANewLeadTakesTheSkeletonsPlace() {
        // Harm: no result ever written into a body that follows the issue skeleton, or a lead
        // put where the skeleton does not put it.
        final String skeleton =
                "Covers.\n\n**Why this size.** A guess.\n\n**What must be true.** Something.\n\n"
                        + "## Plan\n\n- Old.\n";
        assertThat(BodySections.read(skeleton).ambiguity())
                .as("what to repair in a body in the skeleton's order")
                .isEmpty();
        assertThat(BodySections.read(skeleton).with(BodySections.Part.PLAN, "- New.").text())
                .as("the plan written into a body in the skeleton's order")
                .isEqualTo(skeleton.replace("- Old.", "- New."));
        assertThat(
                        BodySections.read(
                                        "Covers.\n\n**What must be true.** Something.\n\n"
                                                + "## Plan\n\n- Old.\n")
                                .with(BodySections.Part.WHY_THIS_SIZE, "one module")
                                .text())
                .as("why this size added before what must be true, as the skeleton puts it")
                .isEqualTo(
                        "Covers.\n\n**Why this size.** one module\n\n"
                                + "**What must be true.** Something.\n\n## Plan\n\n- Old.\n");
    }

    @Test
    void aTabIndentedFenceOpensNoCodeBlock() {
        // Harm: a person's section deleted on a save, after a fence indented by a tab, which
        // Markdown reads as indented code, pairs with a later fence.
        final String body =
                "Covers.\n\n## Plan\n\nold\n\n\t```\n\n## Notes\n\nsecret-mine\n\n"
                        + "```\ncode\n```\n";
        assertThat(BodySections.read(body).with(BodySections.Part.PLAN, "- New.").text())
                .as("the plan written over a tab-indented fence, the person's section kept")
                .isEqualTo(body.replace("old\n\n\t```", "- New."));
        final String lone = "Covers.\n\n## Plan\n\nold\n\n\t```\n\n## Notes\n\nsecret\n\n```\n";
        assertThat(BodySections.read(lone).ambiguity())
                .as("what to repair when a tab-indented fence would pair with a lone later one")
                .hasValueSatisfying(
                        repair ->
                                assertThat(repair)
                                        .as("the repair")
                                        .contains("line 13 never closes"));
    }

    @Test
    void headingsOfThirdLevelAndHashesWithoutASpaceAreNoHeadingsToRepair() {
        // Harm: a body refused for a subsection or a hashtag, so no result is ever written.
        final String body =
                "Covers.\n\n## Plan\n\n- Old.\n\n### A step\n\n#hashtag\n\n## Acceptance\n\n- A\n";
        assertThat(BodySections.read(body).with(BodySections.Part.PLAN, "- New.").text())
                .as("the plan written over its subsection, the acceptance kept")
                .isEqualTo("Covers.\n\n## Plan\n\n- New.\n\n## Acceptance\n\n- A\n");
        assertThatThrownBy(() -> BodySections.read(body).with(BodySections.Part.PLAN, "- a\n# b"))
                .as("a plan holding a first-level heading")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClosedHtmlCommentWithoutAHeadingIsWrittenAround() {
        // Harm: a body refused for a comment a template leaves, so no result is ever written.
        final String body =
                "Covers.\n\n<!-- one line -->\n\n## Plan\n\n- Old.\n\n<!--\nmine\n-->\n"
                        + "<pre>\nx\n</pre>\n\n## Acceptance\n\n- A\n";
        assertThat(BodySections.read(body).ambiguity())
                .as("what to repair in a body whose comments close")
                .isEmpty();
    }

    @Test
    void aNestedFenceOfAnotherKindOrShorterIsNoSignOfAStrayFence() {
        // Harm: a body refused for a code block that shows a code block, so no result is ever
        // written, the planner's own plan included.
        final String body =
                "Covers.\n\n## Plan\n\n````\n## x\n```\ninner\n```\n````\n\n"
                        + "~~~\n## y\n```bash\n~~~\n\n## Acceptance\n\n- A\n";
        assertThat(BodySections.read(body).ambiguity())
                .as("what to repair in a body with nested fences")
                .isEmpty();
        assertThat(
                        BodySections.read(body)
                                .with(BodySections.Part.PLAN, "````\n## z\n```\nin\n```\n````")
                                .with(BodySections.Part.PLAN, "- New.")
                                .text())
                .as("a plan with a nested fence written and written over")
                .isEqualTo("Covers.\n\n## Plan\n\n- New.\n\n## Acceptance\n\n- A\n");
    }

    @Test
    void aPersonsOwnHeadingTwiceIsNoReasonToRefuse() {
        // Harm: a body refused for headings the planner never writes under.
        final String body =
                "Covers.\n\n## Notes\n\nOne.\n\n## Plan\n\n- Old.\n\n## Notes\n\nTwo.\n";
        assertThat(BodySections.read(body).with(BodySections.Part.PLAN, "- New.").text())
                .as("the plan written beside two headings of the person's own")
                .isEqualTo(body.replace("- Old.", "- New."));
    }

    @Test
    void aFenceLeftOpenUnderAPersonsSectionIsNamedByItsOwnLine() {
        // Harm: a person sent to repair a fence that is right, while the one left open stays.
        final String body =
                "Covers.\n\n## Plan\n\n- p\n\n## Notes\n\n```\nsecret-left-open\n\n## More\n\n"
                        + "x\n\n```\ncode\n```\n";
        assertThat(BodySections.read(body).ambiguity())
                .as("the repair named for a fence left open before a closed block")
                .contains("the code block opened in line 9 never closes");
    }

    @Test
    void aLastSectionWrittenOverKeepsTheBodysMissingFinalLineFeed() {
        // Harm: a byte of the body changed that a write leaves as it was.
        assertThat(
                        BodySections.read("Covers.\n\n## Plan\n\n- Old.")
                                .with(BodySections.Part.PLAN, "- New.\n- Two.")
                                .text())
                .as("the last section written over, without a line feed at its end")
                .isEqualTo("Covers.\n\n## Plan\n\n- New.\n- Two.");
    }

    @Test
    void whatItCoversIsWrittenFirstWhereItIsReadBack() {
        // Harm: the covers line written where it is never read, so each save adds one more copy.
        final BodySections body =
                BodySections.read(
                        "**What must be true.** Something.\n\n**Why this size.** A guess.\n\n"
                                + "## Plan\n\n- Old.\n");

        final BodySections once = body.with(BodySections.Part.COVERS, "What it covers.");

        assertThat(once.part(BodySections.Part.COVERS))
                .as("the covers line read back")
                .contains("What it covers.");
        assertThat(once.text())
                .as("the covers line first, the rest as it was")
                .isEqualTo(
                        "What it covers.\n\n**What must be true.** Something.\n\n"
                                + "**Why this size.** A guess.\n\n## Plan\n\n- Old.\n");
        assertThat(once.with(BodySections.Part.COVERS, "What it covers.").text())
                .as("the covers line written a second time")
                .isEqualTo(once.text());
    }

    private static StageResult.ForIssue analysis(final List<String> toBeChecked) {
        return result("- Read it.\n\n```\n## a shell comment\n```", toBeChecked);
    }

    private static StageResult.ForIssue result(
            final @Nullable String plan, final List<String> toBeChecked) {
        return new StageResult.ForIssue(
                ID,
                "Backlog",
                "Review",
                plan,
                "M",
                "one module",
                null,
                toBeChecked,
                List.of("Found one."));
    }
}

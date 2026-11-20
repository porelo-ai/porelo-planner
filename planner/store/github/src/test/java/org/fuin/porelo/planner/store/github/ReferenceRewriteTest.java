package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A Markdown text's references rewritten for GitHub Issues, as the carry-over writes them. */
class ReferenceRewriteTest {

    private static final Map<String, String> WEBS =
            Map.of(
                    "porelo-project", "https://github.com/porelo-ai/porelo-project",
                    "porelo-planner", "https://github.com/porelo-ai/porelo-planner");

    /** PL12 and ST3 are carried; PL9 was finished and is not. */
    private static final Map<String, String> CARRIED =
            Map.of(
                    "porelo-planner/PL12",
                    "porelo-planner#PL12",
                    "porelo-project/ST3",
                    "porelo-project#ST3");

    private static ReferenceRewrite.Rewritten rewrite(final String text) {
        return rewrite(text, "issues");
    }

    private static ReferenceRewrite.Rewritten rewrite(final String text, final String folder) {
        return ReferenceRewrite.rewrite(
                text,
                "porelo-planner",
                folder,
                WEBS,
                (repository, id) -> Optional.ofNullable(CARRIED.get(repository + "/" + id)));
    }

    /** Asserts that a text comes back as it is, nothing rewritten and nothing left as text. */
    private static void unchanged(final String text) {
        final ReferenceRewrite.Rewritten rewritten = rewrite(text);
        assertThat(rewritten.text()).as("the text").isEqualTo(text);
        assertThat(rewritten.rewritten()).as("what was rewritten").isEmpty();
        assertThat(rewritten.leftAsText()).as("what was left as text").isEmpty();
    }

    @Test
    void aLinkToAnIndexRowBecomesTheCarriedItemsReference() {
        final ReferenceRewrite.Rewritten rewritten =
                rewrite("Needs [PL12](README.md#pl12) first.\n");
        assertThat(rewritten.text()).as("the text").isEqualTo("Needs porelo-planner#PL12 first.\n");
        assertThat(rewritten.rewritten())
                .as("what was rewritten")
                .containsExactly("PL12 -> porelo-planner#PL12");
    }

    @Test
    void aLinkToAnotherRepositorysIndexRowBecomesThatItemsReference() {
        final ReferenceRewrite.Rewritten rewritten =
                rewrite(
                        "Part of [porelo-project#ST3](https://github.com/porelo-ai/porelo-project/blob/main/stories/README.md#st3).");
        assertThat(rewritten.text()).as("the text").isEqualTo("Part of porelo-project#ST3.");
    }

    @Test
    void aLinkWithWordsKeepsItsWordsBesideTheReference() {
        assertThat(rewrite("See [the store's flow](README.md#pl12).").text())
                .as("the text")
                .isEqualTo("See the store's flow (porelo-planner#PL12).");
    }

    @Test
    void aLinkToAnItemNotCarriedIsLeftAsPlainTextAndNamed() {
        // Harm: a link to a finished issue rewritten to a guessed reference points at another item.
        final ReferenceRewrite.Rewritten rewritten = rewrite("Built on [PL9](README.md#pl9).");
        assertThat(rewritten.text()).as("the text").isEqualTo("Built on PL9.");
        assertThat(rewritten.leftAsText()).as("what was left as text").containsExactly("PL9");
        assertThat(rewritten.rewritten()).as("what was rewritten").isEmpty();
    }

    @Test
    void aLinkNamingAnotherRepositoryThanTheItemsIsNotRewritten() {
        // Harm: an id read by its number alone would point the reference at another repository's
        // item.
        final ReferenceRewrite.Rewritten rewritten =
                rewrite(
                        "[PL12](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pl12)");
        assertThat(rewritten.text()).as("the text").isEqualTo("PL12");
        assertThat(rewritten.leftAsText()).as("what was left as text").containsExactly("PL12");
    }

    @Test
    void aLinkInCodeOrToAnotherProjectStaysAsItIs() {
        // Harm: a link quoted as code or a page outside the project rewritten changes what a person
        // wrote.
        final String text =
                "`[PL12](README.md#pl12)`\n"
                        + "```\n[PL12](README.md#pl12)\n```\n"
                        + "[X1](https://github.com/other/elsewhere/blob/main/issues/README.md#pl12)\n";
        final ReferenceRewrite.Rewritten rewritten = rewrite(text);
        assertThat(rewritten.text()).as("the text").isEqualTo(text);
        assertThat(rewritten.rewritten()).as("what was rewritten").isEmpty();
        assertThat(rewritten.leftAsText()).as("what was left as text").isEmpty();
    }

    @Test
    void aLinkToARepositoryOfTheSameNameOfAnotherOwnerStaysAsItIs() {
        // Harm: a link into another owner's index is rewritten to this project's item of that id.
        unchanged(
                "[PL12](https://github.com/other-org/porelo-planner/blob/main/issues/README.md#pl12)\n");
    }

    @Test
    void aRelativeLinkOutOfTheRepositoryOrToAPageNoIndexStaysAsItIs() {
        // Harm: a link to another repository's or a non-index page is rewritten to this
        // repository's item, or stripped to plain text.
        unchanged("[PL12](../../other-repo/issues/README.md#pl12)\n");
        unchanged("[setup](../doc/README.md#pl12)\n");
    }

    @Test
    void aStorysLinkToItsRepositorysIssueIndexIsRewritten() {
        assertThat(rewrite("[PL12](../issues/README.md#pl12)", "stories").text())
                .as("the text")
                .isEqualTo("porelo-planner#PL12");
    }

    @Test
    void aLinkInACodeSpanOfTwoBackticksStaysAsItIs() {
        // Harm: a link quoted as code is rewritten, changing what a person wrote.
        unchanged("``[PL12](README.md#pl12)``\n");
    }

    @Test
    void aShorterFenceInsideALongerOneClosesNothing() {
        // Harm: a link in a code block after a shorter fence line in it is rewritten.
        unchanged("````\n```\n[PL12](README.md#pl12)\n````\n");
    }

    @Test
    void codeOnOneLineBetweenThreeBackticksOpensNoFence() {
        // Harm: every reference below a line of inline code is left unrewritten.
        final ReferenceRewrite.Rewritten rewritten =
                rewrite("```[PL9](README.md#pl9)```\nNeeds [PL12](README.md#pl12).\n");
        assertThat(rewritten.text())
                .as("the text")
                .isEqualTo("```[PL9](README.md#pl9)```\nNeeds porelo-planner#PL12.\n");
    }
}

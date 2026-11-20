package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A Markdown text's references rewritten for GitHub Issues, as the carry-over writes them. */
class ReferenceRewriteTest {

    private static final List<String> REPOSITORIES = List.of("porelo-project", "porelo-planner");

    /** PL12 and ST3 are carried; PL9 was finished and is not. */
    private static final Map<String, String> CARRIED =
            Map.of(
                    "porelo-planner/PL12",
                    "porelo-planner#PL12",
                    "porelo-project/ST3",
                    "porelo-project#ST3");

    private static ReferenceRewrite.Rewritten rewrite(final String text) {
        return ReferenceRewrite.rewrite(
                text,
                "porelo-planner",
                REPOSITORIES,
                (repository, id) -> Optional.ofNullable(CARRIED.get(repository + "/" + id)));
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
}

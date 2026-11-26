package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Why a save is held, in the planner's fixed words, shown to the person as they stand. */
class HeldReasonsTest {

    /** A reason's kind or another name written as code: a capital inside a word. */
    private static final Pattern INTERNAL = Pattern.compile("\\b[A-Za-z]+[a-z][A-Z][A-Za-z]*\\b");

    /** A path, a URL or a URL template, or a word for a secret. */
    private static final Pattern UNSHOWN =
            Pattern.compile(
                    "[/\\\\{}~$]|https?:|\\btoken\\b|\\bsecret\\b|\\bpassword\\b",
                    Pattern.CASE_INSENSITIVE);

    private static List<String> sentences() {
        final List<String> all = new ArrayList<>();
        for (final String kind : CommandOutbox.REASONS) {
            all.add(HeldReasons.of(kind, ""));
            all.add(HeldReasons.of(kind, "AA1"));
        }
        return all;
    }

    @Test
    void everyReasonTheOutboxKeepsHasItsOwnSentenceNamingItsSubject() {
        // Harm: a held save shown without a reason the person can read, or with words of another.
        assertThat(HeldReasons.WORDS.keySet()).isEqualTo(CommandOutbox.REASONS);
        for (final String kind : CommandOutbox.REASONS) {
            assertThat(HeldReasons.of(kind, "")).as(kind).doesNotContain("%s");
        }
        assertThat(HeldReasons.of("Whatever", ""))
                .as("an unknown kind")
                .isEqualTo(HeldReasons.of("NotOffered", ""));
    }

    /** A subject of the form each reason's refusal really carries. */
    private static final java.util.Map<String, String> CARRIED =
            java.util.Map.ofEntries(
                    java.util.Map.entry("NoSuchStory", "ST1"),
                    java.util.Map.entry("NoSuchIssue", "porelo-store#41"),
                    java.util.Map.entry("WorkflowMissing", "porelo-store"),
                    java.util.Map.entry("CredentialRefused", "porelo-store"),
                    java.util.Map.entry("NoForgeAccess", "https://github.com"),
                    java.util.Map.entry("Unreachable", "porelo-store"),
                    java.util.Map.entry("Unreadable", "porelo-store"),
                    java.util.Map.entry("ItemsDirectoryRefused", "/home/person/planning-items"),
                    java.util.Map.entry("StaleRevision", "unread"),
                    java.util.Map.entry("Changed", "AA1"),
                    java.util.Map.entry("BasisIncomplete", "AA1"),
                    java.util.Map.entry("NotSavable", "AA1"),
                    java.util.Map.entry("InStory", "AA1"),
                    java.util.Map.entry("Busy", "porelo-store"),
                    java.util.Map.entry("PartlySaved", "porelo-store"),
                    java.util.Map.entry("LabelsNotKept", "porelo-store"),
                    java.util.Map.entry("NotOffered", "AA1"));

    @Test
    void aSubjectThatIsAFolderOrARevisionIsNeverShown() {
        // Harm: a folder of the person's computer, or the board's revision or the word for an
        // unread board, stands in the sentence as if it were what the save is about.
        assertThat(CARRIED.keySet()).isEqualTo(CommandOutbox.REASONS);
        final java.util.Set<String> unnamed =
                java.util.Set.of("ItemsDirectoryRefused", "StaleRevision");
        for (final String kind : CommandOutbox.REASONS) {
            final String sentence = HeldReasons.of(kind, CARRIED.get(kind));
            if (unnamed.contains(kind)) {
                assertThat(sentence).as(kind).isEqualTo(HeldReasons.of(kind, ""));
            } else {
                assertThat(sentence).as(kind).contains(CARRIED.get(kind));
            }
        }
        assertThat(HeldReasons.of("StaleRevision", "3f9c2e1d"))
                .as("a revision")
                .isEqualTo(HeldReasons.of("StaleRevision", ""));
    }

    @Test
    void everySentenceIsOneShortPlainSentence() {
        // Harm: words shown to the person as they stand that name a code name, a secret, a path or
        // a URL template, or run on past one sentence.
        for (final String sentence : sentences()) {
            assertThat(sentence)
                    .as(sentence)
                    .matches("[A-Z0-9][^.!?]*\\.")
                    .hasSizeLessThanOrEqualTo(80)
                    .doesNotContainPattern(INTERNAL)
                    .doesNotContainPattern(UNSHOWN);
        }
    }
}

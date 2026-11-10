package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.junit.jupiter.api.Test;

class WorkReaderTest {

    private static final String SOUND =
            """
            work:
              Implementing:
                from: [Backlog, Rework]
                done: Review
                then: Reading
                per: repository
                modes: [UNATTENDED, ATTENDED]
                order: "Build {issues} of {story}."
                models: {claude-code: claude-opus-5-5, codex: gpt-6.1-sol}
              Reading:
                from: [Implementing]
                done: Review
                blocked: Reworking
                per: story
                modes: [UNATTENDED]
                order: "Read {tasks}."
                models: {agy: gemini-4-argon-high}
            """;

    @Test
    void soundRulesAreReadInTheFilesOrder() {
        final Work work = WorkReader.read(SOUND);
        assertThat(work.faults()).as("no fault").isEmpty();
        assertThat(work.rules().keySet())
                .as("the working states in the file's order")
                .containsExactly("Implementing", "Reading");
        final WorkRule implementing = work.rules().get("Implementing");
        assertThat(implementing)
                .as("every field read")
                .isEqualTo(
                        new WorkRule(
                                List.of("Backlog", "Rework"),
                                "Review",
                                "Reading",
                                null,
                                WorkRule.Per.REPOSITORY,
                                List.of("UNATTENDED", "ATTENDED"),
                                "Build {issues} of {story}.",
                                implementing.models()));
        assertThat(implementing.models().keySet())
                .as("the agents in the file's order, the first the default")
                .containsExactly("claude-code", "codex");
        assertThat(work.rules().get("Reading").per()).as("per story").isEqualTo(WorkRule.Per.STORY);
    }

    @Test
    void aMissingFileIsAFaultNotAFailure() {
        // Without work rules the board is still read; only work cannot start.
        final Work work = WorkReader.read(null);
        assertThat(work.rules()).as("no rules").isEmpty();
        assertThat(work.faults())
                .as("the fault named")
                .containsExactly("stories/work.yml is missing, so no work can be started");
    }

    @Test
    void aFileThatIsNoYamlIsOneFault() {
        final Work work = WorkReader.read("work: [unclosed\n");
        assertThat(work.rules()).as("no rules").isEmpty();
        assertThat(work.faults()).as("one fault").hasSize(1);
        assertThat(work.faults().get(0)).as("the fault").startsWith("stories/work.yml: not YAML: ");
    }

    @Test
    void aFileWithoutAWorkMappingIsOneFault() {
        assertThat(WorkReader.read("states: [Idea]\n").faults())
                .as("no work mapping")
                .containsExactly("stories/work.yml: no mapping 'work'");
        assertThat(WorkReader.read("").faults())
                .as("an empty file")
                .containsExactly("stories/work.yml: no mapping 'work'");
    }

    @Test
    void aBrokenRuleIsDroppedWithItsFaultAndTheOthersAreKept() {
        // One wrong line must not stop every other work from starting.
        final String broken =
                SOUND.replace("    per: story\n", "    per: issue\n")
                        .replace("    then: Reading\n", "    then: Reading\n    next: Review\n");
        final Work work = WorkReader.read(broken);
        assertThat(work.faults())
                .as("each fault named with its state")
                .containsExactly(
                        "stories/work.yml: Implementing: unknown key 'next'",
                        "stories/work.yml: Reading: per 'issue' is neither story nor repository");
        assertThat(work.rules()).as("both dropped").isEmpty();
    }

    @Test
    void aMissingOrMistypedFieldIsAFault() {
        final String broken =
                SOUND.replace("    from: [Backlog, Rework]\n", "    from: Backlog\n")
                        .replace("    order: \"Read {tasks}.\"\n", "");
        final Work work = WorkReader.read(broken);
        assertThat(work.faults())
                .as("each fault named")
                .containsExactly(
                        "stories/work.yml: Implementing: from is no list of states",
                        "stories/work.yml: Reading: order is missing");
        assertThat(work.rules()).as("both dropped").isEmpty();
    }

    @Test
    void aStateTwiceIsAFaultNotASilentReplacement() {
        // The second rule would replace the first unseen, and work would start by rules nobody
        // wrote last.
        final Work work = WorkReader.read(SOUND + "  Implementing:\n    from: [Backlog]\n");
        assertThat(work.rules()).as("no rules").isEmpty();
        assertThat(work.faults()).as("one fault").hasSize(1);
        assertThat(work.faults().get(0))
                .as("the fault names the key twice")
                .startsWith("stories/work.yml: not YAML: ")
                .contains("Implementing");
    }

    @Test
    void deepNestingIsAFault() {
        // The parser goes down one level per bracket; without a bound a small file takes the stack.
        final Work work = WorkReader.read("work: " + "[".repeat(200) + "]".repeat(200) + "\n");
        assertThat(work.faults()).as("one fault").hasSize(1);
        assertThat(work.faults().get(0))
                .as("refused as YAML")
                .startsWith("stories/work.yml: not YAML: ");
    }

    @Test
    void anEmptyListIsAFault() {
        final Work work = WorkReader.read(SOUND.replace("[UNATTENDED, ATTENDED]", "[]"));
        assertThat(work.faults())
                .as("the fault named")
                .containsExactly("stories/work.yml: Implementing: modes names no mode");
        assertThat(work.rules().keySet()).as("the other kept").containsExactly("Reading");
    }
}

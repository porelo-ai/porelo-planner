package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Merges one command over a pair of items, the one it was judged on and the one GitHub holds now.
 */
class CommandMergeTest {

    private static final String BODY = "What it covers.\n\n**What must be true.** It holds.\n";

    private static final MergeTargets.State IN_PROGRESS =
            new MergeTargets.State("In progress", true, false);

    private static final MergeTargets.State DONE = new MergeTargets.State(null, false, false);

    private static final Writes.Reason REASON = new Writes.Reason("Done", "It landed.");

    /** An item, written over by each test where it differs. */
    private static final class Item {
        String itemId = "PVTI_1";
        String issueId = "I_1";
        int number = 1;
        String title = "One";
        String body = BODY;
        boolean open = true;
        @Nullable String stateReason;
        final Map<String, String> fields = new LinkedHashMap<>(Map.of("Status", "Ready"));
        List<String> labels = List.of("tests");
        @Nullable String parent;
        List<String> subIssues = List.of();
        List<String> blockers = List.of();
        boolean archived;
        int unseenBlockers;
        @Nullable String issueType;
        int subIssueCount = -1;
        int blockerCount = -1;
        boolean partial;
        int unseenLabels;
        int unseenValues;

        Item set(final String field, final String value) {
            fields.put(field, value);
            return this;
        }

        ItemView view() {
            return new ItemView(
                    itemId,
                    issueId,
                    "example-project",
                    number,
                    title,
                    body,
                    open,
                    stateReason,
                    fields,
                    labels,
                    parent,
                    subIssues,
                    blockers,
                    URI.create("https://github.com/example-org/example-project/issues/1"),
                    Instant.parse("2026-11-20T10:00:00Z"),
                    archived,
                    List.of(),
                    unseenBlockers,
                    issueType,
                    null,
                    subIssueCount < 0 ? subIssues.size() : subIssueCount,
                    blockerCount < 0 ? blockers.size() + unseenBlockers : blockerCount,
                    partial,
                    unseenLabels,
                    unseenValues);
        }
    }

    /** What a command writes, each target null until a test sets it. */
    private static final class Targets {
        boolean story;
        MergeTargets.@Nullable State state;
        Writes.@Nullable Reason reason;
        @Nullable String worker;
        @Nullable String priority;
        @Nullable List<String> topics;
        @Nullable String title;
        final Map<TextEdit.Part, String> written = new LinkedHashMap<>();
        final List<TextEdit.Part> removed = new ArrayList<>();
        Set<Place> reads = Set.of();

        MergeTargets of() {
            return new MergeTargets(
                    "porelo-ai/example-project#1", // not-a-citation
                    story,
                    state,
                    reason,
                    worker,
                    priority,
                    topics,
                    title,
                    written,
                    Set.copyOf(removed),
                    reads);
        }
    }

    private static Targets move(final MergeTargets.State state) {
        final Targets targets = new Targets();
        targets.state = state;
        return targets;
    }

    private static CommandMerge.Merged merge(
            final Targets targets, final Item judged, final Item now) {
        return CommandMerge.merge(targets.of(), judged.view(), now.view(), false);
    }

    private static MergeTargets written(final CommandMerge.Merged merged, final Item now) {
        assertThat(merged).isInstanceOf(CommandMerge.Write.class);
        final CommandMerge.Write write = (CommandMerge.Write) merged;
        assertThat(write.on()).isEqualTo(now.view());
        return write.rebased();
    }

    private static void conflicts(final CommandMerge.Merged merged, final Place... places) {
        assertThat(merged).isInstanceOf(CommandMerge.Conflict.class);
        assertThat(((CommandMerge.Conflict) merged).places()).containsExactly(places);
    }

    @Test
    void aMoveWhileAnotherSetTheWorkerIsWrittenOnTheItemNow() {
        final Item now = new Item().set("Worker", "Agent");

        final MergeTargets rebased = written(merge(move(IN_PROGRESS), new Item(), now), now);

        assertThat(rebased.state()).isEqualTo(IN_PROGRESS);
    }

    @Test
    void aMoveWhileAnotherMovedTheStatusIsAConflict() {
        final Item now = new Item().set("Status", "Reworking");

        conflicts(merge(move(IN_PROGRESS), new Item(), now), Place.STATE);
    }

    @Test
    void aMoveOnAnItemAnotherClosedIsAConflictThoughItsStatusStayed() {
        final Item now = new Item();
        now.open = false;
        now.stateReason = "completed";

        conflicts(merge(move(IN_PROGRESS), new Item(), now), Place.STATE);
    }

    @Test
    void aCloseAnotherMadeAlreadyIsDoneWithNothingLeftToWrite() {
        final Item now = new Item();
        now.open = false;
        now.stateReason = "completed";

        assertThat(merge(move(DONE), new Item(), now)).isInstanceOf(CommandMerge.Nothing.class);
    }

    @Test
    void aReasonIsNeverWrittenOntoACloseAnotherMade() {
        final Targets close = move(DONE);
        close.reason = REASON;
        final Item now = new Item();
        now.open = false;
        now.stateReason = "completed";

        conflicts(merge(close, new Item(), now), Place.STATE);
    }

    @Test
    void aReasonWhoseCommentFailedIsWrittenAgainOnItsOwnCloseAfterATitleEdit() {
        final Targets close = move(DONE);
        close.reason = REASON;
        final Item judged = new Item();
        judged.open = false;
        judged.stateReason = "completed";
        final Item now = new Item();
        now.open = false;
        now.stateReason = "completed";
        now.title = "One, renamed";

        final MergeTargets rebased = written(merge(close, judged, now), now);

        assertThat(rebased.state()).isEqualTo(DONE);
        assertThat(rebased.reason()).isEqualTo(REASON);
        assertThat(CommandMerge.merge(close.of(), judged.view(), now.view(), true))
                .isInstanceOf(CommandMerge.Nothing.class);
    }

    @Test
    void aStoryIsOnlyReopenedWhileAnIssueOfAnIssueTypeKeepsItsStatusAsItsState() {
        final Item judged = new Item();
        judged.open = false;
        judged.stateReason = "completed";
        judged.issueType = "Task";
        final Item now = new Item().set("Status", "Reworking");
        now.open = false;
        now.stateReason = "completed";
        now.issueType = "Task";
        final Targets story = move(new MergeTargets.State(null, true, false));
        story.story = true;

        written(merge(story, judged, now), now);
        conflicts(merge(move(IN_PROGRESS), judged, now), Place.STATE);
    }

    @Test
    void aWorkerAgainstAnotherWorkerIsAConflictAndAgainstATitleIsWritten() {
        final Targets worker = new Targets();
        worker.worker = "Agent";
        final Item retitled = new Item();
        retitled.title = "One, renamed";

        conflicts(merge(worker, new Item(), new Item().set("Worker", "Person")), Place.WORKER);
        assertThat(written(merge(worker, new Item(), retitled), retitled).worker())
                .isEqualTo("Agent");
    }

    @Test
    void aTitleAgainstAnotherTitleIsAConflictAndAgainstTheBodyIsWritten() {
        final Targets title = new Targets();
        title.title = "One, mine";
        final Item retitled = new Item();
        retitled.title = "One, theirs";
        final Item rewritten = new Item();
        rewritten.body = "Something else it covers.\n\n**What must be true.** It holds.\n";

        conflicts(merge(title, new Item(), retitled), Place.TITLE);
        assertThat(written(merge(title, new Item(), rewritten), rewritten).title())
                .isEqualTo("One, mine");
    }

    @Test
    void aPriorityAgainstAnotherLabelEditIsWritten() {
        final Targets priority = new Targets();
        priority.priority = "High";
        final Item now = new Item();
        now.labels = List.of("tests", "build");

        assertThat(written(merge(priority, new Item(), now), now).priority()).isEqualTo("High");
    }

    @Test
    void aPriorityStandsAlreadyByGitHubsSpellingOfIt() {
        final Targets priority = new Targets();
        priority.priority = "Highest";

        assertThat(
                        merge(
                                priority,
                                new Item().set("Priority", "High"),
                                new Item().set("Priority", "Urgent")))
                .isInstanceOf(CommandMerge.Nothing.class);
    }

    @Test
    void aTopicLabelAddedKeepsTheLabelsAnotherAddedAndRemoved() {
        final Targets labels = new Targets();
        final Item judged = new Item();
        judged.labels = List.of("tests", "security");
        labels.topics = List.of("tests", "security", "documentation");
        final Item now = new Item();
        now.labels = List.of("build", "Bug", "tests");

        assertThat(written(merge(labels, judged, now), now).topics())
                .containsExactly("documentation", "tests", "build");
    }

    @Test
    void aTopicLabelIsComparedInTheCaseTheTopicLabelsFold() {
        final Targets labels = new Targets();
        labels.topics = List.of("build");
        final Item judged = new Item();
        judged.labels = List.of("Tests");
        final Item now = new Item();
        now.labels = List.of("Tests");

        assertThat(written(merge(labels, judged, now), now).topics()).containsExactly("build");
    }

    @Test
    void labelsWrittenWhileLabelsLiePastThePageReadAreAConflict() {
        final Targets labels = new Targets();
        labels.topics = List.of("tests", "build");
        final Item now = new Item();
        now.unseenLabels = 1;
        final Item partial = new Item();
        partial.partial = true;

        conflicts(merge(labels, new Item(), now), Place.label("build"));
        conflicts(merge(labels, partial, new Item()), Place.label("build"));
    }

    @Test
    void aFieldWrittenWhileTheJudgedItemHadValuesPastThePageIsAConflict() {
        final Targets worker = new Targets();
        worker.worker = "Agent";
        final Item judged = new Item();
        judged.unseenValues = 1;

        conflicts(merge(worker, judged, new Item()), Place.WORKER);
    }

    @Test
    void aPartWrittenWhileAnotherRewroteAnotherPartIsWrittenOnTheBodyNow() {
        final Targets text = new Targets();
        text.written.put(TextEdit.Part.WHAT_MUST_BE_TRUE, "It holds twice.");
        final Item now = new Item();
        now.body = "Something else it covers.\n\n**What must be true.** It holds.\n";

        final MergeTargets rebased = written(merge(text, new Item(), now), now);

        assertThat(rebased.written())
                .containsExactly(Map.entry(TextEdit.Part.WHAT_MUST_BE_TRUE, "It holds twice."));
    }

    @Test
    void theSamePartEditedOnBothSidesIsAConflict() {
        final Targets text = new Targets();
        text.written.put(TextEdit.Part.WHAT_MUST_BE_TRUE, "It holds twice.");
        final Item now = new Item();
        now.body = "What it covers.\n\n**What must be true.** It holds thrice.\n";

        conflicts(merge(text, new Item(), now), Place.body(TextEdit.Part.WHAT_MUST_BE_TRUE));
    }

    @Test
    void aBodyWhoseSectionsRepeatOnEitherSideIsAConflict() {
        final Targets text = new Targets();
        text.written.put(TextEdit.Part.WHAT_MUST_BE_TRUE, "It holds twice.");
        final Item repeated = new Item();
        repeated.body = BODY + "\n## Plan\n\nOne.\n\n## Plan\n\nTwo.\n";

        conflicts(merge(text, repeated, new Item()), Place.body(TextEdit.Part.WHAT_MUST_BE_TRUE));
        conflicts(merge(text, new Item(), repeated), Place.body(TextEdit.Part.WHAT_MUST_BE_TRUE));
    }

    @Test
    void aBodyTooLongToReadIsAConflictNotAnError() {
        final Targets text = new Targets();
        text.written.put(TextEdit.Part.WHAT_MUST_BE_TRUE, "It holds twice.");
        final Item now = new Item();
        now.body = BODY + "x".repeat(BodySections.LONGEST);

        conflicts(merge(text, new Item(), now), Place.body(TextEdit.Part.WHAT_MUST_BE_TRUE));
    }

    @Test
    void aTasksStartThatReadItsBlockersIsAConflictOnceAnotherAddedOne() {
        final Targets start = move(IN_PROGRESS);
        start.reads = MergeTargets.RUN_READS;
        final Item now = new Item();
        now.blockers = List.of("I_9");

        conflicts(merge(start, new Item(), now), Place.BLOCKERS);
    }

    @Test
    void aTasksStartIsAConflictOnceItsTitleOrBodyChangedWhichTheNextTaskTakesOver() {
        final Targets start = move(IN_PROGRESS);
        start.reads = MergeTargets.RUN_READS;
        final Item retitled = new Item();
        retitled.title = "One, renamed";
        final Item rewritten = new Item();
        rewritten.body = BODY + "\nWords of a person.\n";

        conflicts(merge(start, new Item(), retitled), Place.TITLE);
        conflicts(merge(start, new Item(), rewritten), Place.WHOLE_BODY);
    }

    @Test
    void aReadListPastThePageIsAConflictThoughNoneOfItChanged() {
        final Targets start = move(IN_PROGRESS);
        start.reads = MergeTargets.RUN_READS;
        final Item blockers = new Item();
        blockers.blockers = List.of("I_9");
        blockers.blockerCount = 2;
        final Item subIssues = new Item();
        subIssues.subIssues = List.of("I_8");
        subIssues.subIssueCount = 2;

        conflicts(merge(start, blockers, blockers), Place.BLOCKERS);
        conflicts(merge(start, subIssues, subIssues), Place.SUB_ISSUES);
    }

    @Test
    void aStorysIssuesReadUnderAlsoNeedsInItsBodyAreAConflictOnceTheyChange() {
        final Targets close = move(DONE);
        close.story = true;
        close.reads = Set.of(Place.SUB_ISSUES);
        final Item judged = new Item();
        judged.body = BODY + "\n## Also needs\n\n- example-lib#3\n";
        final Item now = new Item();
        now.body = BODY + "\n## Also needs\n\n- example-lib#3\n- example-lib#4\n";

        conflicts(merge(close, judged, now), Place.SUB_ISSUES);
    }

    @Test
    void aStorysSubIssuesReadAreAConflictOnceAnotherHangsOneUnderItButNotForAnotherPartOfItsBody() {
        // Harm: a story closed over an issue hung under it after the close was judged, or a close
        // refused for words written elsewhere in the story's body.
        final Targets close = move(DONE);
        close.story = true;
        close.reads = Set.of(Place.SUB_ISSUES);
        final Item judged = new Item();
        judged.subIssues = List.of("I_8");
        final Item hung = new Item();
        hung.subIssues = List.of("I_8", "I_9");
        final Item rewritten = new Item();
        rewritten.subIssues = List.of("I_8");
        rewritten.body = "Something else it covers.\n\n**What must be true.** It holds.\n";

        conflicts(merge(close, judged, hung), Place.SUB_ISSUES);
        written(merge(close, judged, rewritten), rewritten);
    }

    @Test
    void aPriorityGitHubHasNoOptionForIsAConflictNotAnError() {
        // Harm: a merge that throws on a command it cannot write, instead of handing it to the
        // owner account.
        final Targets priority = new Targets();
        priority.priority = "Lowest";

        conflicts(merge(priority, new Item(), new Item()), Place.PRIORITY);
    }

    @Test
    void aReasonOnACloseThatStoodWhenJudgedIsWrittenWhileItsCommentDoesNotStand() {
        // Harm: a reason whose comment failed never written, though the close is the command's own.
        final Targets close = move(DONE);
        close.reason = REASON;
        final Item closed = new Item();
        closed.open = false;
        closed.stateReason = "completed";

        final MergeTargets rebased = written(merge(close, closed, closed), closed);

        assertThat(rebased.reason()).isEqualTo(REASON);
        assertThat(rebased.state()).isEqualTo(DONE);
    }

    @Test
    void anItemWhoseIssueTypeChangedOrThatWasArchivedIsAConflict() {
        final Item typed = new Item();
        typed.issueType = "Bug";
        final Item archived = new Item();
        archived.archived = true;

        conflicts(merge(move(IN_PROGRESS), new Item(), typed), Place.ITEM);
        conflicts(merge(move(IN_PROGRESS), new Item(), archived), Place.ITEM);
    }

    @Test
    void anItemThatDidNotMoveKeepsTheCommandWhole() {
        final Targets start = move(IN_PROGRESS);
        start.worker = "Agent";
        start.reads = MergeTargets.RUN_READS;

        assertThat(written(merge(start, new Item(), new Item()), new Item())).isEqualTo(start.of());
    }
}

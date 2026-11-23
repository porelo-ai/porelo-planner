package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.RepositoryWarning;
import org.fuin.porelo.planner.store.WorkRule;
import org.junit.jupiter.api.Test;

/**
 * A repository's correction of its labels: the preview's calls, the confirm checked again against
 * the labels the store keeps now, and a store without them.
 */
class RepositoryCorrectionsTest {

    private static final Board PLAIN = WorkProposalTest.board(WorkRule.Per.REPOSITORY);

    private static final String REPO = PLAIN.names().get(0);

    private static final String OWN = "example-project";

    /** A store keeping its repositories' labels, whose correction clears the warning. */
    private static final class LabelStore implements PlanningStore {

        Board board;

        final boolean keeps;

        final List<List<RepositoryWarning.Call>> corrections = new ArrayList<>();

        /** The labels the forge holds at the next read, when someone changed them; else null. */
        @org.jspecify.annotations.Nullable RepositoryWarning changed;

        /** The repositories whose labels were read anew. */
        final List<String> rereads = new ArrayList<>();

        LabelStore(final boolean keeps, final RepositoryWarning... warnings) {
            this.keeps = keeps;
            this.board = PLAIN.withRepositories(List.of(warnings), List.of());
        }

        @Override
        public Board board() {
            return board;
        }

        @Override
        public Board refresh() {
            return board;
        }

        @Override
        public boolean keepsRepositoryLabels() {
            return keeps;
        }

        @Override
        public Board rereadLabels(final String repository) {
            rereads.add(repository);
            final RepositoryWarning now = changed;
            if (now != null) {
                board = PLAIN.withRepositories(List.of(now), List.of());
                changed = null;
            }
            return board;
        }

        @Override
        public Board correctLabels(
                final String repository, final List<RepositoryWarning.Call> calls) {
            corrections.add(calls);
            board = PLAIN.withRepositories(List.of(), List.of());
            return board;
        }
    }

    private static RepositoryWarning warning() {
        return new RepositoryWarning(
                        REPO,
                        RepositoryWarning.TOPIC_LABELS,
                        List.of("security"),
                        List.of("bug"),
                        List.of("tests"),
                        null)
                .offered();
    }

    private static Corrections.Repository asked() {
        return new Corrections.Repository(REPO, RepositoryWarning.TOPIC_LABELS, null);
    }

    private static WorkRun run(final PlanningStore store) {
        return new WorkRun(
                store,
                new WorkRunTest.Memory(),
                java.time.Clock.fixed(
                        java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC));
    }

    @Test
    void thePreviewListsTheCallsInTheirOrderAndMakesNone() {
        // Harm: the person confirmed calls the preview did not show, or the preview wrote.
        final LabelStore store = new LabelStore(true, warning());

        final Corrections.Labels preview = run(store).labelsPreview(asked());

        assertThat(preview.calls())
                .extracting(c -> c.action().spelled() + " " + c.label())
                .containsExactly("Delete bug", "Create security", "Update tests");
        assertThat(preview.revision()).isEqualTo(PLAIN.revision().value());
        assertThat(store.corrections).as("no call").isEmpty();
    }

    @Test
    void aConfirmMakesTheCallsAndASecondConfirmIsRefusedWithoutACall() {
        // Harm: a confirm sent twice wrote the labels twice, or a stale preview's calls were made.
        final LabelStore store = new LabelStore(true, warning());
        final WorkRun run = run(store);
        final String digest = run.labelsPreview(asked()).digest(OWN, "main");

        final Board after = run.labels(asked(), digest, OWN, "main");

        assertThat(after.repositoryWarnings()).isEmpty();
        assertThat(store.corrections).hasSize(1);
        assertThat(store.corrections.get(0)).isEqualTo(warning().calls());

        final Throwable again = catchThrowable(() -> run.labels(asked(), digest, OWN, "main"));
        assertThat(again)
                .as("the second confirm")
                .isInstanceOf(Moves.Refused.class)
                .hasMessage("The warning no longer stands on it.");
        assertThat(((Moves.Refused) again).parameters()).containsEntry("item", REPO);
        assertThat(store.corrections).as("no second call").hasSize(1);
    }

    @Test
    void aDigestOfAnotherPreviewIsRefusedWithoutACall() {
        // Harm: the calls made were not the ones the person saw.
        final LabelStore store = new LabelStore(true, warning());
        final WorkRun run = run(store);
        final String digest = run.labelsPreview(asked()).digest(OWN, "main");
        store.board =
                PLAIN.withRepositories(
                        List.of(
                                new RepositoryWarning(
                                                REPO,
                                                RepositoryWarning.TOPIC_LABELS,
                                                List.of("security"),
                                                List.of("bug", "wontfix"),
                                                List.of("tests"),
                                                null)
                                        .offered()),
                        List.of());

        final Throwable changed = catchThrowable(() -> run.labels(asked(), digest, OWN, "main"));

        assertThat(changed).isInstanceOf(WorkRefusal.class);
        assertThat(((WorkRefusal) changed).name()).isEqualTo("PreviewChanged");
        assertThat(store.corrections).isEmpty();
    }

    /** The warning naming the defaults given, the rest as {@link #warning()} names it. */
    private static RepositoryWarning withDefaults(final String... defaults) {
        return new RepositoryWarning(
                        REPO,
                        RepositoryWarning.TOPIC_LABELS,
                        List.of("security"),
                        List.of(defaults),
                        List.of("tests"),
                        null)
                .offered();
    }

    @Test
    void thePreviewReadsTheLabelsAnewSoALabelGoneSinceTheLastReadIsNotCalledOn() {
        // Harm: a Delete shown and confirmed for a label a colleague already deleted, or a call
        // computed from labels read before someone changed them.
        final LabelStore store = new LabelStore(true, withDefaults("bug", "wontfix"));
        store.changed = withDefaults("bug");

        final Corrections.Labels preview = run(store).labelsPreview(asked());

        assertThat(store.rereads).as("the repository read anew").containsExactly(REPO);
        assertThat(preview.calls())
                .extracting(c -> c.action().spelled() + " " + c.label())
                .as("the calls as the labels stand now")
                .containsExactly("Delete bug", "Create security", "Update tests");
    }

    @Test
    void aConfirmAfterTheLabelsChangedSinceThePreviewIsRefusedWithoutACall() {
        // Harm: calls made that the person never saw, since the forge's labels changed after
        // the preview.
        final LabelStore store = new LabelStore(true, warning());
        final WorkRun run = run(store);
        final String digest = run.labelsPreview(asked()).digest(OWN, "main");
        store.changed = withDefaults("bug", "wontfix");

        final Throwable changed = catchThrowable(() -> run.labels(asked(), digest, OWN, "main"));

        assertThat(store.rereads).as("read anew by the preview and the confirm").hasSize(2);
        assertThat(changed).isInstanceOf(WorkRefusal.class);
        assertThat(((WorkRefusal) changed).name()).isEqualTo("PreviewChanged");
        assertThat(store.corrections).as("no call").isEmpty();
    }

    @Test
    void theDigestCoversTheRepositoryTheKindAndEachCall() {
        // Harm: a preview of other calls, or of another repository, gave the same digest.
        final Corrections.Labels one = new Corrections.Labels("r1", asked(), warning().calls());
        final Corrections.Labels fewer =
                new Corrections.Labels("r1", asked(), warning().calls().subList(0, 2));
        final Corrections.Labels other =
                new Corrections.Labels(
                        "r1",
                        new Corrections.Repository("other", RepositoryWarning.TOPIC_LABELS, null),
                        warning().calls());

        assertThat(one.digest(OWN, "main")).isEqualTo(one.digest(OWN, "main"));
        assertThat(one.digest(OWN, "main"))
                .isNotEqualTo(fewer.digest(OWN, "main"))
                .isNotEqualTo(other.digest(OWN, "main"))
                .isNotEqualTo(one.digest(OWN, null))
                .isNotEqualTo(one.digest("other-project", "main"));
    }

    @Test
    void aRepositoryNotTheProjectsAChoiceOrAStoreWithoutLabelsIsRefusedBeforeAnyCall() {
        // Harm: a correction named a repository outside the project, or a store with no labels
        // was asked to make calls it cannot.
        final LabelStore store = new LabelStore(true, warning());

        assertThat(
                        catchThrowable(
                                () ->
                                        run(store)
                                                .labelsPreview(
                                                        new Corrections.Repository(
                                                                "elsewhere",
                                                                RepositoryWarning.TOPIC_LABELS,
                                                                null))))
                .isInstanceOf(Moves.Refused.class)
                .hasMessage("The project has no repository elsewhere.");
        assertThat(
                        catchThrowable(
                                () ->
                                        run(store)
                                                .labelsPreview(
                                                        new Corrections.Repository(
                                                                REPO,
                                                                RepositoryWarning.TOPIC_LABELS,
                                                                "Backlog"))))
                .isInstanceOf(Moves.Refused.class)
                .hasMessage("The choice is none of those the correction offers.");
        assertThat(
                        catchThrowable(
                                () -> run(new LabelStore(false, warning())).labelsPreview(asked())))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(
                        catchThrowable(
                                () ->
                                        run(new LabelStore(false, warning()))
                                                .labels(asked(), "x", OWN, null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(
                        catchThrowable(
                                () ->
                                        run(new LabelStore(
                                                        true,
                                                        new RepositoryWarning(
                                                                REPO,
                                                                RepositoryWarning.TOPIC_LABELS,
                                                                List.of(),
                                                                List.of("bug"),
                                                                List.of(),
                                                                null)))
                                                .labelsPreview(asked())))
                .as("a warning offering no correction")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.corrections).isEmpty();
    }
}

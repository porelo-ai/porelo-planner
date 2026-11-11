package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.junit.jupiter.api.Test;

/** A discussed idea is saved once per offer, whatever happens between two hand-ins. */
class HandedIdeasTest {

    private static final String OFFER = "4f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f";

    private final Store store = new Store();

    private final WorkRunTest.Memory record = new WorkRunTest.Memory();

    private final WorkRun run = new WorkRun(store, record, Clock.systemUTC());

    @Test
    void aSecondHandInOfASavedOfferAnswersTheSameStoryAndSavesNothing() {
        // A window that hands the idea in again after a lost answer would make a second story.
        final String first = run.idea(OFFER, NewIdeasTest.idea(), List.of());
        final NewIdeas.Idea other =
                new NewIdeas.Idea(
                        new NewIdeas.Story("Another", "G", "T", "Low"),
                        List.of(NewIdeasTest.issue("porelo-other")));

        assertThat(run.idea(OFFER, other, List.of()))
                .as("the story the offer was saved as")
                .isEqualTo(first)
                .isEqualTo("ST1");
        assertThat(store.saved).as("one save for the offer").hasSize(1);
        assertThat(record.value.ideas().get(OFFER).status())
                .as("the offer is recorded as done")
                .isEqualTo(HandedIdeas.DONE);
    }

    @Test
    void aMalformedIdeaIsRefusedOnATakenOfferToo() {
        // An idea let through because its offer was taken would hide its faults from the person.
        run.idea(OFFER, NewIdeasTest.idea(), List.of());

        final WorkRefusal refused =
                catchThrowableOfType(
                        WorkRefusal.class,
                        () ->
                                run.idea(
                                        OFFER,
                                        new NewIdeas.Idea(NewIdeasTest.story(), List.of()),
                                        List.of()));

        assertThat(refused).as("the malformed idea is refused").isNotNull();
        assertThat(refused.name()).as("its name").isEqualTo("IdeaRefused");
        assertThat(store.saved).as("nothing more is saved").hasSize(1);
    }

    @Test
    void aPartlySavedOfferIsKeptAndRefusedNamingThePushedRepositoriesWithoutASave() {
        // A retry after a partial push would put the pushed issues in their repositories twice.
        store.failures.add(
                new StoreException.PartlySaved(
                        "porelo-project", List.of("porelo-other"), "refused"));

        assertThat(catchThrowable(() -> run.idea(OFFER, NewIdeasTest.idea(), List.of())))
                .as("the partial save is answered as such")
                .isInstanceOf(StoreException.PartlySaved.class);
        final WorkRefusal refused =
                catchThrowableOfType(
                        WorkRefusal.class, () -> run.idea(OFFER, NewIdeasTest.idea(), List.of()));

        assertThat(refused).as("the second hand-in is refused").isNotNull();
        assertThat(refused.name()).as("its name").isEqualTo("IdeaRefused");
        assertThat(refused.parameters().get("pushed"))
                .as("the repositories already pushed")
                .isEqualTo("porelo-other");
        assertThat(store.attempts).as("no second save is tried").isEqualTo(1);
    }

    @Test
    void anOfferLeftPendingByAStoppedPlannerIsRefusedWithoutASave() {
        // A planner stopped mid-save may have pushed; saving again could make a second story.
        record.value =
                new WorkRun.Snapshot(
                        3,
                        Map.of(),
                        Map.of(
                                OFFER,
                                new HandedIdeas.Handed(HandedIdeas.PENDING, null, List.of(), 3)));

        final WorkRefusal refused =
                catchThrowableOfType(
                        WorkRefusal.class, () -> run.idea(OFFER, NewIdeasTest.idea(), List.of()));

        assertThat(refused).as("the pending offer is refused").isNotNull();
        assertThat(refused.name()).as("its name").isEqualTo("IdeaRefused");
        assertThat(refused.parameters().get("pushed")).as("nothing known pushed").isEqualTo("");
        assertThat(store.attempts).as("no save is tried").isZero();
    }

    @Test
    void aStaleRevisionRefreshesAndTriesOnceMore() {
        // The planner, not the window, holds the revision; a store moved by another save is normal.
        store.failures.add(new StoreException.StaleRevision("r2"));

        assertThat(run.idea(OFFER, NewIdeasTest.idea(), List.of()))
                .as("the idea is saved on the second try")
                .isEqualTo("ST1");
        assertThat(store.refreshes).as("one refresh").isEqualTo(1);
        assertThat(store.attempts).as("two tries").isEqualTo(2);
    }

    @Test
    void aSecondStaleRevisionIsAnsweredAndLeavesTheOfferFreeForARetry() {
        // A loop of refreshes would hang the call; a kept pending offer would refuse every retry.
        store.failures.add(new StoreException.StaleRevision("r2"));
        store.failures.add(new StoreException.StaleRevision("r3"));

        assertThat(catchThrowable(() -> run.idea(OFFER, NewIdeasTest.idea(), List.of())))
                .as("the second stale revision is answered")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(store.attempts).as("two tries, no more").isEqualTo(2);
        assertThat(record.value.ideas()).as("the offer is not kept").doesNotContainKey(OFFER);
        assertThat(run.idea(OFFER, NewIdeasTest.idea(), List.of()))
                .as("a retry saves")
                .isEqualTo("ST1");
    }

    @Test
    void aStoreRefusalBeforeAnyPushLeavesTheOfferFreeForARetry() {
        // A refusal kept as pending would refuse the corrected retry for good.
        store.failures.add(new StoreException.NotSavable("", "no prefix"));

        assertThat(catchThrowable(() -> run.idea(OFFER, NewIdeasTest.idea(), List.of())))
                .as("the store's refusal is answered")
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(record.value.ideas()).as("the offer is not kept").isEmpty();
        assertThat(run.idea(OFFER, NewIdeasTest.idea(), List.of()))
                .as("a retry saves")
                .isEqualTo("ST1");
    }

    @Test
    void theOfferIsRecordedAsPendingBeforeTheStoreSaves() {
        // A save that is not preceded by its record cannot be told from a fresh offer after a stop.
        store.during = () -> record.value.ideas().get(OFFER).status();

        run.idea(OFFER, NewIdeasTest.idea(), List.of());

        assertThat(store.seen).as("the record during the save").containsExactly("PENDING");
    }

    @Test
    void anOfferThatIsNoCanonicalUuidIsRefusedBeforeTheRecord() {
        // An offer of another form would be kept under a key no check of the record accepts.
        for (final String offer :
                List.of("4F2C1D7E-8A3B-4C5D-9E6F-0A1B2C3D4E5F", "1-2-3-4-5", "", "offer")) {
            final WorkRefusal refused =
                    catchThrowableOfType(
                            WorkRefusal.class,
                            () -> run.idea(offer, NewIdeasTest.idea(), List.of()));
            assertThat(refused).as("the offer %s is refused", offer).isNotNull();
            assertThat(refused.name()).as("its name").isEqualTo("IdeaRefused");
        }
        assertThat(store.attempts).as("nothing is saved").isZero();
    }

    @Test
    void theRecordKeepsAThousandDoneOffersDroppingTheOldest() {
        // A record growing with every idea would one day refuse all work as full.
        final Map<String, HandedIdeas.Handed> done = new HashMap<>();
        for (int index = 0; index < HandedIdeas.MOST_DONE; index++) {
            done.put(
                    String.format("00000000-0000-4000-8000-%012d", index),
                    new HandedIdeas.Handed(HandedIdeas.DONE, "ST" + (index + 1), List.of(), index));
        }
        record.value = new WorkRun.Snapshot(5000, Map.of(), done);

        run.idea(OFFER, NewIdeasTest.idea(), List.of());

        assertThat(record.value.ideas())
                .as("a thousand done offers, the newest kept and the oldest dropped")
                .hasSize(HandedIdeas.MOST_DONE)
                .containsKey(OFFER)
                .doesNotContainKey("00000000-0000-4000-8000-000000000000")
                .containsKey("00000000-0000-4000-8000-000000000001");
    }

    @Test
    void aRecordedOfferThatFailsItsCheckIsRefused() {
        // A record edited by hand must not hand a made-up story id to the window.
        assertThat(
                        List.of(
                                new HandedIdeas.Handed(HandedIdeas.DONE, null, List.of(), 1),
                                new HandedIdeas.Handed(HandedIdeas.DONE, "ST01", List.of(), 1),
                                new HandedIdeas.Handed(HandedIdeas.PENDING, "ST1", List.of(), 1),
                                new HandedIdeas.Handed(
                                        HandedIdeas.PARTLY, null, List.of("../x"), 1),
                                new HandedIdeas.Handed("SAVED", null, List.of(), 1),
                                new HandedIdeas.Handed(HandedIdeas.DONE, "ST1", List.of(), -1)))
                .as("each record that fails its check")
                .allSatisfy(
                        handed ->
                                assertThat(
                                                catchThrowable(
                                                        () -> HandedIdeas.validate(OFFER, handed)))
                                        .as("%s", handed)
                                        .isInstanceOf(IllegalArgumentException.class));
        assertThat(
                        catchThrowable(
                                () ->
                                        HandedIdeas.validate(
                                                OFFER.toUpperCase(java.util.Locale.ROOT),
                                                new HandedIdeas.Handed(
                                                        HandedIdeas.DONE, "ST1", List.of(), 1))))
                .as("an offer in upper case")
                .isInstanceOf(IllegalArgumentException.class);
        HandedIdeas.validate(
                OFFER, new HandedIdeas.Handed(HandedIdeas.PARTLY, null, List.of("porelo-x"), 0));
    }

    /** A store that adds ideas as story numbers and can refuse a save before it. */
    private static final class Store implements PlanningStore {

        final Deque<RuntimeException> failures = new ArrayDeque<>();

        final List<NewIdea> saved = new ArrayList<>();

        final List<String> seen = new ArrayList<>();

        java.util.function.@org.jspecify.annotations.Nullable Supplier<String> during;

        int attempts;

        int refreshes;

        @Override
        public Board board() {
            return NewIdeasTest.BOARD;
        }

        @Override
        public Board refresh() {
            refreshes++;
            return NewIdeasTest.BOARD;
        }

        @Override
        public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
            attempts++;
            final var check = during;
            if (check != null) {
                seen.add(check.get());
            }
            final RuntimeException failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
            saved.add(idea);
            return new NewIdea.Added("ST" + saved.size(), List.of(), NewIdeasTest.BOARD);
        }
    }
}

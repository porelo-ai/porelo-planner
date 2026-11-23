package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.junit.jupiter.api.Test;

/** The projects the window gives the planner, as a replaced or a failed one leaves them. */
class ProjectsTest {

    private final List<String> opened = new CopyOnWriteArrayList<>();

    private final List<String> retired = new CopyOnWriteArrayList<>();

    private CountDownLatch slow = new CountDownLatch(0);

    private boolean failing;

    private PlanningStore open(final String project, final String repository, final String branch) {
        opened.add(project + " " + repository + " " + branch);
        if (project.equals("slow")) {
            try {
                slow.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failing) {
            throw new StoreException.Unreachable("porelo-project", "timed out");
        }
        return new PlanningStore() {
            @Override
            public Board board() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Board refresh() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void retire() {
                retired.add(project + " " + repository);
            }
        };
    }

    private Projects projects() {
        return new Projects(
                (project, repository, branch) -> open(project, repository, "" + branch));
    }

    @Test
    void theSameSourceAgainOpensNothingAndAnotherReplacesTheStore() {
        // The window sends its PUT on every choice; opening again would fetch the project anew.
        final Projects projects = projects();
        projects.put("alpha", "git@example.org:a/p.git", null);
        final PlanningStore first = projects.store("alpha");
        projects.put("alpha", "git@example.org:a/p.git", null);
        assertThat(projects.store("alpha")).as("the same store kept").isSameAs(first);
        projects.put("alpha", "git@example.org:a/p.git", "other");
        assertThat(projects.store("alpha")).as("a new store for a new branch").isNotSameAs(first);
        assertThat(opened).as("opened twice, not three times").hasSize(2);
    }

    @Test
    void aStoreWhoseWorkRecordCannotBeOpenedIsRetired() {
        // Harm: a store opened for a project whose work record is refused stays live, kept
        // nowhere, beside the store the next try opens for the same project.
        final Projects projects = projects();
        // A name the work record refuses before it opens anything, which the opener here takes.
        assertThatThrownBy(() -> projects.put("a..b", "git@example.org:a/p.git", null))
                .as("a project whose work record is refused")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(opened)
                .as("the store opened")
                .containsExactly("a..b git@example.org:a/p.git null");
        assertThat(retired)
                .as("the store retired on the failure")
                .containsExactly("a..b git@example.org:a/p.git");
    }

    @Test
    void aFailedReplacementLeavesTheProjectUnknownNeverTheOldOne() {
        // The old store would answer for a source the window no longer means.
        final Projects projects = projects();
        projects.put("alpha", "git@example.org:a/p.git", null);
        failing = true;
        assertThatThrownBy(() -> projects.put("alpha", "git@example.org:a/q.git", null))
                .as("the failed open")
                .isInstanceOf(StoreException.Unreachable.class);
        assertThatThrownBy(() -> projects.store("alpha"))
                .as("the project after the failure")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal -> {
                            assertThat(refusal.name()).as("its name").isEqualTo("NoSuchProject");
                            assertThat(refusal.parameters())
                                    .as("what it names")
                                    .containsEntry("project", "alpha");
                        });
    }

    @Test
    void aReplacedStoreIsRetiredBeforeTheNewOneOpens() {
        // The old store would go on fetching its source into the clones the new one reads.
        final Projects projects = projects();
        projects.put("alpha", "git@example.org:a/p.git", null);
        projects.put("alpha", "git@example.org:a/q.git", null);
        assertThat(retired)
                .as("the old store retired")
                .containsExactly("alpha git@example.org:a/p.git");
    }

    @Test
    void aSlowProjectDoesNotHoldUpAnother() throws Exception {
        // One unreachable remote would leave every other project unreadable for a minute.
        final Projects projects = projects();
        slow = new CountDownLatch(1);
        final CompletableFuture<Void> waiting =
                CompletableFuture.runAsync(
                        () -> projects.put("slow", "git@example.org:a/s.git", null));
        Thread.sleep(200);
        final CompletableFuture<Void> other =
                CompletableFuture.runAsync(
                        () -> projects.put("beta", "git@example.org:a/b.git", null));
        try {
            other.get(2, TimeUnit.SECONDS);
            assertThat(projects.store("beta")).as("the other project given meanwhile").isNotNull();
        } finally {
            slow.countDown();
            waiting.get(5, TimeUnit.SECONDS);
        }
    }
}

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

    private Projects projects(final int most) {
        return new Projects(
                (project, repository, branch) -> open(project, repository, "" + branch), most);
    }

    @Test
    void theSameSourceAgainOpensNothingAndAnotherReplacesTheStore() {
        // The window sends its PUT on every choice; opening again would fetch the project anew.
        final Projects projects = projects(8);
        projects.put("alpha", "git@example.org:a/p.git", null);
        final PlanningStore first = projects.store("alpha");
        projects.put("alpha", "git@example.org:a/p.git", null);
        assertThat(projects.store("alpha")).as("the same store kept").isSameAs(first);
        projects.put("alpha", "git@example.org:a/p.git", "other");
        assertThat(projects.store("alpha")).as("a new store for a new branch").isNotSameAs(first);
        assertThat(opened).as("opened twice, not three times").hasSize(2);
    }

    @Test
    void aFailedReplacementLeavesTheProjectUnknownNeverTheOldOne() {
        // The old store would answer for a source the window no longer means.
        final Projects projects = projects(8);
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
    void oneProjectMoreThanTheBoundIsRefusedBeforeAnythingIsOpened() {
        // Every project keeps clones on disk and a board in memory.
        final Projects projects = projects(2);
        projects.put("alpha", "git@example.org:a/a.git", null);
        projects.put("beta", "git@example.org:a/b.git", null);
        assertThatThrownBy(() -> projects.put("gamma", "git@example.org:a/c.git", null))
                .as("a third project")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal ->
                                assertThat(refusal.name())
                                        .as("its name")
                                        .isEqualTo("TooManyProjects"));
        assertThat(opened).as("the third never opened").hasSize(2);
        projects.put("beta", "git@example.org:a/b2.git", null);
        assertThat(opened).as("replacing a kept project is no new project").hasSize(3);
    }

    @Test
    void aReplacedStoreIsRetiredBeforeTheNewOneOpens() {
        // The old store would go on fetching its source into the clones the new one reads.
        final Projects projects = projects(8);
        projects.put("alpha", "git@example.org:a/p.git", null);
        projects.put("alpha", "git@example.org:a/q.git", null);
        assertThat(retired)
                .as("the old store retired")
                .containsExactly("alpha git@example.org:a/p.git");
    }

    @Test
    void aSlowProjectDoesNotHoldUpAnother() throws Exception {
        // One unreachable remote would leave every other project unreadable for a minute.
        final Projects projects = projects(8);
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

package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrackedStoresTest {

    private static final Workflow WORKFLOW =
            new Workflow(List.of("Idea", "Done"), List.of(), List.of("Done"));

    private static TrackedProject project(final String kind) {
        final boolean local = kind.equals("local");
        return new TrackedProject(
                local ? null : java.net.URI.create("https://forge.example.org"),
                local ? "" : "acme",
                kind,
                local ? "" : "1",
                org.fuin.porelo.planner.store.ProjectRepository.named(List.of("plans")),
                WORKFLOW,
                new Work(Map.of(), List.of()),
                "c1",
                states -> null);
    }

    @Test
    void aProjectIsOpenedByItsTrackersKind() {
        // Harm: a local project is handed to the GitHub store and its ids asked of a forge.
        final List<String> opened = new ArrayList<>();
        final TrackedStores github =
                (project, again) -> {
                    opened.add("github");
                    throw new UnsupportedOperationException("not opened here");
                };
        final TrackedStores local =
                new TrackedStores() {
                    @Override
                    public PlanningStore open(final TrackedProject project, final Source again) {
                        throw new UnsupportedOperationException("opened by name only");
                    }

                    @Override
                    public PlanningStore open(
                            final String name, final TrackedProject project, final Source again) {
                        opened.add("local " + name);
                        return new PlanningStore() {
                            @Override
                            public Board board() {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public Board refresh() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }
                };
        final TrackedStores stores = TrackedStores.byKind(Map.of("github", github, "local", local));

        stores.open("planning", project("local"), () -> project("local"));
        assertThat(opened)
                .as("the local opener, with the project's name")
                .containsExactly("local planning");
        assertThatThrownBy(() -> stores.open("planning", project("jira"), () -> project("jira")))
                .as("a kind no opener keeps")
                .isInstanceOf(StoreException.Unreadable.class);
    }
}

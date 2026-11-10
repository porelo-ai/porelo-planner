package org.fuin.porelo.planner.app;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.fuin.porelo.planner.store.PlanningStore;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/** The projects the window has given the planner, each with its store, while the planner runs. */
public final class Projects {

    /** Opens a project's store from its own repository. */
    @FunctionalInterface
    public interface Opener {

        /**
         * Opens a store.
         *
         * @param project the project's name
         * @param repository its own repository's clone URL
         * @param branch the own repository's branch, or null for the remote's default
         * @return the store
         * @throws Refusal if the URL or the branch is refused
         */
        PlanningStore open(String project, String repository, @Nullable String branch);
    }

    private record Source(String repository, @Nullable String branch) {}

    private record Entry(Source source, PlanningStore store) {}

    private final Map<String, Entry> projects = new ConcurrentHashMap<>();

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private final Set<String> opening = ConcurrentHashMap.newKeySet();

    private final List<Consumer<String>> retired = new CopyOnWriteArrayList<>();

    private final Opener opener;

    private final int most;

    /**
     * Makes a registry.
     *
     * @param opener opens a project's store
     * @param most the most projects kept
     */
    public Projects(final Opener opener, final int most) {
        this.opener = opener;
        this.most = most;
    }

    /**
     * Gives the planner a project; the same source again changes nothing, another replaces it,
     * retiring the old one first, so a failure leaves the project unknown. Projects are given one
     * at a time each, never waiting on another.
     *
     * @param project the project's name, already checked
     * @param repository its own repository's clone URL
     * @param branch the own repository's branch, or null
     */
    public void put(final String project, final String repository, final @Nullable String branch) {
        final Source source = new Source(repository, branch);
        synchronized (locks.computeIfAbsent(project, any -> new Object())) {
            final Entry known = projects.get(project);
            if (known != null && known.source().equals(source)) {
                return;
            }
            synchronized (this) {
                if (known != null) {
                    projects.remove(project);
                    known.store().retire();
                    retired.forEach(listener -> listener.accept(project));
                }
                if (projects.size() + opening.size() >= most) {
                    throw new Refusal(
                            "TooManyProjects",
                            HttpStatus.CONFLICT,
                            "The planner keeps at most " + most + " projects",
                            Map.of("project", project));
                }
                opening.add(project);
            }
            try {
                final PlanningStore store =
                        Objects.requireNonNull(opener.open(project, repository, branch), "store");
                projects.put(project, new Entry(source, store));
            } finally {
                opening.remove(project);
            }
        }
    }

    /** Forgets every project, retiring its store; the mock mode's reset. */
    public synchronized void forgetAll() {
        final List<String> names = List.copyOf(projects.keySet());
        projects.values().forEach(entry -> entry.store().retire());
        projects.clear();
        names.forEach(name -> retired.forEach(listener -> listener.accept(name)));
    }

    /**
     * Tells a listener each project whose store is retired, given anew or forgotten.
     *
     * @param listener takes the project's name
     */
    public void onRetired(final Consumer<String> listener) {
        retired.add(listener);
    }

    /**
     * Answers a known project's store.
     *
     * @param project the project's name
     * @return its store
     */
    public PlanningStore store(final String project) {
        final Entry entry = projects.get(project);
        if (entry == null) {
            throw new Refusal(
                    "NoSuchProject",
                    HttpStatus.NOT_FOUND,
                    "The planner was not given project " + project,
                    Map.of("project", project));
        }
        return entry.store();
    }
}

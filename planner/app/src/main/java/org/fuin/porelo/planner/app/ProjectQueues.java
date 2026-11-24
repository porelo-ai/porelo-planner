package org.fuin.porelo.planner.app;

import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/** Makes the queue of a project whose saves are kept in an outbox beside its work record. */
@FunctionalInterface
interface ProjectQueues {

    /**
     * Makes the project's queue; it opens nothing until {@link ProjectQueue#open} is called.
     *
     * @param place where the project's outbox lies
     * @param store the project's store
     * @param saved tells the project's windows a save the queue ended outside any window's call
     * @return the queue
     */
    ProjectQueue queue(Place place, PlanningStore store, Runnable saved);

    /**
     * Where a project's outbox lies: beside its open work record, under the record's private files
     * and factors.
     *
     * @param environment the planner's environment
     * @param directory the project's state directory
     * @param project the project
     * @param repository its repository
     * @param branch its branch, or null for none
     * @param files the platform's private files
     * @param limits the planner's factors
     */
    record Place(
            Map<String, String> environment,
            Path directory,
            String project,
            String repository,
            @Nullable String branch,
            PrivateFiles files,
            LimitFactors limits) {}
}

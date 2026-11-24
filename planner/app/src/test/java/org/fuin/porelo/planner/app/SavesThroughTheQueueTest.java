package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.fuin.porelo.planner.store.CommandQueue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Every save of a project reaches its store through the project's queue: the GitHub store keeps no
 * two saves apart, so the queue's runner, which claims each issue a command writes, is what keeps
 * two saves of one item from passing each other.
 */
class SavesThroughTheQueueTest {

    /** The store's calls that write to the project, or judge or read for a write. */
    private static final Set<String> WRITES =
            Set.of(
                    "save",
                    "saveResult",
                    "createIssue",
                    "createIdea",
                    "createStoryFromIssues",
                    "addUnlistedIssue",
                    "setFields",
                    "setStoryIssues",
                    "setBlockedBy",
                    "setText",
                    "comment",
                    "correctLabels",
                    "judge",
                    "writeAlone",
                    "readAfter");

    @Test
    void everySaveOfAProjectReachesItsStoreThroughTheQueueButTheStageResult() {
        // Harm: two saves of one item pass each other's check and the second undoes the first,
        // since a save that reaches the store past the queue holds no claim on its items.
        final JavaClasses classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(
                                "org.fuin.porelo.planner.app", "org.fuin.porelo.planner.core");
        final List<String> past = new ArrayList<>();
        for (final JavaClass type : classes) {
            if (storeOrQueue(type)) {
                // A store passing a call on, or a queue writing its commands, is the way in.
                continue;
            }
            // A method reference hands the write on as a call made later, past the queue too.
            final List<JavaAccess<?>> accesses = new ArrayList<>(type.getMethodCallsFromSelf());
            accesses.addAll(type.getMethodReferencesFromSelf());
            for (final JavaAccess<?> call : accesses) {
                if (call.getTargetOwner().isAssignableTo(PlanningStore.class)
                        && WRITES.contains(call.getName())) {
                    past.add(
                            call.getOrigin().getOwner().getSimpleName()
                                    + "."
                                    + call.getOrigin().getName()
                                    + " -> "
                                    + call.getName());
                }
            }
        }

        assertThat(classes).as("the classes read").isNotEmpty();
        // A run's stage result is written past the queue; the GitHub store writes it alone, with
        // no other save beside it, until it runs as a command too.
        assertThat(past)
                .as("the saves that reach a store past the project's queue")
                .containsExactly("WorkRun.land -> saveResult");
    }

    /** Whether a class, or one it is nested in, is a store or a queue. */
    private static boolean storeOrQueue(final JavaClass type) {
        @Nullable JavaClass at = type;
        while (at != null) {
            if (at.isAssignableTo(PlanningStore.class) || at.isAssignableTo(CommandQueue.class)) {
                return true;
            }
            at = at.getEnclosingClass().orElse(null);
        }
        return false;
    }
}

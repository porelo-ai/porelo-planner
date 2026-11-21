package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.planner.app.answers.Story;
import org.fuin.porelo.planner.app.answers.Workflow;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/** The arrays of the planner's answer and store types are registered for the native image. */
class AnswerArraysTest {

    @Test
    void theArrayOfEveryTypeOfThePlannersOwnIsRegistered() {
        // Harm: in the native executable, every answer holding a list failed with 500, since
        // Jackson could not make the array of the list's element type.
        final RuntimeHints hints = new RuntimeHints();
        new AnswerArrays().registerHints(hints, getClass().getClassLoader());

        for (final Class<?> type :
                new Class<?>[] {
                    Story.class, Workflow.class, Issue.class, Board.class, WorkRun.class
                }) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type.arrayType()))
                    .as("the array of " + type.getSimpleName())
                    .accepts(hints);
        }
    }

    @Test
    void theApplicationImportsTheArrays() {
        assertThat(
                        PlannerApplication.class
                                .getAnnotation(
                                        org.springframework.context.annotation.ImportRuntimeHints
                                                .class)
                                .value())
                .contains(AnswerArrays.class);
    }
}

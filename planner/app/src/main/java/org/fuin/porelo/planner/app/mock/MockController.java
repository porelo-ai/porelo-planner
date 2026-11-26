package org.fuin.porelo.planner.app.mock;

import org.fuin.porelo.planner.app.ChangesRegistry;
import org.fuin.porelo.planner.app.MockHeldSaves;
import org.fuin.porelo.planner.app.Projects;
import org.fuin.porelo.planner.app.WorkRecord;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Resets the mock board between the window's tests; outside the mock mode the path answers as one
 * that does not exist.
 */
@RestController
@ImportRuntimeHints(MockController.BoardHints.class)
@RegisterReflectionForBinding(
        classes = {MockStore.BoardFile.class, MockStore.RuleFile.class, MockStore.StoryFile.class})
public class MockController {

    /** Takes the mock board into a native image, which holds no resource it is not told of. */
    static final class BoardHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(final RuntimeHints hints, final @Nullable ClassLoader loader) {
            hints.resources().registerPattern(MockStore.BOARD);
            // The board's issues are read through this constructor, which Jackson finds only by
            // reflection; its stories are records bound above.
            hints.reflection()
                    .registerType(
                            WithoutVersions.IssueMixIn.class,
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
    }

    private static final String RESET = "/mock/reset";

    private static final String REVIEWER = "/mock/projects/{project}/reviewer";

    private static final String HELD = "/mock/projects/{project}/held";

    private final ObjectProvider<MockMode> mode;

    private final ObjectProvider<MockStore> store;

    private final Projects projects;

    private final WorkRecord records;

    private final MockHeldSaves held;

    private final ChangesRegistry changes;

    /**
     * Makes the controller.
     *
     * @param mode present only in the mock mode
     * @param store the mock store, made only when the mock mode is on
     * @param projects the projects given, forgotten by a reset
     * @param records the work records, held in memory in the mock mode
     * @param held the held save the mock gives on request
     * @param changes told of a held save given, so the window's board carries it
     */
    public MockController(
            final ObjectProvider<MockMode> mode,
            final ObjectProvider<MockStore> store,
            final Projects projects,
            final WorkRecord records,
            final MockHeldSaves held,
            final ChangesRegistry changes) {
        this.mode = mode;
        this.store = store;
        this.projects = projects;
        this.records = records;
        this.held = held;
        this.changes = changes;
    }

    /**
     * Restores the mock board and forgets every project given, so each of the window's tests starts
     * alike.
     *
     * @return no content
     * @throws NoResourceFoundException outside the mock mode, as for any unknown path
     */
    @PostMapping(RESET)
    public ResponseEntity<Void> reset() throws NoResourceFoundException {
        // Decided at run time, not by @Profile: a native image fixes its beans when it is built.
        if (mode.getIfAvailable() == null) {
            throw new NoResourceFoundException(HttpMethod.POST, RESET, RESET.substring(1));
        }
        store.getObject().reset();
        projects.forgetAll();
        return ResponseEntity.noContent().build();
    }

    /**
     * Gives a project given before the record of {@link MockSeed}: one pushed issue whose work
     * waits at its gate push, its reviewer offered there, until the next reset.
     *
     * @param project the project
     * @return no content, or not found for a project not given
     * @throws NoResourceFoundException outside the mock mode, as for any unknown path
     */
    @PostMapping(REVIEWER)
    public ResponseEntity<Void> reviewer(@PathVariable("project") final String project)
            throws NoResourceFoundException {
        if (mode.getIfAvailable() == null) {
            throw new NoResourceFoundException(HttpMethod.POST, REVIEWER, REVIEWER.substring(1));
        }
        if (!records.plant(project, MockSeed.of(MockStore.fromClassPath().board()))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Gives a project given before the held save of {@link MockHeldSaves}, which its board then
     * carries until it is resent, dropped or the mock board reset.
     *
     * @param project the project
     * @return no content, or not found for a project not given
     * @throws NoResourceFoundException outside the mock mode, as for any unknown path
     */
    @PostMapping(HELD)
    public ResponseEntity<Void> held(@PathVariable("project") final String project)
            throws NoResourceFoundException {
        if (mode.getIfAvailable() == null) {
            throw new NoResourceFoundException(HttpMethod.POST, HELD, HELD.substring(1));
        }
        if (!held.hold(project)) {
            return ResponseEntity.notFound().build();
        }
        changes.saved(project);
        return ResponseEntity.noContent().build();
    }
}

package org.fuin.porelo.planner.app.mock;

import org.fuin.porelo.planner.app.Projects;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Resets the mock board between the window's tests; outside the mock mode the path answers as one
 * that does not exist.
 */
@RestController
@ImportRuntimeHints(MockController.BoardHints.class)
@RegisterReflectionForBinding(classes = {MockStore.BoardFile.class, MockStore.RuleFile.class})
public class MockController {

    /** Takes the mock board into a native image, which holds no resource it is not told of. */
    static final class BoardHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(final RuntimeHints hints, final @Nullable ClassLoader loader) {
            hints.resources().registerPattern(MockStore.BOARD);
        }
    }

    private static final String RESET = "/mock/reset";

    private final ObjectProvider<MockMode> mode;

    private final ObjectProvider<MockStore> store;

    private final Projects projects;

    /**
     * Makes the controller.
     *
     * @param mode present only in the mock mode
     * @param store the mock store, made only when the mock mode is on
     * @param projects the projects given, forgotten by a reset
     */
    public MockController(
            final ObjectProvider<MockMode> mode,
            final ObjectProvider<MockStore> store,
            final Projects projects) {
        this.mode = mode;
        this.store = store;
        this.projects = projects;
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
}

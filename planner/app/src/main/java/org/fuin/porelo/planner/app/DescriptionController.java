package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the description the planner was built with, so a caller such as an agents' tool server
 * reads the operations of the planner that runs, signed like every call.
 */
@RestController
@ImportRuntimeHints(DescriptionController.DescriptionHints.class)
public class DescriptionController {

    /**
     * Takes the converted description into a native image, which holds no resource it is not told
     * of.
     */
    static final class DescriptionHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(final RuntimeHints hints, final @Nullable ClassLoader loader) {
            hints.resources().registerPattern(Description.RESOURCE);
        }
    }

    private final byte[] description;

    /** Reads the converted description once, failing the start when the build left none. */
    public DescriptionController() {
        try (InputStream in =
                DescriptionController.class
                        .getClassLoader()
                        .getResourceAsStream(Description.RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The build left no " + Description.RESOURCE);
            }
            this.description = in.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read " + Description.RESOURCE, e);
        }
    }

    /**
     * Answers the planner's own description as JSON.
     *
     * @return the description's bytes
     */
    @GetMapping(value = "/openapi", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> description() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(description.clone());
    }
}

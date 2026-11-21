package org.fuin.porelo.planner.app;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

/**
 * Registers, for the native image, the array type of every class of the planner's own, whose
 * records go out in its answers and events and into the records it keeps. Jackson makes an array of
 * a list's element type when it looks at the constructors of the list's class, such as the one
 * {@code List.of} gives; Apache Fory's GraalVM feature registers those classes' constructors for
 * the board copy, so without these arrays every answer holding a list fails in the native
 * executable.
 */
final class AnswerArrays implements RuntimeHintsRegistrar {

    /**
     * The planner's own packages: its answers, events and the records it keeps hold types of all of
     * them in lists.
     */
    static final String[] PACKAGES = {"org.fuin.porelo.planner"};

    @Override
    public void registerHints(final RuntimeHints hints, final @Nullable ClassLoader classLoader) {
        final ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            final org.springframework.beans.factory.annotation
                                            .AnnotatedBeanDefinition
                                    definition) {
                        return true;
                    }
                };
        scanner.addIncludeFilter((reader, factory) -> true);
        if (classLoader != null) {
            scanner.setResourceLoader(
                    new org.springframework.core.io.DefaultResourceLoader(classLoader));
        }
        for (final String name : PACKAGES) {
            for (final var found : scanner.findCandidateComponents(name)) {
                final String type = found.getBeanClassName();
                if (type != null) {
                    hints.reflection()
                            .registerType(
                                    ClassUtils.resolveClassName(type, classLoader).arrayType());
                }
            }
        }
    }
}

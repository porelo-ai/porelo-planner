package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The flow decides by a role's definition, so no word of a role stands in core's code. */
class NoRoleWordInCoreTest {

    /** The words that named a role in code, as a string, a constant or a type. */
    private static final Pattern WORD =
            Pattern.compile("\\b(MAIN|BUILDER|READER|DISCUSSION|AUTHOR|REVIEWER|RunRole)\\b");

    @Test
    void noSourceOfCoreNamesARoleByAWord() throws IOException {
        // Harm: a role a project adds is refused or misread by a branch on a fixed role word.
        final Path sources = Path.of("src/main/java/org/fuin/porelo/planner/core");
        final List<String> found = new ArrayList<>();
        final List<Path> files;
        try (Stream<Path> listed = Files.list(sources)) {
            files = listed.filter(file -> file.toString().endsWith(".java")).sorted().toList();
        }
        assertThat(files)
                .as("core's sources are found where the test looks")
                .hasSizeGreaterThan(30);
        for (final Path file : files) {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                final Matcher matcher = WORD.matcher(lines.get(index));
                if (matcher.find()) {
                    found.add(file.getFileName() + ":" + (index + 1) + " " + matcher.group());
                }
            }
        }
        assertThat(found).as("no role word in core, comments and strings included").isEmpty();
    }
}

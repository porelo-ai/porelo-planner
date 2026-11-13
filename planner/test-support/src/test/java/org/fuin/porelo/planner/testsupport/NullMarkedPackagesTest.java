package org.fuin.porelo.planner.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NullMarkedPackagesTest {

    /** The planner's modules, seen from this module's directory. */
    private static final Path PLANNER = Path.of("..");

    /** The CI's clean-up, a module beside the planner's parts. */
    private static final Path CLEANUP = Path.of("../../cleanup");

    @Test
    void everyMainPackageOfThePlannerIsNullMarked() throws Exception {
        // An unmarked package bypasses NullAway, and an unchecked null there crashes a request.
        final List<Path> packages;
        try (Stream<Path> paths = Stream.concat(Files.walk(PLANNER), Files.walk(CLEANUP))) {
            packages =
                    paths.filter(path -> path.toString().contains("/src/main/java/"))
                            .filter(path -> path.toString().endsWith(".java"))
                            .map(Path::getParent)
                            .distinct()
                            .sorted()
                            .toList();
        }
        assertThat(packages)
                .as("the packages of every part are checked")
                .contains(
                        PLANNER.resolve("core/src/main/java/org/fuin/porelo/planner/core"),
                        PLANNER.resolve("store/api/src/main/java/org/fuin/porelo/planner/store"),
                        PLANNER.resolve(
                                "store/git/src/main/java/org/fuin/porelo/planner/store/git"),
                        PLANNER.resolve("app/src/main/java/org/fuin/porelo/planner/app"),
                        CLEANUP.resolve("src/main/java/org/fuin/porelo/planner/cleanup"));
        for (final Path pkg : packages) {
            final Path info = pkg.resolve("package-info.java");
            assertThat(info).as("%s declares its null safety", pkg).exists();
            assertThat(Files.readString(info))
                    .as("%s is checked by NullAway", pkg)
                    .containsPattern("(?m)^@(?:org\\.jspecify\\.annotations\\.)?NullMarked\\b");
        }
    }

    @Test
    void thePackageOfTheGeneratedAnswerRecordsIsNullMarked() throws Exception {
        // An unmarked generated package makes NullAway check nothing in the answer records.
        final Path info =
                PLANNER.resolve(
                        "app/target/generated-sources/openapi/src/main/java/org/fuin/porelo/planner"
                                + "/app/answers/package-info.java");
        assertThat(info)
                .as("the answer records' package, generated before this module's tests run")
                .exists();
        assertThat(Files.readString(info))
                .as("the generated answer records are checked by NullAway")
                .containsPattern("(?m)^@(?:org\\.jspecify\\.annotations\\.)?NullMarked\\b");
    }
}

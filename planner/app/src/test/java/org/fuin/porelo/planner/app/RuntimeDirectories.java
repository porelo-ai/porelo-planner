package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime directories of the tests' own, as the system makes them, and reading what is in them. */
final class RuntimeDirectories {

    private static final Pattern ADDRESS =
            Pattern.compile("\\{\"port\":(\\d+),\"token\":\"([A-Za-z0-9_-]{43})\"}\\n");

    private RuntimeDirectories() {}

    /** A new directory, {@code 0700}, apart from the account's real one and a running planner. */
    static Path create() throws IOException {
        return Files.createTempDirectory(
                "porelo-planner-runtime",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    /** The port and token a planner wrote under a runtime directory. */
    static Address read(final Path runtime) throws IOException {
        final String text =
                Files.readString(
                        runtime.resolve(AddressFile.DIRECTORY).resolve(AddressFile.FILE),
                        StandardCharsets.UTF_8);
        final Matcher matcher = ADDRESS.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalStateException("the address file holds no port and token");
        }
        return new Address(Integer.parseInt(matcher.group(1)), matcher.group(2));
    }

    /** Deletes a test's runtime directory and everything in it. */
    static void delete(final Path runtime) throws IOException {
        try (var paths = Files.walk(runtime)) {
            for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Where a planner listens and what it lets in. */
    record Address(int port, String token) {}
}

package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runtime directories of the tests' own, as the system makes them, and reading what is in them. */
final class RuntimeDirectories {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        final JsonNode json = JSON.readTree(text);
        if (!text.endsWith("}\n")
                || !json.path("port").isInt()
                || !TOKEN.matcher(json.path("token").asString("")).matches()) {
            throw new IllegalStateException("the address file holds no port and token");
        }
        return new Address(json.path("port").asInt(), json.path("token").asString());
    }

    /** The log file a planner named in its address file, or {@code null} when it named none. */
    static @Nullable String log(final Path runtime) throws IOException {
        final JsonNode log =
                JSON.readTree(
                                Files.readString(
                                        runtime.resolve(AddressFile.DIRECTORY)
                                                .resolve(AddressFile.FILE),
                                        StandardCharsets.UTF_8))
                        .path("log");
        return log.isString() ? log.asString() : null;
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

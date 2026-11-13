package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runtime directories of the tests' own, as the system makes them, and reading what is in them. */
final class RuntimeDirectories {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Whether the tests run on Windows, where the planner's base is {@code LOCALAPPDATA}. */
    static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private RuntimeDirectories() {}

    /**
     * A new directory apart from the account's real one and a running planner: {@code 0700} on
     * Linux, the temporary directory's own access list on Windows, named without an 8.3 short name,
     * which the planner's checks refuse.
     */
    static Path create() throws IOException {
        if (WINDOWS) {
            return Files.createTempDirectory("porelo-planner-runtime").toRealPath();
        }
        return Files.createTempDirectory(
                "porelo-planner-runtime",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    /**
     * What points a planner at a test's directory for its address file, log and work record: the
     * runtime and state directories on Linux, {@code LOCALAPPDATA} on Windows.
     */
    static Map<String, String> environment(final Path runtime) {
        if (WINDOWS) {
            return Map.of("LOCALAPPDATA", runtime.toString());
        }
        return Map.of("XDG_RUNTIME_DIR", runtime.toString(), "XDG_STATE_HOME", runtime.toString());
    }

    /** The system's own variables a planner started with an empty environment needs. */
    static Map<String, String> system() {
        if (WINDOWS) {
            // Without SystemRoot a Windows process has no sockets, and PowerShell is found by it.
            final String root = System.getenv("SystemRoot");
            if (root == null || root.isBlank()) {
                throw new IllegalStateException("this Windows test runs without SystemRoot");
            }
            return Map.of("SystemRoot", root, "PATH", root + "\\System32");
        }
        return Map.of("PATH", "/usr/bin:/bin");
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

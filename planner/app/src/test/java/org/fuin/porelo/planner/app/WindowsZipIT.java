package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The Windows ZIP the profile {@code windows} packs, as a person unpacks it; run by its verify. */
@EnabledIfSystemProperty(named = "planner.zip", matches = ".+")
class WindowsZipIT {

    @Test
    void theZipHoldsTheTestedExecutableWithItsLicensesAndNoticesAndNothingElse() throws Exception {
        // A ZIP without the licenses and notices ships the libraries against their terms; one with
        // another executable than the tested one ships what no test started; a stray file ships
        // whatever the build left in its folder.
        final Path zip = Path.of(System.getProperty("planner.zip", "missing"));
        final Path executable = Path.of(System.getProperty("planner.executable", "missing"));
        assertThat(zip).as("the ZIP the profile packed").isRegularFile();
        final Map<String, String> entries = new LinkedHashMap<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (final ZipEntry entry : java.util.Collections.list(file.entries())) {
                assertThat(entry.isDirectory())
                        .as("%s is a file, not a directory", entry.getName())
                        .isFalse();
                assertThat(entry.getSize()).as("%s holds something", entry.getName()).isPositive();
                try (InputStream in = file.getInputStream(entry)) {
                    entries.put(entry.getName(), sha256(in));
                }
            }
        }
        assertThat(entries.keySet())
                .as("the ZIP's entries, flat")
                .containsExactlyInAnyOrder(
                        executable.getFileName().toString(),
                        "LICENSE",
                        "LICENSE-GPL-3.0",
                        "THIRD-PARTY-NOTICES",
                        "copyright",
                        "bom.json");
        try (InputStream tested = Files.newInputStream(executable)) {
            assertThat(entries.get(executable.getFileName().toString()))
                    .as("the executable in the ZIP is the tested one, by its SHA-256")
                    .isEqualTo(sha256(tested));
        }
    }

    private static String sha256(final InputStream in) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
        final byte[] buffer = new byte[64 * 1024];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

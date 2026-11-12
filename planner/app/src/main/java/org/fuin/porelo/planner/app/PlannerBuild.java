package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The planner's package version and the commit it was built from, as its build wrote them.
 *
 * @param version {@code x.y.z} or {@code x.y.z~snapshot.N}, as the packages carry it
 * @param commit the 40 lowercase hex digits of the commit, or {@code null} for a build without a
 *     checkout
 */
public record PlannerBuild(String version, @Nullable String commit) {

    /** The resource the build writes. */
    public static final String RESOURCE = "planner-build.properties";

    /** The form of a version, as the description's {@code Health.version} gives it. */
    static final Pattern VERSION =
            Pattern.compile("(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){2}(~snapshot\\.(0|[1-9][0-9]*))?");

    /** The form of a commit, as the description's {@code Health.commit} gives it. */
    static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    /**
     * Refuses a version or commit of another form.
     *
     * @param version the package version
     * @param commit the commit, or {@code null}
     */
    public PlannerBuild {
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "the planner's version '"
                            + version
                            + "' is neither x.y.z nor x.y.z~snapshot.N; the build wrote it wrong");
        }
        if (commit != null && !COMMIT.matcher(commit).matches()) {
            throw new IllegalArgumentException(
                    "the planner's commit '"
                            + commit
                            + "' is not 40 lowercase hex digits; the build wrote it wrong");
        }
    }

    /**
     * Reads the build's resource.
     *
     * @param loader the class loader that holds it
     * @param resource the resource's name
     * @return what it states
     * @throws IllegalStateException when it is missing or malformed
     */
    public static PlannerBuild read(final ClassLoader loader, final String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "the planner's build resource " + resource + " is missing");
            }
            final Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            final String version = properties.getProperty("version");
            final String commit = properties.getProperty("commit");
            if (version == null || commit == null) {
                throw new IllegalStateException(
                        "the planner's build resource " + resource + " lacks version or commit");
            }
            return new PlannerBuild(version, commit.isEmpty() ? null : commit);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "the planner's build resource "
                            + resource
                            + " cannot be read: "
                            + e.getMessage(),
                    e);
        }
    }
}

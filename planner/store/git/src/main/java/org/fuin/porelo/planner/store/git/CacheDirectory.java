package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Ids;

/**
 * The planner's clones of a project, under {@code $XDG_CACHE_HOME/porelo-planner/<project>/}:
 * directories of the person's alone, reached through no link, so nobody else can read the clones or
 * swap one for their own.
 */
public final class CacheDirectory {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rwx------");

    private static final int OTHERS_WRITE = 0002;

    private static final int STICKY = 01000;

    private CacheDirectory() {}

    /**
     * Makes or checks a project's cache directory.
     *
     * @param environment the planner's environment, for {@code XDG_CACHE_HOME} and {@code HOME}
     * @param project the project's name
     * @return the directory, the person's alone
     * @throws IllegalStateException when it, or a directory on its way, could be changed by another
     *     account
     */
    public static Path of(final Map<String, String> environment, final String project) {
        Ids.name(project);
        final Path base = base(environment);
        try {
            final int uid = (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
            requireNoWayRound(base, uid);
            final Path planner = ownersAlone(base.resolve("porelo-planner"), uid);
            return ownersAlone(planner.resolve(project), uid);
        } catch (final IOException e) {
            throw new UncheckedIOException("the cache directory could not be made", e);
        }
    }

    private static Path base(final Map<String, String> environment) {
        final String cache = environment.get("XDG_CACHE_HOME");
        if (cache != null && !cache.isEmpty() && Path.of(cache).isAbsolute()) {
            return Path.of(cache);
        }
        final String home = environment.get("HOME");
        if (home == null || !Path.of(home).isAbsolute()) {
            throw new IllegalStateException(
                    "Neither XDG_CACHE_HOME nor HOME names an absolute directory");
        }
        return Path.of(home, ".cache");
    }

    private static Path ownersAlone(final Path directory, final int uid) throws IOException {
        try {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            // The umask may have taken bits the attribute asked for.
            Files.setPosixFilePermissions(directory, OWNER_ONLY);
        } catch (final FileAlreadyExistsException exists) {
            // Checked below, whoever made it.
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(directory + " is not a directory, or is a link");
        }
        final int owner =
                (Integer) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        if (owner != uid) {
            throw new IllegalStateException(
                    directory + " belongs to account " + owner + ", not to this one");
        }
        final Set<PosixFilePermission> mode =
                Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
        if (!mode.equals(OWNER_ONLY)) {
            throw new IllegalStateException(
                    directory
                            + " has mode "
                            + PosixFilePermissions.toString(mode)
                            + ", not rwx------");
        }
        return directory;
    }

    /**
     * Refuses a base reached through a link or below a directory another account could rename it
     * out of.
     */
    private static void requireNoWayRound(final Path base, final int uid) throws IOException {
        if (!base.equals(base.normalize())) {
            throw new IllegalStateException(base + " is not in its plain form");
        }
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(base, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        if (!base.toRealPath().equals(base)) {
            throw new IllegalStateException(base + " is reached through a link");
        }
        for (Path dir = base; dir != null; dir = dir.getParent()) {
            final int owner =
                    (Integer) Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            final int mode =
                    (Integer) Files.getAttribute(dir, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            if (owner != 0 && owner != uid) {
                throw new IllegalStateException(
                        dir + " belongs to account " + owner + ", neither root nor this one");
            }
            if ((mode & OTHERS_WRITE) != 0 && (mode & STICKY) == 0) {
                throw new IllegalStateException(
                        dir + " is a directory others can write, without the sticky bit");
            }
        }
    }
}

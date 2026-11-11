package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The planner's private files on Linux: directories of mode {@code rwx------} and files of mode
 * {@code rw-------}, owned by the account, under directories only root or the account can change
 * or, when others may write them, sticky ones.
 */
final class PosixPrivateFiles implements PrivateFiles {

    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
            PosixFilePermissions.fromString("rw-------");

    /** Group or others may write: refused above a runtime or state directory. */
    private static final int GROUP_OR_OTHERS_WRITE = 0022;

    /** Others may write: refused above the clones, whose directories a group may share. */
    private static final int OTHERS_WRITE = 0002;

    private static final int STICKY = 01000;

    private static final Path RUN_USER = Path.of("/run/user");

    @Override
    public boolean windows() {
        return false;
    }

    @Override
    public Path base(final Kind kind, final Map<String, String> environment) {
        return switch (kind) {
            case RUNTIME -> runtime(environment);
            case STATE -> under(environment, "XDG_STATE_HOME", ".local/state");
            case CACHE -> under(environment, "XDG_CACHE_HOME", ".cache");
        };
    }

    @Override
    public List<String> steps(final Kind kind) {
        return List.of("porelo-planner");
    }

    @Override
    public Path directories(
            final Path base, final boolean create, final Kind kind, final List<String> steps) {
        try {
            if (!base.isAbsolute() || !base.equals(base.normalize())) {
                throw new IllegalStateException(base + " is not in its plain form");
            }
            final int uid = ownUid();
            if (create && !Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(
                        base, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
            }
            // A runtime directory is the account's alone.
            if (kind == Kind.RUNTIME) {
                requireOwnersAlone(base, uid);
            }
            requireNoWayRound(base, uid, kind == Kind.CACHE ? OTHERS_WRITE : GROUP_OR_OTHERS_WRITE);
            Path directory = base;
            for (final String step : steps) {
                directory = directory.resolve(step);
                createOwnerOnly(directory);
                requireOwnersAlone(directory, uid);
            }
            return directory;
        } catch (final IOException e) {
            throw new UncheckedIOException(base + " cannot be used: " + e.getMessage(), e);
        }
    }

    @Override
    public FileAttribute<?>[] ownerOnlyFile() {
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)};
    }

    @Override
    public void requireOwnFile(final Path file, final boolean alone) {
        try {
            final BasicFileAttributes attributes =
                    Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IllegalStateException(file + " is not a file, or is a link");
            }
            final int owner =
                    (Integer) Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (owner != ownUid()) {
                throw new IllegalStateException(file + " belongs to another account");
            }
            if (alone
                    && (!Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                                    .equals(OWNER_ONLY_FILE)
                            || ((Number)
                                                    Files.getAttribute(
                                                            file,
                                                            "unix:nlink",
                                                            LinkOption.NOFOLLOW_LINKS))
                                            .intValue()
                                    != 1)) {
                throw new IllegalStateException(
                        file + " is open to others or has a second name, not rw------- alone");
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(file + " cannot be checked: " + e.getMessage(), e);
        }
    }

    /**
     * This account's uid.
     *
     * @return the uid the process runs as
     * @throws IOException when it cannot be read
     */
    static int ownUid() throws IOException {
        return (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
    }

    private static Path runtime(final Map<String, String> environment) {
        final String runtime = environment.getOrDefault("XDG_RUNTIME_DIR", "");
        if (!runtime.isBlank()) {
            return Path.of(runtime);
        }
        // The window reads the same place when XDG_RUNTIME_DIR is unset.
        try {
            return RUN_USER.resolve(String.valueOf(ownUid()));
        } catch (final IOException e) {
            throw new IllegalStateException("this account's uid cannot be read: " + e, e);
        }
    }

    private static Path under(
            final Map<String, String> environment, final String variable, final String inHome) {
        final String own = environment.get(variable);
        if (own != null && !own.isEmpty() && Path.of(own).isAbsolute()) {
            return Path.of(own);
        }
        final String home = environment.get("HOME");
        if (home == null || !Path.of(home).isAbsolute()) {
            throw new IllegalStateException(
                    "Neither " + variable + " nor HOME names an absolute directory");
        }
        return Path.of(home).resolve(inHome);
    }

    /** Makes the directory owner-only unless it exists; one made by a racing start is checked. */
    private static void createOwnerOnly(final Path directory) throws IOException {
        try {
            Files.createDirectory(
                    directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        } catch (final FileAlreadyExistsException exists) {
            return;
        }
        // The umask may have taken bits the attribute asked for; owner-only is set again exactly.
        Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
    }

    private static void requireOwnersAlone(final Path path, final int uid) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(path + " is not a directory, or is a link");
        }
        final int owner = (Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        if (owner != uid) {
            throw new IllegalStateException(
                    path + " belongs to account " + owner + ", not to this one (" + uid + ")");
        }
        final Set<PosixFilePermission> mode =
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!mode.equals(OWNER_ONLY_DIRECTORY)) {
            throw new IllegalStateException(
                    path + " has mode " + PosixFilePermissions.toString(mode) + ", not rwx------");
        }
    }

    /**
     * Refuses a base reached through a link anywhere on its way, or lying under a directory another
     * account could rename it out of: each lets the place be swapped between the check and the
     * write.
     */
    private static void requireNoWayRound(final Path base, final int uid, final int writable)
            throws IOException {
        final Path real = base.toRealPath();
        if (!real.equals(base)) {
            throw new IllegalStateException(base + " is reached through a link, as " + real);
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
            if ((mode & writable) != 0 && (mode & STICKY) == 0) {
                throw new IllegalStateException(
                        dir
                                + " is a directory "
                                + (writable == OTHERS_WRITE ? "others" : "its group or others")
                                + " can write, without the sticky bit");
            }
        }
    }
}

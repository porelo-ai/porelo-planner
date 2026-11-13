package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks the program {@code PORELO_EXECUTABLE} names before Git may run it with a login: an
 * absolute path that is its own real path, a regular file the account may run, which no other
 * account could change, and no directory above it either, so nothing can swap it between the check
 * and Git's run.
 */
interface PoreloExecutable {

    /**
     * Checks a path.
     *
     * @param value the variable's value
     * @return the path, exactly as checked
     * @throws IllegalStateException or {@link UncheckedIOException} when it is refused or cannot be
     *     checked
     */
    String checked(String value);

    /**
     * The platform's own check.
     *
     * @param environment the planner's environment
     * @param windows whether this is Windows
     * @return the check
     */
    static PoreloExecutable of(final Map<String, String> environment, final boolean windows) {
        return windows
                ? new OnWindows(
                        new OnWindows.Paths() {
                            @Override
                            public String real(final String path) throws IOException {
                                return Path.of(path).toRealPath().toString();
                            }

                            @Override
                            public boolean executable(final String path) {
                                return Files.isExecutable(Path.of(path));
                            }
                        },
                        (protect, paths) ->
                                WindowsPrivateFiles.answer(
                                        WindowsPrivateFiles.powershellCall(
                                                environment, protect, paths),
                                        "powershell",
                                        Duration.ofSeconds(60)))
                : new OnPosix(new OnPosix.RealNodes());
    }

    /** The check on Linux, by owner and mode, over what {@link Nodes} reads. */
    final class OnPosix implements PoreloExecutable {

        /** What the file system says of a path, so the rules can be proven over a fake. */
        interface Nodes {

            /**
             * This account's uid.
             *
             * @return the uid
             * @throws IOException when it cannot be read
             */
            int self() throws IOException;

            /**
             * A path's real path, every link followed.
             *
             * @param path the path
             * @return the real path
             * @throws IOException when it cannot be read
             */
            Path real(Path path) throws IOException;

            /**
             * A path itself, a link not followed.
             *
             * @param path the path
             * @return its kind, owner and mode
             * @throws IOException when it cannot be read
             */
            Node node(Path path) throws IOException;
        }

        /**
         * One path as the file system has it.
         *
         * @param kind what it is
         * @param owner its owner's uid
         * @param mode its mode, the group bits holding a POSIX ACL's mask
         * @param executable whether this account may run it
         */
        record Node(Kind kind, int owner, int mode, boolean executable) {}

        /** What a path is. */
        enum Kind {
            /** A regular file. */
            FILE,
            /** A directory. */
            DIRECTORY,
            /** A link or anything else. */
            OTHER
        }

        private final Nodes nodes;

        OnPosix(final Nodes nodes) {
            this.nodes = nodes;
        }

        @Override
        public String checked(final String value) {
            final Path path = Path.of(value);
            if (!path.isAbsolute() || !path.equals(path.normalize())) {
                throw new IllegalStateException(value + " is not an absolute path in plain form");
            }
            try {
                final Path real = nodes.real(path);
                if (!real.equals(path)) {
                    throw new IllegalStateException(value + " is reached through a link");
                }
                final int uid = nodes.self();
                final Node file = nodes.node(path);
                if (file.kind() != Kind.FILE || !file.executable()) {
                    throw new IllegalStateException(
                            value + " is not a regular file this account may run");
                }
                if (file.owner() != 0 && file.owner() != uid) {
                    throw new IllegalStateException(
                            value
                                    + " belongs to account "
                                    + file.owner()
                                    + ", neither root nor this one");
                }
                if ((file.mode() & PosixPrivateFiles.GROUP_OR_OTHERS_WRITE) != 0) {
                    throw new IllegalStateException(
                            value + " is a file its group or others can write");
                }
                for (Path dir = path.getParent(); dir != null; dir = dir.getParent()) {
                    final Node node = nodes.node(dir);
                    if (node.kind() != Kind.DIRECTORY) {
                        throw new IllegalStateException(dir + " is not a directory, or is a link");
                    }
                    PosixPrivateFiles.requireSafeAncestor(
                            dir,
                            node.owner(),
                            node.mode(),
                            uid,
                            PosixPrivateFiles.GROUP_OR_OTHERS_WRITE);
                }
                return value;
            } catch (final IOException e) {
                throw new UncheckedIOException(value + " cannot be checked: " + e.getMessage(), e);
            }
        }

        /** The real file system. */
        static final class RealNodes implements Nodes {

            @Override
            public int self() throws IOException {
                return PosixPrivateFiles.ownUid();
            }

            @Override
            public Path real(final Path path) throws IOException {
                return path.toRealPath();
            }

            @Override
            public Node node(final Path path) throws IOException {
                final BasicFileAttributes attributes =
                        Files.readAttributes(
                                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                final Kind kind =
                        attributes.isRegularFile()
                                ? Kind.FILE
                                : attributes.isDirectory() ? Kind.DIRECTORY : Kind.OTHER;
                return new Node(
                        kind,
                        (Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS),
                        (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS),
                        Files.isExecutable(path));
            }
        }
    }

    /**
     * The check on Windows, by owner and access control list as {@link WindowsAccess} reads them,
     * every entry counted, inherited ones and groups too.
     */
    final class OnWindows implements PoreloExecutable {

        /** A drive's absolute path, never a UNC, device or {@code \\?\} path. */
        private static final Pattern DRIVE_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\x00-\\x1f]*");

        /** What Java says of a Windows path, so the rules can be proven over a fake. */
        interface Paths {

            /**
             * A path's real path, every link and junction followed.
             *
             * @param path the path
             * @return the real path
             * @throws IOException when it cannot be read
             */
            String real(String path) throws IOException;

            /**
             * Whether this account may run the file.
             *
             * @param path the path
             * @return whether it may
             */
            boolean executable(String path);
        }

        private final Paths paths;

        private final WindowsPrivateFiles.Shell shell;

        OnWindows(final Paths paths, final WindowsPrivateFiles.Shell shell) {
            this.paths = paths;
            this.shell = shell;
        }

        @Override
        public String checked(final String value) {
            final List<String> steps = List.of(value.split("\\\\", -1));
            if (!DRIVE_PATH.matcher(value).matches()
                    || steps.contains("..")
                    || steps.contains(".")
                    || steps.subList(1, steps.size()).contains("")) {
                throw new IllegalStateException(
                        value + " is not a drive's absolute path in plain form");
            }
            try {
                if (!paths.real(value).equals(value)) {
                    throw new IllegalStateException(
                            value + " is reached through a link or junction");
                }
            } catch (final IOException e) {
                throw new UncheckedIOException(value + " cannot be checked: " + e.getMessage(), e);
            }
            if (!paths.executable(value)) {
                throw new IllegalStateException(value + " is not a file this account may run");
            }
            final List<String> above = new ArrayList<>();
            String dir = steps.getFirst() + "\\";
            above.add(dir);
            for (int i = 1; i < steps.size() - 1; i++) {
                dir = (i == 1 ? dir : dir + "\\") + steps.get(i);
                above.add(dir);
            }
            final StringBuilder asked = new StringBuilder();
            for (final String path : above) {
                asked.append(path).append('\n');
            }
            asked.append(value).append('\n');
            final WindowsAccess access =
                    WindowsAccess.parse(shell.run("", asked.toString()), above.size() + 1);
            for (int i = 0; i < above.size(); i++) {
                access.requireSafeAncestor(above.get(i), i);
            }
            access.requireSafeProgram(value, above.size());
            return value;
        }
    }
}

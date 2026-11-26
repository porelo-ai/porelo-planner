package org.fuin.porelo.planner.store.files;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * A file of the account's own opened and judged on Linux through the open descriptor itself, by the
 * C library's own calls: never through a link, never waiting on a pipe, and the file read the one
 * judged. Java's own open can neither refuse to wait on a pipe nor tell what the descriptor it
 * opened is.
 *
 * <p>Only the directory's last step is held this way; the directories above it are the account's
 * alone, so only the account's own processes could swap one, and the account is trusted.
 */
final class LinuxFiles {

    private static final int O_RDONLY = 0;

    private static final int O_WRONLY = 1;

    private static final int O_CREAT = 0100;

    private static final int O_NONBLOCK = 04000;

    private static final int O_CLOEXEC = 02000000;

    private static final int AT_EMPTY_PATH = 0x1000;

    private static final int STATX_BASIC_STATS = 0x7ff;

    private static final int S_IFMT = 0170000;

    private static final int S_IFREG = 0100000;

    /** A lock of the open file description, so it holds against Java's own file lock too. */
    private static final int F_OFD_SETLK = 37;

    private static final int F_OFD_SETLKW = 38;

    private static final short F_WRLCK = 1;

    /** {@code struct flock}, the same on x86_64 and aarch64: the whole file from its start. */
    private static final long FLOCK_SIZE = 32;

    private static final long FLOCK_TYPE = 0;

    private static final int EACCES = 13;

    private static final int EINTR = 4;

    private static final int ENOENT = 2;

    private static final int EAGAIN = 11;

    private static final int ELOOP = 40;

    private static final int ENXIO = 6;

    /** The part of {@code struct statx} judged; its layout is the same on every architecture. */
    private static final StructLayout STATX =
            MemoryLayout.structLayout(
                    JAVA_INT.withName("mask"),
                    JAVA_INT.withName("blksize"),
                    JAVA_LONG.withName("attributes"),
                    JAVA_INT.withName("nlink"),
                    JAVA_INT.withName("uid"),
                    JAVA_INT.withName("gid"),
                    JAVA_SHORT.withName("mode"),
                    MemoryLayout.paddingLayout(256 - 30));

    private static final long NLINK =
            STATX.byteOffset(MemoryLayout.PathElement.groupElement("nlink"));

    private static final long UID = STATX.byteOffset(MemoryLayout.PathElement.groupElement("uid"));

    private static final long MODE =
            STATX.byteOffset(MemoryLayout.PathElement.groupElement("mode"));

    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();

    private static final VarHandle ERRNO =
            CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    /** Its value differs by architecture; -1 where the system is not known here. */
    private static final int O_NOFOLLOW =
            !HostSystem.LINUX
                    ? -1
                    : switch (System.getProperty("os.arch", "")) {
                        case "amd64", "x86_64" -> 0400000;
                        case "aarch64" -> 0100000;
                        default -> -1;
                    };

    /** The C library's calls, found only once the architecture is known to be supported. */
    private static final class Calls {

        private static final Linker LINKER = Linker.nativeLinker();

        private static final Linker.Option ERRNO_STATE = Linker.Option.captureCallState("errno");

        static final MethodHandle OPEN =
                downcall(
                        "open",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
                        ERRNO_STATE,
                        Linker.Option.firstVariadicArg(2));

        static final MethodHandle STATX =
                downcall(
                        "statx",
                        FunctionDescriptor.of(
                                JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
                        ERRNO_STATE);

        static final MethodHandle READ =
                downcall(
                        "read",
                        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG),
                        ERRNO_STATE);

        static final MethodHandle CLOSE =
                downcall("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT), ERRNO_STATE);

        static final MethodHandle FCNTL =
                downcall(
                        "fcntl",
                        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS),
                        ERRNO_STATE,
                        Linker.Option.firstVariadicArg(2));

        static final MethodHandle GETEUID = downcall("geteuid", FunctionDescriptor.of(JAVA_INT));

        private Calls() {}

        private static MethodHandle downcall(
                final String name,
                final FunctionDescriptor descriptor,
                final Linker.Option... options) {
            return LINKER.downcallHandle(
                    LINKER.defaultLookup()
                            .find(name)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "The C library has no " + name + " here")),
                    descriptor,
                    options);
        }
    }

    private LinuxFiles() {}

    /**
     * Refuses, loud, a system or architecture whose flags and calls are not known here.
     *
     * @throws IllegalStateException on any other than Linux on x86_64 and aarch64
     */
    static void requireSupported() {
        if (O_NOFOLLOW < 0) {
            throw new IllegalStateException(
                    "The account's own files cannot be checked on this system ("
                            + System.getProperty("os.name", "unknown")
                            + " on "
                            + System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT)
                            + "); only Linux on x86_64 and aarch64 is supported");
        }
    }

    /**
     * Reads a file of the account's alone: a regular file with one name, judged on its open
     * descriptor.
     *
     * @param file the file
     * @param most the most bytes wanted; one more is read to tell a larger file
     * @return its bytes, at most {@code most + 1}
     * @throws NoSuchFileException when it is gone
     * @throws IllegalStateException when it is a link, no regular file, another account's or has a
     *     second name
     * @throws IOException when it cannot be read
     */
    static byte[] read(final Path file, final int most) throws IOException {
        requireSupported();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(CALL_STATE);
            final int fd = open(arena, state, file, O_RDONLY, 0);
            try {
                requireOwnAlone(arena, state, fd, file);
                final MemorySegment buffer = arena.allocate(most + 1L);
                long read = 0;
                while (read < most + 1L) {
                    final long got =
                            (long)
                                    invoke(
                                            Calls.READ,
                                            state,
                                            fd,
                                            buffer.asSlice(read),
                                            most + 1L - read);
                    if (got < 0) {
                        if (errno(state) == EINTR) {
                            continue;
                        }
                        throw failure(file, "cannot be read", errno(state));
                    }
                    if (got == 0) {
                        break;
                    }
                    read += got;
                }
                return Arrays.copyOf(buffer.toArray(JAVA_BYTE), (int) read);
            } finally {
                close(state, fd);
            }
        }
    }

    /**
     * Opens, making it owner-only when missing, and locks a lock file of the account's alone.
     *
     * @param file the lock file
     * @param wait whether to wait for another holder; otherwise a held lock answers {@code -1}
     * @return the open descriptor holding the lock, or {@code -1} when another holds it
     * @throws IllegalStateException when it is a link, no regular file, another account's or has a
     *     second name
     * @throws IOException when it cannot be opened or locked
     */
    static int lock(final Path file, final boolean wait) throws IOException {
        requireSupported();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(CALL_STATE);
            final int fd = open(arena, state, file, O_WRONLY | O_CREAT, 0600);
            boolean held = false;
            try {
                requireOwnAlone(arena, state, fd, file);
                // A write lock over the whole file, as Java's own file lock takes it, so a
                // process locking either way keeps the other out.
                final MemorySegment range = arena.allocate(FLOCK_SIZE, 8);
                range.set(JAVA_SHORT, FLOCK_TYPE, F_WRLCK);
                while (true) {
                    final int done =
                            (int)
                                    invoke(
                                            Calls.FCNTL,
                                            state,
                                            fd,
                                            wait ? F_OFD_SETLKW : F_OFD_SETLK,
                                            range);
                    if (done == 0) {
                        held = true;
                        return fd;
                    }
                    final int error = errno(state);
                    if (error == EINTR) {
                        continue;
                    }
                    if ((error == EAGAIN || error == EACCES) && !wait) {
                        return -1;
                    }
                    throw failure(file, "cannot be locked", error);
                }
            } finally {
                if (!held) {
                    close(state, fd);
                }
            }
        }
    }

    /**
     * Lets go of a lock {@link #lock} took, by closing its descriptor.
     *
     * @param fd the descriptor
     * @throws IOException when it cannot be closed
     */
    static void unlock(final int fd) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(CALL_STATE);
            if ((int) invoke(Calls.CLOSE, state, fd) != 0 && errno(state) != EINTR) {
                throw new IOException(
                        "The project's lock could not be let go: " + name(errno(state)));
            }
        }
    }

    private static int open(
            final Arena arena,
            final MemorySegment state,
            final Path file,
            final int access,
            final int mode)
            throws IOException {
        final MemorySegment path = arena.allocateFrom(file.toString());
        while (true) {
            final int fd =
                    (int)
                            invoke(
                                    Calls.OPEN,
                                    state,
                                    path,
                                    access | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC,
                                    mode);
            if (fd >= 0) {
                return fd;
            }
            final int error = errno(state);
            switch (error) {
                case EINTR -> {
                    // Asked again.
                }
                case ENOENT -> throw new NoSuchFileException(file.toString());
                case ELOOP -> throw new IllegalStateException(file + " is a link");
                case ENXIO ->
                        // A pipe opened to write with no reader: refused at once, never waited on.
                        throw new IllegalStateException(file + " is not a regular file");
                default -> throw failure(file, "cannot be opened", error);
            }
        }
    }

    private static void requireOwnAlone(
            final Arena arena, final MemorySegment state, final int fd, final Path file)
            throws IOException {
        final MemorySegment stat = arena.allocate(STATX);
        if ((int)
                        invoke(
                                Calls.STATX,
                                state,
                                fd,
                                arena.allocateFrom(""),
                                AT_EMPTY_PATH,
                                STATX_BASIC_STATS,
                                stat)
                != 0) {
            throw failure(file, "cannot be checked", errno(state));
        }
        if ((stat.get(JAVA_SHORT, MODE) & S_IFMT) != S_IFREG) {
            throw new IllegalStateException(file + " is not a regular file");
        }
        if (stat.get(JAVA_INT, UID) != geteuid()) {
            throw new IllegalStateException(file + " belongs to another account");
        }
        if (stat.get(JAVA_INT, NLINK) != 1) {
            throw new IllegalStateException(file + " has a second name");
        }
    }

    private static void close(final MemorySegment state, final int fd) throws IOException {
        if ((int) invoke(Calls.CLOSE, state, fd) != 0 && errno(state) != EINTR) {
            throw new IOException("A file could not be closed: " + name(errno(state)));
        }
    }

    private static int errno(final MemorySegment state) {
        return (int) ERRNO.get(state, 0L);
    }

    private static IOException failure(final Path file, final String what, final int errno) {
        return new IOException(file + " " + what + ": " + name(errno));
    }

    private static String name(final int errno) {
        return "errno " + errno;
    }

    private static int geteuid() {
        try {
            return (int) Calls.GETEUID.invokeExact();
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable e) {
            throw new IllegalStateException("A C library call failed", e);
        }
    }

    /** Calls a C function that captures its call state, the state first. */
    private static Object invoke(
            final MethodHandle function, final MemorySegment state, final Object... arguments) {
        try {
            final Object[] all = new Object[arguments.length + 1];
            all[0] = state;
            System.arraycopy(arguments, 0, all, 1, arguments.length);
            return function.invokeWithArguments(all);
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable e) {
            throw new IllegalStateException("A C library call failed", e);
        }
    }
}

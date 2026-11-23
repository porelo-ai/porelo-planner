package org.fuin.porelo.planner.store.files;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A file of the account's own opened and judged on Windows through its open handle itself, by the
 * system's own calls: never through a reparse point, only a file on a disk, with one name, its
 * owner and access control list read from the handle, and the file read the one judged. Java's own
 * open can tell none of this of the handle it opened.
 *
 * <p>Only the directory's last step is held this way; the directories above it are the account's
 * alone, so only the account's own processes could swap one, and the account is trusted.
 */
final class WindowsFiles {

    private static final int GENERIC_READ = 0x80000000;

    private static final int GENERIC_WRITE = 0x40000000;

    private static final int READ_CONTROL = 0x00020000;

    private static final int SHARE_ALL = 0x1 | 0x2 | 0x4;

    private static final int OPEN_EXISTING = 3;

    private static final int OPEN_ALWAYS = 4;

    private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;

    private static final int FILE_ATTRIBUTE_NORMAL = 0x80;

    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;

    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;

    private static final int FILE_TYPE_DISK = 1;

    private static final int SE_FILE_OBJECT = 1;

    private static final int OWNER_AND_DACL = 0x1 | 0x4;

    private static final int TOKEN_QUERY = 0x8;

    private static final int TOKEN_USER = 1;

    private static final int LOCKFILE_FAIL_IMMEDIATELY = 0x1;

    private static final int LOCKFILE_EXCLUSIVE_LOCK = 0x2;

    private static final int ERROR_FILE_NOT_FOUND = 2;

    private static final int ERROR_PATH_NOT_FOUND = 3;

    private static final int ERROR_LOCK_VIOLATION = 33;

    private static final byte ACCESS_ALLOWED = 0;

    private static final byte ACCESS_DENIED = 1;

    private static final byte OBJECT_INHERIT_ACE = 0x1;

    private static final byte CONTAINER_INHERIT_ACE = 0x2;

    private static final byte NO_PROPAGATE_INHERIT_ACE = 0x4;

    private static final byte INHERIT_ONLY_ACE = 0x8;

    /** {@code BY_HANDLE_FILE_INFORMATION}, its fields judged by offset. */
    private static final long INFORMATION_SIZE = 52;

    private static final long ATTRIBUTES_AT = 0;

    private static final long LINKS_AT = 40;

    /** {@code OVERLAPPED} on 64-bit Windows, zero: the lock's range starts at the file's start. */
    private static final long OVERLAPPED_SIZE = 32;

    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();

    private static final VarHandle LAST_ERROR =
            CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

    /** The system's calls, found only on Windows. */
    private static final class Calls {

        private static final Linker LINKER = Linker.nativeLinker();

        private static final SymbolLookup KERNEL32 =
                SymbolLookup.libraryLookup("kernel32", Arena.global());

        private static final SymbolLookup ADVAPI32 =
                SymbolLookup.libraryLookup("advapi32", Arena.global());

        private static final Linker.Option ERROR = Linker.Option.captureCallState("GetLastError");

        static final MethodHandle CREATE_FILE =
                downcall(
                        KERNEL32,
                        "CreateFileW",
                        FunctionDescriptor.of(
                                ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
                                ADDRESS),
                        ERROR);

        static final MethodHandle GET_FILE_TYPE =
                downcall(KERNEL32, "GetFileType", FunctionDescriptor.of(JAVA_INT, ADDRESS), ERROR);

        static final MethodHandle GET_FILE_INFORMATION =
                downcall(
                        KERNEL32,
                        "GetFileInformationByHandle",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                        ERROR);

        static final MethodHandle READ_FILE =
                downcall(
                        KERNEL32,
                        "ReadFile",
                        FunctionDescriptor.of(
                                JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                        ERROR);

        static final MethodHandle LOCK_FILE =
                downcall(
                        KERNEL32,
                        "LockFileEx",
                        FunctionDescriptor.of(
                                JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS),
                        ERROR);

        static final MethodHandle UNLOCK_FILE =
                downcall(
                        KERNEL32,
                        "UnlockFileEx",
                        FunctionDescriptor.of(
                                JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS),
                        ERROR);

        static final MethodHandle CLOSE_HANDLE =
                downcall(KERNEL32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS), ERROR);

        static final MethodHandle CURRENT_PROCESS =
                downcall(KERNEL32, "GetCurrentProcess", FunctionDescriptor.of(ADDRESS));

        static final MethodHandle LOCAL_FREE =
                downcall(KERNEL32, "LocalFree", FunctionDescriptor.of(ADDRESS, ADDRESS));

        static final MethodHandle GET_SECURITY_INFO =
                downcall(
                        ADVAPI32,
                        "GetSecurityInfo",
                        FunctionDescriptor.of(
                                JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS,
                                ADDRESS, ADDRESS));

        static final MethodHandle OPEN_PROCESS_TOKEN =
                downcall(
                        ADVAPI32,
                        "OpenProcessToken",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                        ERROR);

        static final MethodHandle GET_TOKEN_INFORMATION =
                downcall(
                        ADVAPI32,
                        "GetTokenInformation",
                        FunctionDescriptor.of(
                                JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                        ERROR);

        static final MethodHandle SID_TO_STRING =
                downcall(
                        ADVAPI32,
                        "ConvertSidToStringSidW",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                        ERROR);

        static final MethodHandle GET_ACE =
                downcall(
                        ADVAPI32,
                        "GetAce",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                        ERROR);

        private Calls() {}

        private static MethodHandle downcall(
                final SymbolLookup library,
                final String name,
                final FunctionDescriptor descriptor,
                final Linker.Option... options) {
            return LINKER.downcallHandle(
                    library.find(name)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Windows has no " + name + " here")),
                    descriptor,
                    options);
        }
    }

    private WindowsFiles() {}

    /**
     * Reads a file of the account's own: a file on a disk with one name, no reparse point, owned by
     * the account, SYSTEM or Administrators, and that no one else may write, judged on its open
     * handle.
     *
     * @param file the file
     * @param most the most bytes wanted; one more is read to tell a larger file
     * @return its bytes, at most {@code most + 1}
     * @throws NoSuchFileException when it is gone
     * @throws IllegalStateException when it is not the account's own plain file
     * @throws IOException when it cannot be read
     */
    static byte[] read(final Path file, final int most) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(CALL_STATE);
            final MemorySegment handle =
                    open(arena, state, file, GENERIC_READ | READ_CONTROL, OPEN_EXISTING);
            try {
                requireOwnAlone(arena, state, handle, file);
                final MemorySegment buffer = arena.allocate(most + 1L);
                final MemorySegment got = arena.allocate(JAVA_INT);
                long read = 0;
                while (read < most + 1L) {
                    if ((int)
                                    invoke(
                                            Calls.READ_FILE,
                                            state,
                                            handle,
                                            buffer.asSlice(read),
                                            (int) (most + 1L - read),
                                            got,
                                            MemorySegment.NULL)
                            == 0) {
                        throw failure(file, "cannot be read", lastError(state));
                    }
                    final int count = got.get(JAVA_INT, 0);
                    if (count == 0) {
                        break;
                    }
                    read += count;
                }
                return Arrays.copyOf(buffer.toArray(JAVA_BYTE), (int) read);
            } finally {
                close(state, handle);
            }
        }
    }

    /**
     * Opens, making it when missing with the directory's own access control list, and locks a lock
     * file of the account's own.
     *
     * @param file the lock file
     * @param wait whether to wait for another holder; otherwise a held lock answers {@code 0}
     * @return the open handle's address holding the lock, or {@code 0} when another holds it
     * @throws IllegalStateException when it is not the account's own plain file
     * @throws IOException when it cannot be opened or locked
     */
    static long lock(final Path file, final boolean wait) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(CALL_STATE);
            final MemorySegment handle =
                    open(
                            arena,
                            state,
                            file,
                            GENERIC_READ | GENERIC_WRITE | READ_CONTROL,
                            OPEN_ALWAYS);
            boolean held = false;
            try {
                requireOwnAlone(arena, state, handle, file);
                final MemorySegment overlapped = arena.allocate(OVERLAPPED_SIZE, 8);
                if ((int)
                                invoke(
                                        Calls.LOCK_FILE,
                                        state,
                                        handle,
                                        LOCKFILE_EXCLUSIVE_LOCK
                                                | (wait ? 0 : LOCKFILE_FAIL_IMMEDIATELY),
                                        0,
                                        1,
                                        0,
                                        overlapped)
                        != 0) {
                    held = true;
                    return handle.address();
                }
                final int error = lastError(state);
                if (error == ERROR_LOCK_VIOLATION && !wait) {
                    return 0;
                }
                throw failure(file, "cannot be locked", error);
            } finally {
                if (!held) {
                    close(state, handle);
                }
            }
        }
    }

    /**
     * Lets go of a lock {@link #lock} took and closes its handle.
     *
     * @param address the handle's address
     * @throws IOException when it cannot be let go
     */
    static void unlock(final long address) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(CALL_STATE);
            final MemorySegment handle = MemorySegment.ofAddress(address);
            try {
                if ((int)
                                invoke(
                                        Calls.UNLOCK_FILE,
                                        state,
                                        handle,
                                        0,
                                        1,
                                        0,
                                        arena.allocate(OVERLAPPED_SIZE, 8))
                        == 0) {
                    throw new IOException(
                            "The project's lock could not be let go: error " + lastError(state));
                }
            } finally {
                close(state, handle);
            }
        }
    }

    private static MemorySegment open(
            final Arena arena,
            final MemorySegment state,
            final Path file,
            final int access,
            final int disposition)
            throws IOException {
        final MemorySegment handle =
                (MemorySegment)
                        invoke(
                                Calls.CREATE_FILE,
                                state,
                                arena.allocateFrom(file.toString(), StandardCharsets.UTF_16LE),
                                access,
                                SHARE_ALL,
                                MemorySegment.NULL,
                                disposition,
                                FILE_FLAG_OPEN_REPARSE_POINT | FILE_ATTRIBUTE_NORMAL,
                                MemorySegment.NULL);
        if (handle.address() == -1L) {
            final int error = lastError(state);
            if (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND) {
                throw new NoSuchFileException(file.toString());
            }
            throw failure(file, "cannot be opened", error);
        }
        return handle;
    }

    private static void requireOwnAlone(
            final Arena arena,
            final MemorySegment state,
            final MemorySegment handle,
            final Path file)
            throws IOException {
        if ((int) invoke(Calls.GET_FILE_TYPE, state, handle) != FILE_TYPE_DISK) {
            throw new IllegalStateException(file + " is not a regular file");
        }
        final MemorySegment information = arena.allocate(INFORMATION_SIZE, 4);
        if ((int) invoke(Calls.GET_FILE_INFORMATION, state, handle, information) == 0) {
            throw failure(file, "cannot be checked", lastError(state));
        }
        final int attributes = information.get(JAVA_INT, ATTRIBUTES_AT);
        if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            throw new IllegalStateException(file + " is a link");
        }
        if ((attributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
            throw new IllegalStateException(file + " is not a regular file");
        }
        if (information.get(JAVA_INT, LINKS_AT) != 1) {
            throw new IllegalStateException(file + " has a second name");
        }
        new WindowsAccess(self(arena, state), List.of(entry(arena, handle, file)))
                .requireOwnItem(file, 0);
    }

    /** The handle's owner and access control list, as {@link WindowsAccess} judges a path's. */
    private static WindowsAccess.Entry entry(
            final Arena arena, final MemorySegment handle, final Path file) {
        final MemorySegment owner = arena.allocate(ADDRESS);
        final MemorySegment dacl = arena.allocate(ADDRESS);
        final MemorySegment descriptor = arena.allocate(ADDRESS);
        final int result;
        try {
            result =
                    (int)
                            Calls.GET_SECURITY_INFO.invokeExact(
                                    handle,
                                    SE_FILE_OBJECT,
                                    OWNER_AND_DACL,
                                    owner,
                                    MemorySegment.NULL,
                                    dacl,
                                    MemorySegment.NULL,
                                    descriptor);
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable e) {
            throw new IllegalStateException("A Windows call failed", e);
        }
        if (result != 0) {
            throw new IllegalStateException(
                    file + "'s owner and access cannot be read: error " + result);
        }
        final MemorySegment held = descriptor.get(ADDRESS, 0);
        try {
            final MemorySegment list = dacl.get(ADDRESS, 0);
            if (list.address() == 0) {
                throw new IllegalStateException(
                        file + " has no access control list, so anyone may change it");
            }
            final MemorySegment acl = list.reinterpret(8);
            final int count = Short.toUnsignedInt(acl.get(JAVA_SHORT, 4));
            final List<WindowsAccess.Ace> aces = new ArrayList<>();
            final MemorySegment state = arena.allocate(CALL_STATE);
            final MemorySegment found = arena.allocate(ADDRESS);
            for (int at = 0; at < count; at++) {
                if ((int) invoke(Calls.GET_ACE, state, list, at, found) == 0) {
                    throw new IllegalStateException(file + "'s access control list cannot be read");
                }
                final MemorySegment ace = found.get(ADDRESS, 0).reinterpret(8);
                final byte type = ace.get(JAVA_BYTE, 0);
                final byte flags = ace.get(JAVA_BYTE, 1);
                if (type != ACCESS_ALLOWED && type != ACCESS_DENIED) {
                    // An entry of another kind may allow on a condition no rule here judges.
                    throw new IllegalStateException(
                            file + " carries an access entry of a kind not judged here");
                }
                aces.add(
                        new WindowsAccess.Ace(
                                type == ACCESS_ALLOWED,
                                sid(arena, ace.reinterpret(12).asSlice(8).address()),
                                Integer.toUnsignedLong(ace.get(JAVA_INT, 4)),
                                ((flags & CONTAINER_INHERIT_ACE) != 0
                                                ? WindowsAccess.CONTAINER_INHERIT
                                                : 0)
                                        | ((flags & OBJECT_INHERIT_ACE) != 0
                                                ? WindowsAccess.OBJECT_INHERIT
                                                : 0),
                                ((flags & NO_PROPAGATE_INHERIT_ACE) != 0
                                                ? WindowsAccess.NO_PROPAGATE
                                                : 0)
                                        | ((flags & INHERIT_ONLY_ACE) != 0
                                                ? WindowsAccess.INHERIT_ONLY
                                                : 0)));
            }
            return new WindowsAccess.Entry(
                    sid(arena, owner.get(ADDRESS, 0).address()), false, false, aces);
        } finally {
            free(held);
        }
    }

    /** The process's own user, as a SID's text. */
    private static String self(final Arena arena, final MemorySegment state) throws IOException {
        final MemorySegment token = arena.allocate(ADDRESS);
        final MemorySegment process;
        try {
            process = (MemorySegment) Calls.CURRENT_PROCESS.invokeExact();
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable e) {
            throw new IllegalStateException("A Windows call failed", e);
        }
        if ((int) invoke(Calls.OPEN_PROCESS_TOKEN, state, process, TOKEN_QUERY, token) == 0) {
            throw new IOException("This account's token cannot be read: error " + lastError(state));
        }
        final MemorySegment opened = token.get(ADDRESS, 0);
        try {
            final int size = 256;
            final MemorySegment user = arena.allocate(size, 8);
            final MemorySegment needed = arena.allocate(JAVA_INT);
            if ((int)
                            invoke(
                                    Calls.GET_TOKEN_INFORMATION,
                                    state,
                                    opened,
                                    TOKEN_USER,
                                    user,
                                    size,
                                    needed)
                    == 0) {
                throw new IOException(
                        "This account's user cannot be read: error " + lastError(state));
            }
            return sid(arena, user.get(ADDRESS, 0).address());
        } finally {
            close(state, opened);
        }
    }

    /** A SID's text, as {@code S-1-5-...}. */
    private static String sid(final Arena arena, final long address) {
        final MemorySegment text = arena.allocate(ADDRESS);
        final MemorySegment state = arena.allocate(CALL_STATE);
        if ((int) invoke(Calls.SID_TO_STRING, state, MemorySegment.ofAddress(address), text) == 0) {
            throw new IllegalStateException("A SID cannot be read: error " + lastError(state));
        }
        final MemorySegment wide = text.get(ADDRESS, 0);
        try {
            final StringBuilder sid = new StringBuilder();
            final MemorySegment chars = wide.reinterpret(2L * 200);
            for (long at = 0; at < 200; at++) {
                final char next = chars.getAtIndex(JAVA_CHAR, at);
                if (next == 0) {
                    return sid.toString();
                }
                sid.append(next);
            }
            throw new IllegalStateException("A SID's text is longer than any SID's");
        } finally {
            free(wide);
        }
    }

    private static void free(final MemorySegment held) {
        try {
            Calls.LOCAL_FREE.invokeWithArguments(held);
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable e) {
            throw new IllegalStateException("A Windows call failed", e);
        }
    }

    private static void close(final MemorySegment state, final MemorySegment handle)
            throws IOException {
        if ((int) invoke(Calls.CLOSE_HANDLE, state, handle) == 0) {
            throw new IOException("A file could not be closed: error " + lastError(state));
        }
    }

    private static int lastError(final MemorySegment state) {
        return (int) LAST_ERROR.get(state, 0L);
    }

    private static IOException failure(final Path file, final String what, final int error) {
        return new IOException(file + " " + what + ": error " + error);
    }

    /** Calls a Windows function that captures its last error, the state first. */
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
            throw new IllegalStateException("A Windows call failed", e);
        }
    }
}

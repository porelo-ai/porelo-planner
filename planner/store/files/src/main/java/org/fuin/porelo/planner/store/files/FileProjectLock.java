package org.fuin.porelo.planner.store.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ProjectLock} that is a lock in the process and a lock on a file in the project's cache
 * directory. The file is opened and judged as the account's own regular file with one name through
 * the system's own calls, so a pipe, a link or another account's file in its place is refused at
 * once and never waited on.
 */
public final class FileProjectLock implements ProjectLock {

    /** One process lock per lock file, so every store of a project shares it. */
    private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS =
            new ConcurrentHashMap<>();

    private static final Duration POLL = Duration.ofMillis(50);

    private final ReentrantLock inProcess;

    private final Path file;

    /**
     * Makes a lock.
     *
     * @param project the project's cache directory
     */
    public FileProjectLock(final Path project) {
        this.file = project.toAbsolutePath().normalize().resolve("lock");
        this.inProcess = IN_PROCESS.computeIfAbsent(file, any -> new ReentrantLock());
    }

    @Override
    public Held acquire() {
        // The process lock comes first, so one process asks the system for the file lock once.
        inProcess.lock();
        return taken(null)
                .orElseThrow(() -> new IllegalStateException("the project's lock was not taken"));
    }

    @Override
    public Optional<Held> acquire(final Duration most) {
        final long deadline = System.nanoTime() + most.toNanos();
        try {
            if (!inProcess.tryLock(most.toNanos(), TimeUnit.NANOSECONDS)) {
                return Optional.empty();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        return taken(deadline);
    }

    /**
     * Takes the file lock, the process lock held: until the deadline when one is given, otherwise
     * as long as another holds it. The process lock is let go unless the file lock is taken.
     */
    private Optional<Held> taken(final @Nullable Long deadline) {
        try {
            while (true) {
                final OptionalLong held = Platform.take(file, deadline == null);
                if (held.isPresent()) {
                    final long token = held.getAsLong();
                    return Optional.of(
                            () -> {
                                try {
                                    Platform.release(token);
                                } catch (final IOException e) {
                                    throw new UncheckedIOException(e);
                                } finally {
                                    inProcess.unlock();
                                }
                            });
                }
                if (deadline == null || System.nanoTime() >= deadline) {
                    inProcess.unlock();
                    return Optional.empty();
                }
                Thread.sleep(POLL.toMillis());
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            inProcess.unlock();
            return Optional.empty();
        } catch (final IOException e) {
            inProcess.unlock();
            throw new UncheckedIOException("the project's lock could not be taken", e);
        } catch (final RuntimeException | Error e) {
            inProcess.unlock();
            throw e;
        }
    }

    /**
     * The system's own lock, which Java's own file lock also keeps to: an open file's {@code fcntl}
     * lock on Linux, {@code LockFileEx} on Windows.
     */
    enum Platform {
        LINUX,
        WINDOWS,
        OTHER;

        /** The system the lock is taken on, told once. */
        static final Platform CURRENT = current();

        /** The system as {@link HostSystem} tells it, as every other reader asks. */
        static Platform current() {
            if (HostSystem.LINUX) {
                return LINUX;
            }
            return HostSystem.WINDOWS ? WINDOWS : OTHER;
        }

        /** The lock's token, or none while another holds it and no wait was asked. */
        static OptionalLong take(final Path file, final boolean wait) throws IOException {
            return switch (CURRENT) {
                case LINUX -> {
                    final int fd = LinuxFiles.lock(file, wait);
                    yield fd < 0 ? OptionalLong.empty() : OptionalLong.of(fd);
                }
                case WINDOWS -> {
                    final long handle = WindowsFiles.lock(file, wait);
                    yield handle == 0 ? OptionalLong.empty() : OptionalLong.of(handle);
                }
                case OTHER ->
                        throw new IllegalStateException(
                                "The project's lock cannot be taken on this system ("
                                        + System.getProperty("os.name", "unknown")
                                        + "); only Linux and Windows are supported");
            };
        }

        static void release(final long token) throws IOException {
            switch (CURRENT) {
                case LINUX -> LinuxFiles.unlock((int) token);
                case WINDOWS -> WindowsFiles.unlock(token);
                case OTHER -> throw new IllegalStateException("No lock is taken on this system");
            }
        }
    }
}

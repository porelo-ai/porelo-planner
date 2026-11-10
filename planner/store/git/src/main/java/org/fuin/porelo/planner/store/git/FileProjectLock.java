package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ProjectLock} that is a lock in the process and a lock on a file in the project's cache
 * directory.
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
        // The process lock comes first: a second FileChannel.lock in one process throws instead of
        // waiting.
        inProcess.lock();
        FileChannel channel = null;
        try {
            channel =
                    FileChannel.open(
                            file,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            final FileLock lock = channel.lock();
            final FileChannel open = channel;
            return () -> {
                try (open) {
                    lock.release();
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    inProcess.unlock();
                }
            };
        } catch (final IOException e) {
            close(channel, e);
            inProcess.unlock();
            throw new UncheckedIOException("the project's lock could not be taken", e);
        } catch (final RuntimeException | Error e) {
            close(channel, e);
            inProcess.unlock();
            throw e;
        }
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
        FileChannel channel = null;
        try {
            channel =
                    FileChannel.open(
                            file,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            // A file lock has no wait with a bound, so it is tried until the deadline.
            FileLock lock = channel.tryLock();
            while (lock == null && System.nanoTime() < deadline) {
                Thread.sleep(POLL.toMillis());
                lock = channel.tryLock();
            }
            if (lock == null) {
                channel.close();
                inProcess.unlock();
                return Optional.empty();
            }
            final FileLock held = lock;
            final FileChannel open = channel;
            return Optional.of(
                    () -> {
                        try (open) {
                            held.release();
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        } finally {
                            inProcess.unlock();
                        }
                    });
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            close(channel, e);
            inProcess.unlock();
            return Optional.empty();
        } catch (final IOException e) {
            close(channel, e);
            inProcess.unlock();
            throw new UncheckedIOException("the project's lock could not be taken", e);
        } catch (final RuntimeException | Error e) {
            close(channel, e);
            inProcess.unlock();
            throw e;
        }
    }

    private static void close(final @Nullable FileChannel channel, final Throwable failure) {
        if (channel != null) {
            try {
                channel.close();
            } catch (final IOException e) {
                failure.addSuppressed(e);
            }
        }
    }
}

package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
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

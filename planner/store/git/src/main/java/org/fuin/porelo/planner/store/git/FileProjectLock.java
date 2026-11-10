package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link ProjectLock} that is a lock in the process and a lock on a file in the project's cache
 * directory.
 */
public final class FileProjectLock implements ProjectLock {

    private final ReentrantLock inProcess = new ReentrantLock();

    private final Path file;

    /**
     * Makes a lock.
     *
     * @param project the project's cache directory
     */
    public FileProjectLock(final Path project) {
        this.file = project.resolve("lock");
    }

    @Override
    public Held acquire() {
        // The process lock comes first: a second FileChannel.lock in one process throws instead of
        // waiting.
        inProcess.lock();
        try {
            final FileChannel channel =
                    FileChannel.open(
                            file,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            final FileLock lock = channel.lock();
            return () -> {
                try (channel) {
                    lock.release();
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    inProcess.unlock();
                }
            };
        } catch (final IOException e) {
            inProcess.unlock();
            throw new UncheckedIOException("the project's lock could not be taken", e);
        }
    }
}

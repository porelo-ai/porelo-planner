package org.fuin.porelo.planner.store.files;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A planner process that takes a project's lock as Java's own file lock does: holds it until its
 * input closes.
 */
final class ChannelLockHolder {

    private ChannelLockHolder() {}

    /**
     * Prints {@code held} once the lock is taken and releases it when standard input ends.
     *
     * @param args the project's cache directory
     * @throws Exception when the lock cannot be taken
     */
    public static void main(final String[] args) throws Exception {
        try (FileChannel channel =
                        FileChannel.open(
                                Path.of(args[0]).resolve("lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            System.out.println("held");
            System.out.flush();
            System.in.readAllBytes();
        }
    }
}

package org.fuin.porelo.planner.store.local;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.jspecify.annotations.Nullable;

/**
 * The account's checks, with a step a test runs at a chosen moment of a read or a save: when an
 * item file is read, or when a new file's attributes are asked for.
 */
final class HookedFiles implements PrivateFiles {

    /** A step that may throw what a file system step throws. */
    interface Step {
        void run() throws Exception;
    }

    private final PrivateFiles files = PrivateFiles.of(Map.of());

    /** How often the directory was checked. */
    final AtomicInteger directoryChecks = new AtomicInteger();

    private volatile @Nullable String readName;

    private volatile @Nullable Step onRead;

    private volatile int newFileCall;

    private volatile @Nullable Step onNewFile;

    private final AtomicInteger newFiles = new AtomicInteger();

    /**
     * Runs a step once, right before the item file of the name is read, after the read looked at
     * it.
     *
     * @param name the file's name
     * @param step the step
     */
    void beforeReadOf(final String name, final Step step) {
        readName = name;
        onRead = step;
    }

    /**
     * Runs a step once, when the given new file of a save is about to be made, counting from one.
     *
     * @param call which new file
     * @param step the step
     */
    void beforeNewFile(final int call, final Step step) {
        newFiles.set(0);
        newFileCall = call;
        onNewFile = step;
    }

    @Override
    public boolean windows() {
        return files.windows();
    }

    @Override
    public Path base(final Kind kind, final Map<String, String> environment) {
        return files.base(kind, environment);
    }

    @Override
    public List<String> steps(final Kind kind) {
        return files.steps(kind);
    }

    @Override
    public Path directories(
            final Path base, final boolean create, final Kind kind, final List<String> steps) {
        return files.directories(base, create, kind, steps);
    }

    @Override
    public FileAttribute<?>[] ownerOnlyFile() {
        final Step step = onNewFile;
        if (step != null && newFiles.incrementAndGet() == newFileCall) {
            onNewFile = null;
            run(step);
        }
        return files.ownerOnlyFile();
    }

    @Override
    public void requireOwnFile(final Path file, final boolean alone) {
        files.requireOwnFile(file, alone);
    }

    @Override
    public void requireExistingDirectory(final Path directory) {
        directoryChecks.incrementAndGet();
        files.requireExistingDirectory(directory);
    }

    @Override
    public byte[] readItemFile(final Path file, final int most) throws IOException {
        final Step step = onRead;
        if (step != null && file.getFileName().toString().equals(readName)) {
            onRead = null;
            run(step);
        }
        return files.readItemFile(file, most);
    }

    private static void run(final Step step) {
        try {
            step.run();
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("A test's step failed", e);
        }
    }
}

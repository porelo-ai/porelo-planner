package org.fuin.porelo.planner.store.git;

import java.nio.file.Path;

/** A second planner process in miniature: holds a project's lock until its input closes. */
final class ProjectLockHolder {

    private ProjectLockHolder() {}

    /**
     * Prints {@code held} once the lock is taken and releases it when standard input ends.
     *
     * @param args the project's cache directory
     * @throws Exception when the lock cannot be taken
     */
    public static void main(final String[] args) throws Exception {
        try (ProjectLock.Held held = new FileProjectLock(Path.of(args[0])).acquire()) {
            System.out.println("held");
            System.out.flush();
            System.in.readAllBytes();
        }
    }
}

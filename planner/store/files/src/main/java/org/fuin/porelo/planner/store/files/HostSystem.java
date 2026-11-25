package org.fuin.porelo.planner.store.files;

import java.nio.file.FileSystems;

/** What the planner knows of the system it runs on, told in one place for every reader. */
public final class HostSystem {

    /**
     * Whether this is Windows: the default file system has no POSIX attribute view there. Every
     * reader that takes a different way on Windows asks this, so none disagrees with another on one
     * machine.
     */
    public static final boolean WINDOWS =
            !FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private HostSystem() {}
}

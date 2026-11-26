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

    /**
     * Whether this is Linux: not Windows, and named Linux, since a POSIX file system alone does not
     * tell Linux from another system the readers do not support. The one place the system's name is
     * read to choose a way; {@code LinuxFiles} reads {@code os.arch} only to pick its {@code
     * O_NOFOLLOW} value once this holds.
     */
    public static final boolean LINUX = !WINDOWS && "Linux".equals(System.getProperty("os.name"));

    private HostSystem() {}
}

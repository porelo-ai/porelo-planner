package org.fuin.porelo.planner.store.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The shape of a directory the planner takes, as the system it runs on writes one. */
class ItemsDirectoryShapeTest {

    @Test
    @Tag("linux")
    @EnabledOnOs(OS.LINUX)
    void onLinuxAnAbsolutePathIsADirectoryAndADrivePathIsNone() {
        // Harm: the planner takes a directory by another system's rule than the one it runs on,
        // so the person's directory is refused or a path no file call can reach is kept.
        assertThat(
                        Arrays.asList(
                                ItemsDirectories.shapeFault("/home/person/items"),
                                ItemsDirectories.shapeFault("C:\\items")))
                .containsExactly(null, ItemsDirectories.NOT_NORMAL);
    }

    @Test
    @Tag("windows")
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsOnlyADrivePathIsADirectory() {
        // Harm: the planner takes a directory by another system's rule than the one it runs on,
        // so the person's directory is refused or a share or rooted path is kept.
        assertThat(
                        Arrays.asList(
                                ItemsDirectories.shapeFault("C:\\items"),
                                ItemsDirectories.shapeFault("\\\\server\\share\\items"),
                                ItemsDirectories.shapeFault("\\items")))
                .containsExactly(null, ItemsDirectories.NOT_NORMAL, ItemsDirectories.NOT_NORMAL);
    }
}

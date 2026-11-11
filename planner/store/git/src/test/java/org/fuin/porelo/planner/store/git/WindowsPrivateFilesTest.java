package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WindowsPrivateFilesTest {

    @Test
    void aDrivesAbsolutePathIsTheBase() {
        assertThat(
                        WindowsPrivateFiles.drivePath(
                                Map.of("LOCALAPPDATA", "C:\\Users\\person\\AppData\\Local"),
                                "LOCALAPPDATA"))
                .as("the account's local application data is where the planner's files go")
                .isEqualTo("C:\\Users\\person\\AppData\\Local");
    }

    @Test
    void theVariableIsFoundInAnyCaseAsWindowsFindsIt() {
        assertThat(
                        WindowsPrivateFiles.drivePath(
                                Map.of("LocalAppData", "D:\\Profiles\\person\\Local"),
                                "LOCALAPPDATA"))
                .as("a copied environment keeps the name's case as the system gave it")
                .isEqualTo("D:\\Profiles\\person\\Local");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "\\\\server\\share\\person",
                "\\\\?\\C:\\Users\\person",
                "\\\\.\\C:\\Users\\person",
                "Users\\person",
                "C:Users\\person",
                "C:\\Users\\..\\Public",
                "C:\\Users\\.\\person",
                "C:\\Users\\person\nD:\\x"
            })
    void anythingButAPlainDrivePathIsRefused(final String value) {
        // A share would send the account's login to another machine; a relative or dotted path
        // lands somewhere nobody checked.
        assertThatThrownBy(
                        () ->
                                WindowsPrivateFiles.drivePath(
                                        Map.of("LOCALAPPDATA", value), "LOCALAPPDATA"))
                .as("'%s' is refused before anything is made", value)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCALAPPDATA");
    }
}

package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.planner.store.files.HostSystem;
import org.junit.jupiter.api.Test;

/** The planner's readers of the platform ask one check whether this is Windows. */
class WindowsCheckTest {

    @Test
    void onlyTheHostSystemAsksTheFileSystemWhetherItIsPosix() {
        // Harm: two readers each keep a copy of the check and one is changed alone, so on one
        // machine the Git calls and Porelo's helper take the Windows way while another does not.
        final JavaClasses classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("org.fuin.porelo.planner");
        final List<String> asking = new ArrayList<>();
        for (final JavaClass type : classes) {
            for (final JavaAccess<?> call : type.getMethodCallsFromSelf()) {
                if (call.getName().equals("supportedFileAttributeViews")) {
                    asking.add(type.getName());
                }
            }
        }

        assertThat(classes).as("the classes read").isNotEmpty();
        assertThat(asking)
                .as("the classes that ask the file system for its attribute views")
                .containsExactly(HostSystem.class.getName());
    }
}

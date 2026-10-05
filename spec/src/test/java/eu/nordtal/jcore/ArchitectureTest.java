package eu.nordtal.jcore;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        Assumptions.assumeTrue(Boolean.getBoolean("conventions.enforced"));
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.nordtal.jcore");
    }

    @Test
    void packagesHaveNoCycles() {
        slices().matching("eu.nordtal.jcore.(*).(*)..")
                .should()
                .beFreeOfCycles()
                .check(classes);
    }

    @Test
    void noPackageIsAGrabBag() {
        noClasses()
                .should()
                .resideInAnyPackage("..util..", "..utils..", "..helper..", "..helpers..", "..misc..")
                .check(classes);
    }
}

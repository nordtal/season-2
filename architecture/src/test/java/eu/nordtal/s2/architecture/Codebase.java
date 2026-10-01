package eu.nordtal.s2.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Assumptions;

/** Every checked module's compiled classes, imported once for all the rules of this module. */
final class Codebase {

    private Codebase() {}

    /** Returns the classes, or skips the calling test where the build does not enforce the conventions. */
    static JavaClasses classes() {
        Assumptions.assumeTrue(Boolean.getBoolean("conventions.enforced"));
        return Imported.CLASSES;
    }

    /** Holds the import, so it runs once, on first use, whichever rule asks first. */
    private static final class Imported {

        private static final JavaClasses CLASSES = new ClassFileImporter()
                .importPackages("eu.nordtal.s2")
                .that(DescribedPredicate.not(resideInAPackage("eu.nordtal.s2.architecture..")));
    }
}

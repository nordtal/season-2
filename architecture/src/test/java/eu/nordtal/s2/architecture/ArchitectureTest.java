package eu.nordtal.s2.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final Set<String> MIGRATORS =
            Set.of("org.flywaydb.core.Flyway", "eu.nordtal.jcore.persistence.sql.Database");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        Assumptions.assumeTrue(Boolean.getBoolean("conventions.enforced"));
        classes = new ClassFileImporter()
                .importPackages("eu.nordtal.s2")
                .that(DescribedPredicate.not(resideInAPackage("eu.nordtal.s2.architecture..")));
    }

    @Test
    void packagesHaveNoCycles() {
        slices().matching("eu.nordtal.s2.(*).(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    void noPackageIsAGrabBag() {
        noClasses()
                .should()
                .resideInAnyPackage("..util..", "..utils..", "..helper..", "..helpers..", "..misc..")
                .check(classes);
    }

    // Adventure is on the list because both platforms provide it, so :common only compiles against it.
    @Test
    void commonDependsOnlyOnTheDatabaseStack() {
        classes()
                .that()
                .resideInAPackage("eu.nordtal.s2.common..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        "javax.sql..",
                        "org.jdbi..",
                        "com.zaxxer.hikari..",
                        "org.slf4j..",
                        "org.postgresql..",
                        "org.jspecify..",
                        "net.kyori.adventure..",
                        "eu.nordtal.s2.common..")
                .check(classes);
    }

    @Test
    void noPaperPluginWaitsForTheDatabase() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        "eu.nordtal.s2.limbo..",
                        "eu.nordtal.s2.hungergames..",
                        "eu.nordtal.s2.smp..",
                        "eu.nordtal.s2.papercommon..")
                .should()
                .callMethod("eu.nordtal.s2.common.message.PlayerLocales", "join", "java.util.UUID")
                .check(classes);
    }

    @Test
    void onlyStewardWorkerMigrates() {
        noClasses()
                .that()
                .resideOutsideOfPackage("eu.nordtal.s2.steward.worker..")
                .should()
                .callMethodWhere(DescribedPredicate.describe(
                        "a migration",
                        call -> call.getTarget().getName().equals("migrate")
                                && MIGRATORS.contains(call.getTargetOwner().getName())))
                .check(classes);
    }
}

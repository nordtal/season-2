package eu.nordtal.s2.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final Set<String> MIGRATORS =
            Set.of("org.flywaydb.core.Flyway", "eu.nordtal.jcore.persistence.sql.Database");

    private static final Set<String> WALL_CLOCK = Set.of(
            "java.time.Instant.now",
            "java.time.LocalDate.now",
            "java.time.LocalTime.now",
            "java.time.LocalDateTime.now",
            "java.time.ZonedDateTime.now",
            "java.time.OffsetDateTime.now",
            "java.time.Clock.system",
            "java.time.Clock.systemUTC",
            "java.time.Clock.systemDefaultZone",
            "java.lang.System.currentTimeMillis");

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

    @Test
    void theKernelDependsOnTheJdkAlone() {
        onlyOn("eu.nordtal.s2.common..", "java..", "org.jspecify..", "com.google.gson..", "eu.nordtal.s2.common..");
        onlyOn("eu.nordtal.s2.limboprotocol..", "java..", "org.jspecify..", "eu.nordtal.s2.limboprotocol..");
    }

    @Test
    void theDatabaseModuleDependsOnlyOnTheDatabaseStack() {
        onlyOn(
                "eu.nordtal.s2.database..",
                "java..",
                "javax.sql..",
                "org.jdbi..",
                "com.zaxxer.hikari..",
                "org.slf4j..",
                "org.postgresql..",
                "org.jspecify..",
                "eu.nordtal.s2.common..",
                "eu.nordtal.s2.database..");
    }

    @Test
    void theMessageCoreHasNoAdventure() {
        onlyOn(
                "eu.nordtal.s2.messages..",
                "java..",
                "org.slf4j..",
                "org.jspecify..",
                "eu.nordtal.s2.common..",
                "eu.nordtal.s2.messages..");
    }

    // Adventure is allowed here because both platforms provide it, so these modules only compile against it.
    @Test
    void theRenderersDependOnlyOnMessagesAndAdventure() {
        onlyOn(
                "eu.nordtal.s2.messagerendering..",
                "java..",
                "org.jspecify..",
                "net.kyori.adventure..",
                "eu.nordtal.s2.common..",
                "eu.nordtal.s2.messages..",
                "eu.nordtal.s2.messagerendering..");
        onlyOn(
                "eu.nordtal.s2.packrendering..",
                "java..",
                "org.jspecify..",
                "net.kyori.adventure..",
                "eu.nordtal.s2.common..",
                "eu.nordtal.s2.messages..",
                "eu.nordtal.s2.messagerendering..",
                "eu.nordtal.s2.packrendering..");
    }

    @Test
    void neitherTheBotNorStewardRendersForMinecraft() {
        noClasses()
                .that()
                .resideInAnyPackage("eu.nordtal.s2.discordbot..", "eu.nordtal.s2.steward..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "net.kyori.adventure..", "eu.nordtal.s2.messagerendering..", "eu.nordtal.s2.packrendering..")
                .check(classes);
    }

    @Test
    void onlyNetworkTimeReadsTheWallClock() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("eu.nordtal.s2.common.time.NetworkTime")
                .should()
                .accessTargetWhere(DescribedPredicate.describe(
                        "a read of the wall clock",
                        access -> WALL_CLOCK.contains(access.getTargetOwner().getName() + "."
                                        + access.getTarget().getName())
                                && !(access.getTarget() instanceof final CodeUnitAccessTarget unit
                                        && unit.getRawParameterTypes().stream()
                                                .anyMatch(type -> type.getName().equals("java.time.Clock")))))
                .because("each process creates one clock with NetworkTime.clock() and hands it down")
                .check(classes);
    }

    private static void onlyOn(final String module, final String... allowed) {
        classes()
                .that()
                .resideInAPackage(module)
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(allowed)
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
                .callMethod("eu.nordtal.s2.messages.PlayerLocales", "join", "java.util.UUID")
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

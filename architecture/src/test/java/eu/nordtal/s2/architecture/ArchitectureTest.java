package eu.nordtal.s2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import java.util.List;
import java.util.Set;
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

    /** The annotations a setting Steward lists is described by, from jcore's config spec. */
    private static final String SPEC = "eu.nordtal.jcore.config.spec.annotation.";

    /** Specs the import must find at least, so a module missing from the classpath cannot make it find none. */
    private static final List<String> KNOWN_SPECS = List.of(
            "eu.nordtal.s2.discordbot.config.AccessSpec",
            "eu.nordtal.s2.discordbot.config.BotSpec",
            "eu.nordtal.s2.hungergames.config.HungerGamesSpec",
            "eu.nordtal.s2.hungergames.config.SoundsSpec",
            "eu.nordtal.s2.limbo.config.LimboSpec",
            "eu.nordtal.s2.proxy.config.GateSpec",
            "eu.nordtal.s2.proxy.config.NetworkSpec",
            "eu.nordtal.s2.proxy.config.PackSpec",
            "eu.nordtal.s2.smp.config.MilestonesSpec",
            "eu.nordtal.s2.smp.config.PrestigeSpec",
            "eu.nordtal.s2.smp.config.SmpSpec",
            "eu.nordtal.s2.smp.config.SoundsSpec",
            "eu.nordtal.s2.settings.ColoursSpec",
            "eu.nordtal.s2.settings.DatabaseSpec",
            "eu.nordtal.s2.steward.config.WebSpec",
            "eu.nordtal.s2.steward.config.StewardSpec");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Codebase.classes();
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
                "com.google.gson..",
                "eu.nordtal.s2.common..",
                "eu.nordtal.s2.messages..",
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

    /** The wire to the internal services: Javalin and the kernel, and nothing a service does behind it. */
    @Test
    void theInternalWireDependsOnJavalinAndTheKernelAlone() {
        onlyOn(
                "eu.nordtal.s2.internalapi..",
                "java..",
                "org.jspecify..",
                "org.slf4j..",
                "io.javalin..",
                "com.google.gson..",
                "eu.nordtal.s2.common..",
                "eu.nordtal.s2.internalapi..");
    }

    /** The bank key is only useful to whatever can call bunq, so nothing outside steward-bunq can. */
    @Test
    void onlyStewardBunqHoldsTheBankClient() {
        noClasses()
                .that()
                .resideOutsideOfPackage("eu.nordtal.s2.stewardbunq..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.bunq..")
                .because("steward and everything else reach the bank through steward-bunq's internal API")
                .check(classes);
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
    void onlyStewardMigrates() {
        noClasses()
                .that()
                .resideOutsideOfPackage("eu.nordtal.s2.steward..")
                .should()
                .callMethodWhere(DescribedPredicate.describe(
                        "a migration",
                        call -> call.getTarget().getName().equals("migrate")
                                && MIGRATORS.contains(call.getTargetOwner().getName())))
                .check(classes);
    }

    @Test
    void theImportFindsEverySettingsSpec() {
        final List<String> missing = KNOWN_SPECS.stream()
                .filter(name -> !classes.contain(name) || !classes.get(name).isAnnotatedWith(SPEC + "ConfigSpec"))
                .toList();
        assertEquals(List.of(), missing, "a settings spec is not on this module's classpath, so no rule sees it");
    }

    /** Steward shows a listed setting by its name, never its key. */
    @Test
    void everyListedSettingHasAName() {
        methods()
                .that()
                .areDeclaredInClassesThat()
                .areAnnotatedWith(SPEC + "ConfigSpec")
                .and()
                .areAnnotatedWith(SPEC + "Order")
                .should()
                .beAnnotatedWith(SPEC + "Name")
                .check(classes);
    }

    /** Steward shows a sentence beside a listed setting, unless its spec says deliberately that none is needed. */
    @Test
    void everyListedSettingIsExplainedOrSaysItNeedsNoExplanation() {
        methods()
                .that()
                .areDeclaredInClassesThat()
                .areAnnotatedWith(SPEC + "ConfigSpec")
                .and()
                .areAnnotatedWith(SPEC + "Order")
                .should()
                .beAnnotatedWith(SPEC + "Explain")
                .orShould()
                .beAnnotatedWith(SPEC + "NoExplanationNeeded")
                .check(classes);
        noMethods()
                .that()
                .areAnnotatedWith(SPEC + "Explain")
                .should()
                .beAnnotatedWith(SPEC + "NoExplanationNeeded")
                .allowEmptyShould(true)
                .check(classes);
    }

    /** The proxy publishes the allowlist the backends filter by, gates what players type and routes only its own. */
    @Test
    void theProxyPublishesTheAllowlistAndGatesWhatPlayersType() {
        classes()
                .that()
                .haveFullyQualifiedName("eu.nordtal.s2.proxy.ProxyPlugin")
                .should()
                .callMethod(
                        "eu.nordtal.s2.database.command.AllowlistDirectory",
                        "publish",
                        "eu.nordtal.s2.database.command.CommandAllowlist")
                .andShould()
                .callConstructorWhere(builds("eu.nordtal.s2.proxy.command.CommandGate"))
                .andShould()
                .callConstructorWhere(builds("eu.nordtal.s2.proxy.routing.RouteIntents"))
                .check(classes);
    }

    /** Every Paper plugin filters commands by the published allowlist, since the base builds the filter. */
    @Test
    void thePluginBaseFiltersCommandsByTheAllowlist() {
        classes()
                .that()
                .haveFullyQualifiedName("eu.nordtal.s2.papercommon.plugin.NordtalPlugin")
                .should()
                .callConstructorWhere(builds("eu.nordtal.s2.papercommon.command.CommandFilter"))
                .check(classes);
    }

    private static DescribedPredicate<JavaConstructorCall> builds(final String owner) {
        return DescribedPredicate.describe(
                "a new " + owner, call -> call.getTargetOwner().getName().equals(owner));
    }
}

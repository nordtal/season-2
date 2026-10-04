package eu.nordtal.s2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
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

    /** Every member of these makes a thread pool or a timer, or is one. */
    private static final Set<String> POOLS = Set.of(
            "java.util.concurrent.Executors",
            "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            "java.util.concurrent.ForkJoinPool",
            "java.util.Timer");

    /** The annotations a setting Steward lists is described by, from jcore's config spec. */
    private static final String SPEC = "eu.nordtal.jcore.config.spec.annotation.";

    /** Specs the import must find at least, so a module missing from the classpath cannot make it find none. */
    private static final List<String> KNOWN_SPECS = List.of(
            "eu.nordtal.s2.discordbot.config.AccessSpec",
            "eu.nordtal.s2.discordbot.config.BotSpec",
            "eu.nordtal.s2.hungergames.config.HungerGamesSpec",
            "eu.nordtal.s2.limbo.config.LimboSpec",
            "eu.nordtal.s2.papercommon.sound.SoundsSpec",
            "eu.nordtal.s2.proxy.config.GateSpec",
            "eu.nordtal.s2.proxy.config.NetworkSpec",
            "eu.nordtal.s2.proxy.config.PackSpec",
            "eu.nordtal.s2.smp.config.MilestonesSpec",
            "eu.nordtal.s2.smp.config.SmpSpec",
            "eu.nordtal.s2.settings.ColoursSpec",
            "eu.nordtal.s2.settings.DatabaseSpec",
            "eu.nordtal.s2.settings.network.PlayersSpec",
            "eu.nordtal.s2.settings.network.PrestigeSpec",
            "eu.nordtal.s2.steward.config.WebSpec",
            "eu.nordtal.s2.steward.config.StewardSpec",
            "eu.nordtal.s2.stewardagent.config.RunSpec");

    /** A call that builds a message renderer, which only the base of a process does. */
    private static final String RENDERER = "eu.nordtal.s2.messagerendering.MessageRenderer";

    private static final DescribedPredicate<JavaAccess<?>> BUILDS_A_RENDERER = DescribedPredicate.describe(
            "a new renderer",
            access -> access.getTargetOwner().getName().equals(RENDERER)
                    && Set.of("<init>", "of").contains(access.getTarget().getName()));

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

    /** dev runs the command lines steward-agent's Compose writes, and takes nothing else of the agent's along. */
    @Test
    void devTakesOnlyTheComposeLinesFromTheAgent() {
        classes()
                .that()
                .resideInAPackage("eu.nordtal.s2.dev..")
                .should()
                .onlyDependOnClassesThat(DescribedPredicate.describe(
                        "are the JDK's, jspecify's, dev's own or steward-agent's Compose",
                        type -> type.getPackageName().startsWith("java.")
                                || type.getPackageName().startsWith("org.jspecify")
                                || type.getPackageName().startsWith("eu.nordtal.s2.dev")
                                || type.getName().equals("eu.nordtal.s2.stewardagent.Compose")))
                .check(classes);
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

    @Test
    void onlyTheProcessSchedulerOwnsAThreadPoolOrATimer() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("eu.nordtal.s2.common.time.ProcessScheduler")
                .should()
                .accessTargetWhere(DescribedPredicate.describe(
                        "a thread pool or a timer of its own", ArchitectureTest::reachesAPoolOrATimer))
                .because("a process makes one Scheduler where it starts and hands it to whatever schedules")
                .check(classes);
    }

    /** A pool or a timer, a virtual thread per call, or the common pool behind an async call given no executor. */
    private static boolean reachesAPoolOrATimer(final JavaAccess<?> access) {
        final String owner = access.getTargetOwner().getName();
        final String member = access.getTarget().getName();
        if (POOLS.contains(owner)) {
            return true;
        }
        if (owner.equals("java.lang.Thread")) {
            return member.equals("ofVirtual") || member.equals("startVirtualThread");
        }
        if (owner.equals("java.util.concurrent.CompletableFuture") && member.equals("delayedExecutor")) {
            return true;
        }
        return member.endsWith("Async")
                && owner.startsWith("java.util.concurrent.")
                && access.getTarget() instanceof final CodeUnitAccessTarget unit
                && unit.getRawParameterTypes().stream()
                        .noneMatch(type -> type.getName().equals("java.util.concurrent.Executor"));
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
        // Pre-login runs on Paper's login thread and a hub refresh on the hub's: the two that may block.
        noClasses()
                .that()
                .resideInAnyPackage(
                        "eu.nordtal.s2.limbo..",
                        "eu.nordtal.s2.hungergames..",
                        "eu.nordtal.s2.smp..",
                        "eu.nordtal.s2.papercommon..")
                .and()
                .doNotHaveFullyQualifiedName("eu.nordtal.s2.papercommon.player.Presence")
                .should()
                .callMethod("eu.nordtal.s2.papercommon.player.Identities", "load", "eu.nordtal.s2.common.id.PlayerId")
                .orShould()
                .callMethod("eu.nordtal.s2.papercommon.player.Identities", "reread")
                .check(classes);
        classes()
                .that()
                .haveFullyQualifiedName("eu.nordtal.s2.papercommon.player.Presence")
                .should(Wiring.callOnlyFrom("onPreLogin", "Identities#load"))
                .check(classes);
    }

    @Test
    void onlyStewardAgentMigrates() {
        noClasses()
                .that()
                .resideOutsideOfPackage("eu.nordtal.s2.stewardagent..")
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

    /** The proxy gates what players type by the network's allowlist and routes only its own. */
    @Test
    void theProxyGatesWhatPlayersType() {
        classes()
                .that()
                .haveFullyQualifiedName("eu.nordtal.s2.proxy.ProxyPlugin")
                .should()
                .callMethod(
                        "eu.nordtal.s2.settings.network.NetworkSettings",
                        "allowlist",
                        "eu.nordtal.s2.settings.network.PlayersSpec")
                .andShould()
                .callConstructorWhere(builds("eu.nordtal.s2.proxy.command.CommandGate"))
                .andShould()
                .callConstructorWhere(builds("eu.nordtal.s2.proxy.routing.RouteIntents"))
                .check(classes);
    }

    /** Every Paper plugin filters commands by the network's allowlist, since the base builds the filter. */
    @Test
    void thePluginBaseFiltersCommandsByTheAllowlist() {
        classes()
                .that()
                .haveFullyQualifiedName("eu.nordtal.s2.papercommon.plugin.NordtalPlugin")
                .should()
                .callConstructorWhere(builds("eu.nordtal.s2.papercommon.command.CommandFilter"))
                .check(classes);
    }

    /** A server and the proxy render through their base's one renderer, which draws every name with its card. */
    @Test
    void onlyThePluginBaseBuildsARenderer() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        "eu.nordtal.s2.limbo..",
                        "eu.nordtal.s2.hungergames..",
                        "eu.nordtal.s2.smp..",
                        "eu.nordtal.s2.papercommon..",
                        "eu.nordtal.s2.proxy..")
                .and()
                .doNotHaveFullyQualifiedName("eu.nordtal.s2.papercommon.plugin.NordtalPlugin")
                .and()
                .doNotHaveFullyQualifiedName("eu.nordtal.s2.proxy.ProxyPlugin")
                .should()
                .accessTargetWhere(BUILDS_A_RENDERER)
                .check(classes);
    }

    /** Settings live in the database: only :settings reads a YAML file, the last installation's, to import it once. */
    @Test
    void onlyTheSettingsModuleReadsASettingsFile() {
        noClasses()
                .that()
                .resideOutsideOfPackage("eu.nordtal.s2.settings..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("eu.nordtal.jcore.config.spec.CommentedConfiguration")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("eu.nordtal.jcore.config.ConfigHandle")
                .orShould()
                .callMethodWhere(DescribedPredicate.describe(
                        "a file read through jcore's ConfigLoader",
                        call -> call.getTargetOwner().getName().equals("eu.nordtal.jcore.config.ConfigLoader")
                                && !call.getName().equals("gsonBuilder")))
                .check(classes);
    }

    private static DescribedPredicate<JavaConstructorCall> builds(final String owner) {
        return DescribedPredicate.describe(
                "a new " + owner, call -> call.getTargetOwner().getName().equals(owner));
    }
}

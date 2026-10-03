package eu.nordtal.s2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static eu.nordtal.s2.architecture.Wiring.alwaysOnOneLine;
import static eu.nordtal.s2.architecture.Wiring.callFrom;
import static eu.nordtal.s2.architecture.Wiring.callInOrder;
import static eu.nordtal.s2.architecture.Wiring.callOnOneLine;
import static eu.nordtal.s2.architecture.Wiring.callOnceFrom;
import static eu.nordtal.s2.architecture.Wiring.callOnlyFrom;
import static eu.nordtal.s2.architecture.Wiring.isListed;
import static eu.nordtal.s2.architecture.Wiring.neverCallFrom;
import static eu.nordtal.s2.architecture.Wiring.reaches;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** How the SMP plugin reloads its track, settles duels and graves, guards its figure and stages its welcome. */
class SmpRulesTest {

    private static final String PLUGIN = "eu.nordtal.s2.smp.SmpPlugin";
    private static final String START = "eu.nordtal.s2.smp.SmpStart";
    private static final String PLUGIN_BASE = "eu.nordtal.s2.papercommon.plugin.NordtalPlugin";
    private static final String TRACK = "eu.nordtal.s2.smp.milestone.MilestoneTrack";
    private static final String ENGINE = "eu.nordtal.s2.smp.progress.ObjectiveEngine";
    private static final String POLLER = "eu.nordtal.s2.smp.progress.StatisticPoller";
    private static final String DUELS = "eu.nordtal.s2.smp.duel.Duels";
    private static final String DUEL_LISTENER = "eu.nordtal.s2.smp.duel.DuelListener";
    private static final String GRAVES = "eu.nordtal.s2.smp.grave.Graves";
    private static final String NPC = "eu.nordtal.s2.smp.npc.SpawnNpc";
    private static final String NPC_GUARD = "eu.nordtal.s2.smp.npc.NpcProtection";
    private static final String PORTAL_GATE = "eu.nordtal.s2.smp.travel.PortalGate";
    private static final String PRESENCE = "eu.nordtal.s2.smp.player.PresenceListener";
    private static final String LANDING = "eu.nordtal.s2.smp.world.LandingSite";

    /** Everything that outlives a reload and reads the milestone track. */
    private static final List<String> TRACK_READERS = List.of(
            ENGINE,
            POLLER,
            "eu.nordtal.s2.smp.progress.GateHolders",
            "eu.nordtal.s2.smp.npc.NpcListener",
            "eu.nordtal.s2.smp.travel.BalloonListener");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Codebase.classes();
    }

    /** {@code /smp reload} replaces the field, so every long-lived reader is handed a way to read it, not a track. */
    @Test
    void everyReaderOfTheTrackSeesAReload() {
        for (final String reader : TRACK_READERS) {
            for (final JavaConstructor constructor : classes.get(reader).getConstructors()) {
                final List<String> parameters = constructor.getRawParameterTypes().stream()
                        .map(JavaClass::getName)
                        .toList();
                assertTrue(
                        parameters.contains("java.util.function.Supplier") && !parameters.contains(TRACK),
                        reader + " is handed a track rather than a way to read the current one, so /smp reload"
                                + " would report success and leave it on the definitions the server started with");
            }
        }
        classes()
                .that(isListed(START))
                .should(callFrom(
                        "wireProgressEngine",
                        "ObjectiveEngine#<init>",
                        "StatisticPoller#<init>",
                        "GateHolders#<init>",
                        "SmpPlugin#track"))
                .andShould(callFrom("wireNpc", "NpcListener#<init>", "SmpPlugin#track"))
                .andShould(callFrom("restoreGravesAndRegisterWorld", "BalloonListener#<init>", "SmpPlugin#track"))
                .check(classes);
        // A reload writes the field off the server thread, and every reader is on it.
        assertTrue(
                classes.get(PLUGIN).getField("track").getModifiers().contains(JavaModifier.VOLATILE),
                "SmpPlugin.track is not volatile");
    }

    /** A reload is checked against the server and the rows, written, applied, and only then swept. */
    @Test
    void aReloadIsCheckedWrittenAppliedThenSwept() {
        classes()
                .that(isListed(PLUGIN))
                .should(callFrom("loadMilestoneTrack", "TrackNames#validate"))
                .andShould(callInOrder(
                        "reloadMilestoneTrack",
                        "TrackValidation#validate",
                        "TrackNames#validate",
                        "List#isEmpty",
                        "SmpPlugin#ensureRows",
                        "SmpPlugin#track",
                        "SmpPlugin#completeWhateverTheNewTargetsAlreadyReach"))
                .andShould(callOnOneLine(
                        "reloadMilestoneTrack",
                        "StoredProgress#<init>",
                        "SmpDao#storedMilestones",
                        "SmpDao#storedObjectives"))
                .because("the sweep decides against the targets in the rows, and a refused file must never be"
                        + " applied")
                .check(classes);
    }

    /** The rows carry the targets payouts are computed from, so a reload writes all of them or none. */
    @Test
    void theRowsAreWrittenInOneTransaction() {
        classes()
                .that(isListed(PLUGIN))
                .should(callInOrder(
                        "ensureRows",
                        "Jdbi#useTransaction",
                        "Handle#attach",
                        "SmpDao#ensureMilestone",
                        "SmpDao#ensureObjective"))
                .andShould(neverCallFrom("ensureRows", "SmpPlugin#dao"))
                .because("the plugin's own dao writes outside the transaction, one statement at a time")
                .check(classes);
    }

    /** One unlock is built from one track, and a changed track starts the statistic baselines over. */
    @Test
    void aReaderTakesOneTrackAtATime() {
        classes()
                .that(isListed(ENGINE))
                .should(callOnceFrom("unlockMilestone", "Supplier#get"))
                .because("two reads can answer two tracks and name a milestone the state does not hold")
                .check(classes);
        classes()
                .that(isListed(POLLER))
                .should(callInOrder("poll", "Supplier#get", "StatisticPoller#sampledUnder", "Map#clear"))
                .because("a baseline read under the old definitions credits a widened objective with everything"
                        + " that was already there")
                .check(classes);
    }

    /** A dead fighter is respawned at once and gets their own life back behind the screen; a living one at once. */
    @Test
    void aFighterGetsTheirOwnLifeBackWithoutWaiting() {
        classes()
                .that(isListed(DUELS))
                .should(callInOrder("restore", "Player#isDead", "Map#put", "Spigot#respawn", "SavedState#restore"))
                .andShould(callOnOneLine("restore", "SavedState#restore", "Duels#spawn"))
                .andShould(callFrom("restore", "Player#showTitle"))
                .andShould(callInOrder("respawned", "Map#remove", "BukkitScheduler#runTask", "SavedState#restore"))
                .because("the parked state is process memory only, and the outcome is read off the screen")
                .check(classes);
        handled(DUEL_LISTENER, "org.bukkit.event.player.PlayerRespawnEvent");
        classes()
                .that(isListed(DUEL_LISTENER))
                .should(callFrom("onRespawn", "Duels#respawned"))
                .check(classes);
    }

    /** The two Discord ids are read at the start, since the base forgets an identity on quit before it is settled. */
    @Test
    void aQuitCostsTheSameAsALoss() {
        classes()
                .that(isListed(DUELS))
                .should(callOnlyFrom("begin", "Identities#discordIdOf"))
                .andShould(callFrom("book", "ActiveDuel#discordIds"))
                .check(classes);
    }

    /** A duel begins and ends a tick after the event, and its lethal blow is cancelled rather than shown. */
    @Test
    void aDuelMovesNobodyInsideTheEventThatDecidedIt() {
        handled(DUEL_LISTENER, "org.bukkit.event.entity.EntityDamageEvent");
        classes()
                .that(isListed(DUELS))
                .should(callInOrder("steppedOn", "BukkitScheduler#runTask", "Duels#begin"))
                .andShould(callOnceFrom("steppedOn", "Duels#begin"))
                .because("a teleport inside a move event is undone once its handlers return")
                .check(classes);
        classes()
                .that(isListed(DUEL_LISTENER))
                .should(callOnOneLine("onDeath", "BukkitScheduler#runTask", "Duels#decide"))
                .andShould(callOnceFrom("onDeath", "Duels#decide"))
                .andShould(callInOrder("onDamage", "EntityDamageEvent#setCancelled", "Duels#decide"))
                .andShould(callOnOneLine("onDamage", "BukkitScheduler#runTask", "Duels#decide"))
                .andShould(callOnceFrom("onDamage", "Duels#decide"))
                .because("the grave listener asks about the arena after this one, and a death cannot be un-shown")
                .check(classes);
    }

    /** One grave is one window, settled by the last viewer, and its head comes back on close, not with the loot. */
    @Test
    void oneGraveIsOneWindowAndTheHeadIsNotLoot() {
        classes()
                .that(isListed(GRAVES))
                .should(callOnOneLine("open", "Map#computeIfAbsent", "Graves#window"))
                .andShould(callOnlyFrom("window", "Bukkit#createInventory"))
                .andShould(callOnceFrom("window", "Bukkit#createInventory"))
                .andShould(callOnOneLine("onClosed", "BukkitScheduler#runTask", "Graves#settle"))
                .andShould(callOnceFrom("onClosed", "Graves#settle"))
                .andShould(callFrom("settle", "Inventory#getViewers", "Graves#returnHeadToPlayer"))
                .andShould(neverCallFrom("settle", "GravePanel#headSlot"))
                .andShould(neverCallFrom("takeAll", "GravePanel#headSlot"))
                .andShould(callInOrder(
                        "returnHeadToPlayer",
                        "GravePanel#headSlot",
                        "PlayerInventory#addItem",
                        "World#dropItemNaturally"))
                .because("a window per viewer paid the loot out twice, and Bukkit drops the viewer after the close")
                .check(classes);
    }

    /** A grave that settles sounds for everybody standing at it, in its own category. */
    @Test
    void aSettlingGraveSoundsAtTheGrave() {
        classes()
                .that(isListed(GRAVES))
                .should(callFrom("expire", "SmpSounds#playAt", "Feedback#RECLAIMED"))
                .andShould(callFrom("finishLooting", "SmpSounds#playAt", "Feedback#RECLAIMED"))
                .check(classes);
    }

    /** The figure in the tavern is the only way to hand in, so nothing may hurt, burn or shove it. */
    @Test
    void theFigureSurvivesAndIsTheOnlyOne() {
        final Map<String, String> guards = Map.of(
                "onDamage", "org.bukkit.event.entity.EntityDamageEvent",
                "onCombust", "org.bukkit.event.entity.EntityCombustEvent",
                "onKnockback", "io.papermc.paper.event.entity.EntityKnockbackEvent");
        guards.forEach((handler, event) -> {
            handled(NPC_GUARD, event);
            classes()
                    .that(isListed(NPC_GUARD))
                    .should(callFrom(
                            handler, "SpawnNpc#is", event.substring(event.lastIndexOf('.') + 1) + "#setCancelled"))
                    .check(classes);
        });
        assertFalse(
                classes.get(NPC_GUARD).getCodeUnits().stream()
                        .flatMap(unit -> unit.getInstanceofChecks().stream())
                        .anyMatch(check -> check.getRawType().getName().equals("org.bukkit.entity.Mannequin")),
                "the guard recognises the figure by type, which protects every mannequin on the server");
        classes()
                .that(isListed(START))
                .should(callOnOneLine("wireNpc", "NpcProtection#<init>", "PluginManager#registerEvents"))
                .check(classes);
        classes()
                .that(isListed(NPC))
                .should(callOnlyFrom("spawn", "World#spawn"))
                .andShould(callOnceFrom("spawn", "World#spawn"))
                .andShould(callFrom(
                        "spawn",
                        "Mannequin#setImmovable",
                        "Mannequin#setInvulnerable",
                        "Mannequin#setSilent",
                        "Mannequin#setPersistent"))
                .andShould(callOnOneLine("spawn", "SpawnNpc#spawned", "Mannequin#getUniqueId"))
                .because("a second way to the figure is where a flag is forgotten, and an unremembered one is"
                        + " defended by nobody")
                .check(classes);
    }

    /** The configured name is the figure's visible label, and the description under it is not drawn at all. */
    @Test
    void theFigureCarriesOneLabel() {
        classes()
                .that(isListed(NPC))
                .should(callFrom(
                        "spawn", "Mannequin#customName", "Mannequin#setCustomNameVisible", "Mannequin#setDescription"))
                .andShould(neverCallFrom("spawn", "Component#empty"))
                .because("left alone the description draws \"NPC\" under the name, and an empty one a blank strip")
                .check(classes);
    }

    /** A refused portal ignition puts out the fire the flint and steel placed, a tick later. */
    @Test
    void aRefusedPortalLeavesNoFire() {
        classes()
                .that(isListed(PORTAL_GATE))
                .should(callInOrder("onPortalCreate", "PortalCreateEvent#setCancelled", "PortalGate#putOutTheFire"))
                .andShould(callInOrder("putOutTheFire", "BukkitScheduler#runTask", "Block#setType"))
                .because("a block placed in the same call stack is placed again once the event returns")
                .check(classes);
    }

    /** The season's opening moment is built, registered, stopped at disable and run once the language is known. */
    @Test
    void theOpeningMomentRunsInThePlayersLanguage() {
        classes()
                .that(isListed(START))
                .should(callInOrder(
                        "wirePresenceInputs",
                        "BukkitCinematics#<init>",
                        "PluginManager#registerEvents",
                        "SeasonWelcome#<init>"))
                .andShould(callFrom(
                        "registerPresenceListeners", "PresenceListener#<init>", "SeasonWelcome#onLanguageReady"))
                .check(classes);
        classes()
                .that(isListed(PLUGIN))
                .should(callFrom("languageKnown", "PresenceListener#languageKnown"))
                .andShould(callFrom("disable", "BukkitCinematics#stop"))
                .because("Paper disables plugins before it saves players, so a running staging saves its blindness")
                .check(classes);
        classes()
                .that(isListed(PRESENCE))
                .should(callInOrder("languageKnown", "SystemLines#announceJoin", "Consumer#accept"))
                .andShould(neverCallFrom("onJoin", "Consumer#accept"))
                .because("the join line and the opening moment run once every join handler ran")
                .check(classes);
    }

    /** A wheel still spinning at shutdown pays out through its one-shot latch, before the pool is gone. */
    @Test
    void aSpinStillRunningAtShutdownPaysOut() {
        classes()
                .that(isListed(PLUGIN))
                .should(callFrom("disable", "SmpPlugin#payOutSpinsInFlight"))
                .andShould(callFrom("payOutSpinsInFlight", "WheelGui#finish"))
                .because("Paper disables plugins before it disconnects players, and the spin is spent before the"
                        + " first frame")
                .check(classes);
        classes()
                .that(isListed(PLUGIN_BASE))
                .should(callInOrder("onDisable", "NordtalPlugin#disable", "HikariDataSource#close"))
                .check(classes);
    }

    /** A world spawn is a coordinate, not a promise: a player only reaches one through {@code LandingSite}. */
    @Test
    void everyWorldSpawnIsLandingChecked() {
        classes()
                .that()
                .resideInAPackage("eu.nordtal.s2.smp..")
                // NavigateGui only shows the spawn's coordinates and never moves a player.
                .and(DescribedPredicate.not(isListed(LANDING, "eu.nordtal.s2.smp.navigate.NavigateGui")))
                .should(alwaysOnOneLine(
                        reaches("org.bukkit.World", "getSpawnLocation"),
                        DescribedPredicate.describe(
                                "a landing check",
                                access -> access.getTargetOwner().getName().equals(LANDING)
                                        && Set.of("safeAt", "findSafeAt")
                                                .contains(access.getTarget().getName()))))
                .because("safeAt is free on a good spot and the difference between arriving and suffocating on a"
                        + " bad one")
                .check(classes);
    }

    /** Holds that the listener's method taking {@code event} is an event handler. */
    private static void handled(final String listener, final String event) {
        methods()
                .that()
                .areDeclaredIn(listener)
                .and()
                .haveRawParameterTypes(event)
                .should()
                .beAnnotatedWith("org.bukkit.event.EventHandler")
                .check(classes);
    }
}

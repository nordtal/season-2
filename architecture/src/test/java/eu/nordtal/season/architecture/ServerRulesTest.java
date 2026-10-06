package eu.nordtal.season.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static eu.nordtal.season.architecture.Wiring.callFrom;
import static eu.nordtal.season.architecture.Wiring.callInOrder;
import static eu.nordtal.season.architecture.Wiring.isListed;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Wiring inside the game servers and the bot that a test without a running server can only read off the code. */
class ServerRulesTest {

    private static final String MANAGER = "eu.nordtal.season.hungergames.game.HungerGamesManager";
    private static final String FREEZE = "eu.nordtal.season.hungergames.freeze.FreezeListener";
    private static final String HUD = "eu.nordtal.season.hungergames.hud.GameHud";
    private static final String COMBAT = "eu.nordtal.season.hungergames.combat.CombatListener";
    private static final String LIMBO_PRESENCE = "eu.nordtal.season.limbo.presence.PresenceListener";
    private static final String BOT = "eu.nordtal.season.discordbot.AccessBot";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Codebase.classes();
    }

    /** A frozen participant hovers on a tower, which vanilla kicks for unless flight is allowed. */
    @Test
    void aParticipantOnATowerMayFlyAndLandsBeforeTheReleaseTakesItBack() {
        classes()
                .that(isListed(MANAGER))
                .should(callFrom("placeOnTower", "Player#setAllowFlight"))
                .andShould(callInOrder("release", "Player#setFlying", "Player#setAllowFlight"))
                .check(classes);
        classes()
                .that(isListed(FREEZE))
                .should(Wiring.reachInside(Wiring.reaches("org.bukkit.event.player.PlayerMoveEvent", "setTo")))
                .because("the freeze cancels the fall, which is what makes the flight necessary")
                .check(classes);
    }

    /** Two setters once had no caller and printed zeroes all game; the renderer pulls from what owns the number. */
    @Test
    void theHudPullsItsNumbersFromWhatOwnsThem() {
        noMethods()
                .that()
                .areDeclaredIn(HUD)
                .should()
                .haveNameMatching("set[A-Z].*")
                .allowEmptyShould(true)
                .check(classes);
        classes()
                .that(isListed(HUD))
                .should(Wiring.reachInside(
                        Wiring.reaches("eu.nordtal.season.hungergames.game.WinTracker", "aliveCount")))
                .andShould(Wiring.reachInside(
                        Wiring.reaches("eu.nordtal.season.hungergames.game.WinTracker", "deadCount")))
                .andShould(Wiring.reachInside(
                        Wiring.reaches("eu.nordtal.season.hungergames.loot.LootRefill", "nextRefillAt")))
                .check(classes);
    }

    @Test
    void aBodysDeathIsAnnouncedFromTheHandlerThatBooksIt() {
        classes()
                .that(isListed(COMBAT))
                .should(callFrom("onMarkerDeath", "CombatListener#announceBodyDeath"))
                .andShould(callFrom("announceBodyDeath", "SystemLines#announce"))
                .check(classes);
    }

    /** Nobody on the limbo can reach anybody: chat and commands are handled, and everybody is hidden both ways. */
    @Test
    void thePassageIsSilentAndEverybodyIsHidden() {
        for (final String event : new String[] {
            "io.papermc.paper.event.player.AsyncChatEvent", "org.bukkit.event.player.PlayerCommandPreprocessEvent"
        }) {
            methods()
                    .that()
                    .areDeclaredIn(LIMBO_PRESENCE)
                    .and()
                    .haveRawParameterTypes(event)
                    .should()
                    .beAnnotatedWith("org.bukkit.event.EventHandler")
                    .check(classes);
        }
        final JavaCodeUnit hiding = classes.get(LIMBO_PRESENCE).getCodeUnits().stream()
                .filter(unit -> unit.getName().equals("hideEverybodyFromEachOther"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the limbo's PresenceListener hides nobody any more"));
        assertTrue(
                hiding.getMethodCallsFromSelf().stream()
                                .filter(call -> call.getName().equals("hidePlayer"))
                                .count()
                        >= 2,
                "hidePlayer is one-way, so hiding both ways takes two calls");
    }

    /** A token Discord rejects is a setting to fix, not a stack trace in a restart loop. */
    @Test
    void aRejectedTokenIsCaughtAndTheRestartBacksOff() {
        assertTrue(
                classes.get(BOT).getCodeUnits().stream()
                        .filter(unit -> unit.getName().equals("main"))
                        .flatMap(unit -> unit.getTryCatchBlocks().stream())
                        .flatMap(block -> block.getCaughtThrowables().stream())
                        .anyMatch(
                                type -> type.getName().equals("net.dv8tion.jda.api.exceptions.InvalidTokenException")),
                "AccessBot.main no longer catches InvalidTokenException");
        classes()
                .that(isListed(BOT))
                .should(callFrom("main", "AccessBot#backOffThenExit"))
                .andShould(callFrom("backOffThenExit", "Waiting#on"))
                .because("the back-off goes through Waiting, which stays interruptible for a SIGTERM")
                .check(classes);
    }
}

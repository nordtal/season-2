package eu.nordtal.s2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static eu.nordtal.s2.architecture.Wiring.callFrom;
import static eu.nordtal.s2.architecture.Wiring.callInOrder;
import static eu.nordtal.s2.architecture.Wiring.isListed;
import static eu.nordtal.s2.architecture.Wiring.neverCallFrom;
import static eu.nordtal.s2.architecture.Wiring.reachInside;
import static eu.nordtal.s2.architecture.Wiring.reaches;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** How a process says it is alive, and what it may write about a player. */
class ProcessRulesTest {

    private static final String READINESS = "eu.nordtal.s2.common.health.Readiness";
    private static final String PAPER = "eu.nordtal.s2.papercommon.plugin.NordtalPlugin";
    private static final String PROXY = "eu.nordtal.s2.proxy.ProxyPlugin";
    private static final String BOT = "eu.nordtal.s2.discordbot.AccessBot";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Codebase.classes();
    }

    /** A marker on a path of its own is one compose.yml does not read; one written once stays green when dead. */
    @Test
    void everyKindOfProcessBeatsOnTheSharedMarker() {
        classes()
                .that(isListed(PAPER, PROXY, BOT))
                .should(reachInside(reaches(READINESS, "onDefaultPath")))
                // A beat of its own, or Readiness's on the process's scheduler.
                .andShould(reachInside(reaches(READINESS, "refresh").or(reaches(READINESS, "keepBeating"))))
                .check(classes);
    }

    /** A proxy with the gate off still binds its port, so only the marker can say it refuses logins. */
    @Test
    void theProxyBeatsOnlyOnceItStartedAndStopsBeatingOnTheWayDown() {
        classes()
                .that(isListed(PROXY))
                .should(callFrom("start", "ProxyPlugin#startHeartbeat"))
                .andShould(neverCallFrom("failClosed", "ProxyPlugin#startHeartbeat"))
                .andShould(reachInside(reaches("com.velocitypowered.api.scheduler.ScheduledTask", "cancel")))
                .check(classes);
        assertEquals(
                1,
                classes.get(PROXY).getMethod("startHeartbeat").getCallsOfSelf().size(),
                "the proxy starts its beat from more than one place; the fail-closed path must not be one");
    }

    /** A bot that dies in its startup reconcile must never have reported ready. */
    @Test
    void theBotBeatsOnlyAfterDiscordIsReadyAndTheReconcilesAreDone() {
        classes()
                .that(isListed(BOT))
                .should(callInOrder("<init>", "AccessBot#publishAndReconcile", "AccessBot#finishStartup"))
                .andShould(callFrom("publishAndReconcile", "AccessRoles#reconcile"))
                .andShould(callInOrder("finishStartup", "AccessBot#listen", "Readiness#keepBeating"))
                .because("the beat runs on the one scheduler every other duty of the bot runs on")
                .check(classes);
    }

    /** The bot sees a phase change through its hub like every other process; a timer of its own is a poll. */
    @Test
    void theBotSeesThePhaseThroughItsHubAndNotOnATimer() {
        classes()
                .that(isListed(BOT))
                .should(callFrom("listen", "Channel#PHASE", "StatusChannels#tick"))
                .andShould(neverCallFrom("schedule", "StatusChannels#tick"))
                .check(classes);
    }

    /** A person in this schema is a Discord id in a {@code varchar(32)}; a UUID as text is 36 characters. */
    @Test
    void noMinecraftUuidIsSpelledAsText() {
        classes()
                .that()
                .resideInAnyPackage(
                        "eu.nordtal.s2.database..",
                        "eu.nordtal.s2.papercommon..",
                        "eu.nordtal.s2.smp..",
                        "eu.nordtal.s2.limbo..",
                        "eu.nordtal.s2.hungergames..",
                        "eu.nordtal.s2.proxy..")
                .should(Wiring.neverOnOneLine(uniqueId(), reaches("java.util.UUID", "toString")))
                .because("resolve the Discord id (Identities#discordIdOf) or bind a java.util.UUID to a uuid column")
                .check(classes);
    }

    private static DescribedPredicate<JavaAccess<?>> uniqueId() {
        return DescribedPredicate.describe(
                "a player's getUniqueId()",
                access -> access.getTarget().getName().equals("getUniqueId"));
    }
}

package eu.nordtal.s2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static eu.nordtal.s2.architecture.Wiring.callFrom;
import static eu.nordtal.s2.architecture.Wiring.callInOrder;
import static eu.nordtal.s2.architecture.Wiring.callOnceFrom;
import static eu.nordtal.s2.architecture.Wiring.callOnlyFrom;
import static eu.nordtal.s2.architecture.Wiring.isListed;
import static eu.nordtal.s2.architecture.Wiring.isOrIsNestedIn;
import static eu.nordtal.s2.architecture.Wiring.reachInside;
import static eu.nordtal.s2.architecture.Wiring.reaches;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** How the proxy routes players, warns them of a run, moves them at zero and hands them back, as it is wired. */
class ProxyRulesTest {

    private static final String PROXY = "eu.nordtal.s2.proxy.ProxyPlugin";
    private static final String PHASE_SERVERS = "eu.nordtal.s2.proxy.PhaseServers";
    private static final String SETTINGS = "eu.nordtal.s2.proxy.config.ProxySettings";
    private static final String GATE = "eu.nordtal.s2.proxy.config.GateSpec";
    private static final String WATCH = "eu.nordtal.s2.proxy.update.RestartWatch";
    private static final String EVACUATION = "eu.nordtal.s2.proxy.update.Evacuation";
    private static final String STANDBY_RETURN = "eu.nordtal.s2.proxy.update.StandbyReturn";
    private static final String UPDATES = "eu.nordtal.s2.database.update.UpdateDirectory";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Codebase.classes();
    }

    /** In maintenance the admin flag decides between the SMP and the waiting room, not only who may type. */
    @Test
    void aChangedAdminFlagReroutesThePlayers() {
        classes()
                .that(isListed(PROXY))
                .should(callInOrder("start", "LoginRoster#refreshAdmins", "PlayerRouter#rerouteAll"))
                .because("a revoked admin otherwise stays on the SMP until the phase happens to change")
                .check(classes);
    }

    /** There are two waiting rooms, and a player parked on the standby one is in a waiting room too. */
    @Test
    void nobodyAsksAboutTheWaitingRoomByOneName() {
        classes()
                .that()
                .resideInAPackage("eu.nordtal.s2.proxy..")
                .and(DescribedPredicate.not(isListed(PHASE_SERVERS)))
                .should(Wiring.neverOnOneLine(
                        reaches(PHASE_SERVERS, "limbo"),
                        DescribedPredicate.describe(
                                "an equals",
                                access -> access.getTarget().getName().equals("equals"))))
                .because("PhaseServers#isWaitingRoom knows both")
                .check(classes);
        noClasses()
                .that(DescribedPredicate.not(isListed(PHASE_SERVERS, SETTINGS)))
                .should()
                .callMethodWhere(reaches(GATE, "serverLimbo"))
                .because("a class handed one waiting room's name keeps it; take PhaseServers instead")
                .check(classes);
    }

    /** A countdown that has not run out can still be called off, so nobody is moved before zero. */
    @Test
    void theEvacuationDecidesOffTheRunningRowAlone() {
        noClasses()
                .that(isOrIsNestedIn(EVACUATION))
                .should()
                .callMethodWhere(reaches(UPDATES, "countingDown"))
                .check(classes);
        classes()
                .that(isListed(EVACUATION))
                .should(reachInside(reaches(UPDATES, "running")))
                .check(classes);
    }

    /** At zero the backends empty into the waiting room first, then the network goes onto the standby proxy. */
    @Test
    void zeroMovesTheBackendsThenTheNetwork() {
        classes()
                .that(isListed(WATCH))
                .should(callInOrder(
                        "schedule", "Kind#NOW", "Countdown#zeroReached", "RestartWatch#atZero", "RestartWatch#say"))
                .because("the move and the sentence are one event, and the move is the half that can hurt")
                .check(classes);
        classes()
                .that(isListed(PROXY))
                .should(callFrom("start", "RestartWatch#whenZeroReached", "ProxyPlugin#atZero"))
                .andShould(callInOrder("atZero", "Evacuation#check", "ProxySwap#check"))
                .because("parking the network first would move everybody twice")
                .check(classes);
        final Set<String> swept = classes.get(PROXY).getCodeUnits().stream()
                .filter(unit -> unit.getName().equals("start"))
                .flatMap(unit -> unit.getMethodReferencesFromSelf().stream())
                .map(reference -> reference.getTargetOwner().getSimpleName() + "#" + reference.getName())
                .collect(Collectors.toSet());
        assertTrue(
                swept.containsAll(Set.of("Evacuation#check", "ProxySwap#check")),
                "the repeating sweeps catch a countdown whose scheduled beats a restart lost: " + swept);
    }

    /** steward counts the players the instant the counter reaches zero. */
    @Test
    void theCountsHurryFromTheCountdownOn() {
        classes()
                .that(isListed(PROXY))
                .should(callFrom("start", "OnlineWriter#whenHurrying", "RestartWatch#isCountingDown"))
                .check(classes);
    }

    /** A title reaches a player with chat closed, so the countdown's chat line draws its number too. */
    @Test
    void theCountdownLineDrawsATitle() {
        classes()
                .that(isListed(WATCH))
                .should(callInOrder("say", "RestartWatch#countdown", "RestartWatch#title", "RestartWatch#now"))
                .because("the first title in say lies between the countdown's line and the outage's")
                .check(classes);
    }

    /** The voice chat hint is a side note: the first line of a countdown says it, and a new countdown again. */
    @Test
    void theVoiceHintIsSaidOncePerCountdown() {
        classes()
                .that(isListed(WATCH))
                .should(callInOrder("say", "RestartWatch#saidVoice", "Restart#voice"))
                .andShould(callFrom("check", "RestartWatch#saidVoice"))
                .check(classes);
    }

    /** A transfer closes the connection, so the standby speaks before it sends players home, and nowhere else. */
    @Test
    void theStandbySpeaksBeforeItTransfers() {
        classes()
                .that(isListed(STANDBY_RETURN))
                .should(callInOrder("announceThenSendHome", "Homecoming#say", "StandbyReturn#sendHome"))
                .andShould(callOnlyFrom("announceThenSendHome", "Homecoming#say"))
                .andShould(callOnceFrom("announceThenSendHome", "Homecoming#say"))
                .check(classes);
    }
}

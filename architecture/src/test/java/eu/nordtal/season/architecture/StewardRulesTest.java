package eu.nordtal.season.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static eu.nordtal.season.architecture.Wiring.callFrom;
import static eu.nordtal.season.architecture.Wiring.callInOrder;
import static eu.nordtal.season.architecture.Wiring.isListed;
import static eu.nordtal.season.architecture.Wiring.isOrIsNestedIn;
import static eu.nordtal.season.architecture.Wiring.neverCallFrom;
import static eu.nordtal.season.architecture.Wiring.reaches;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The order of a run's steps, and which thread writes a heartbeat, as steward and steward-agent are wired. */
class StewardRulesTest {

    private static final String SERVE = "eu.nordtal.season.stewardagent.run.";
    private static final String FOLLOWS = "eu.nordtal.season.internalapi.sse.Follows";
    private static final String AGENT = "eu.nordtal.season.stewardagent.StewardAgent";
    private static final String AGENT_PACKAGE = "eu.nordtal.season.stewardagent..";

    /** Standbys up, then the warning, then the wait for the players, then the stop: no other order. */
    private static final String[] CHOREOGRAPHY = {
        "Choreography#open", "Runner#countDown", "Choreography#waitUntilEmpty", "UpdateRun#stop"
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Codebase.classes();
    }

    /** Warning every player for a plan that turns out to be "everything is current" is the run's worst habit. */
    @Test
    void nothingIsCountedDownBeforeThePlanIsResolvedAndFoundToBeWork() {
        classes()
                .that(isListed(SERVE + "Kinds"))
                .should(callInOrder("update", "Runs#resolve", "UpdateReport#isWork", "Plan#of"))
                .check(classes);
        classes()
                .that(isListed(SERVE + "Runner"))
                .should(callInOrder("run", "Runner#plan", "Run#carryOut"))
                .check(classes);
        classes()
                .that(isListed(SERVE + "Run"))
                .should(callInOrder("carryOut", "Runner#countDown", "Runner#cancelled", "UpdateRun#stop"))
                .because("a cancelled countdown leaves the run before anything is stopped")
                .check(classes);
    }

    /** A standby that will not come up is found by a run that has warned nobody yet. */
    @Test
    void everyRunThatStopsServersRunsTheSameChoreography() {
        classes()
                .that(isListed(SERVE + "Run"))
                .should(callInOrder("carryOut", CHOREOGRAPHY))
                .andShould(callFrom("carryOut", "Choreography#close"))
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("eu.nordtal.season..")
                .and(DescribedPredicate.not(isOrIsNestedIn(SERVE + "Run")))
                .should()
                .callMethodWhere(reaches(SERVE + "UpdateRun", "stop"))
                .orShould()
                .callMethodWhere(reaches(SERVE + "Runner", "countDown"))
                .because("every kind of run is a plan carried out by Run, so no kind stops a server its own way")
                .check(classes);
    }

    /** Failing is there to warn about archives that exist; failing instead of writing them is the loss. */
    @Test
    void aRunCarriesOutItsPayloadBeforeItDecidesTheRunFailed() {
        classes()
                .that(isListed(SERVE + "Run"))
                .should(callInOrder("carryOut", "UpdateRun#stop", "Payload#carryOut", "Run#finish"))
                .andShould(callFrom("finish", "Runner#settle"))
                .check(classes);
    }

    /** One follow nobody reads must hold no other's heartbeat, so each beats as a task of the process's scheduler. */
    @Test
    void eachFollowBeatsAsATaskOfItsOwnAndAsksWhetherItIsStillWanted() {
        classes()
                .that(isListed(FOLLOWS))
                .should(neverCallFrom("serve", "SseClient#sendComment"))
                .andShould(callFrom("serve", "Scheduler#every", "Follows#beat"))
                .andShould(callInOrder("beat", "Follows#stillWanted", "SseClient#sendComment"))
                .because("the scheduler never runs one task twice at once, so a stuck write skips its next beats")
                .check(classes);
    }

    /** steward holds no socket of the daemon's and none of the agent's classes: the typed client is its one way. */
    @Test
    void onlyTheAgentReachesTheDockerSocket() {
        noClasses()
                .that()
                .resideOutsideOfPackage(AGENT_PACKAGE)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.net.UnixDomainSocketAddress")
                .because("the daemon's socket is mounted into steward-agent alone")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("eu.nordtal.season.steward..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(AGENT_PACKAGE)
                .because("steward reaches Docker and the volumes through AgentClient, so the agent is the one door")
                .check(classes);
    }

    /** Recreate never fetches, deploy does, and a standby fetches only an image this host does not have. */
    @Test
    void recreatingNeverPullsAndDeployingDoes() {
        classes()
                .that(isListed(AGENT))
                .should(neverCallFrom("recreate", "Compose#pull"))
                .andShould(callFrom("recreate", "Compose#hasLocalImage", "Compose#recreate"))
                .andShould(callFrom("deploy", "Compose#pull"))
                .andShould(callFrom("standby", "Compose#hasLocalImage", "Compose#pull", "Compose#recreate"))
                .check(classes);
    }
}

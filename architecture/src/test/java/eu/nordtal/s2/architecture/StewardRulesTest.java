package eu.nordtal.s2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static eu.nordtal.s2.architecture.Wiring.callFrom;
import static eu.nordtal.s2.architecture.Wiring.callInOrder;
import static eu.nordtal.s2.architecture.Wiring.isListed;
import static eu.nordtal.s2.architecture.Wiring.neverCallFrom;

import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The order of a run's steps, and which thread writes a heartbeat, as Steward's processes are wired. */
class StewardRulesTest {

    private static final String SERVE = "eu.nordtal.s2.steward.serve.";
    private static final String LOG_FOLLOWS = "eu.nordtal.s2.steward.api.LogFollows";
    private static final String DEPLOYER = "eu.nordtal.s2.steward.deployer.StewardDeployer";

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
                .that(isListed(SERVE + "Runner"))
                .should(callInOrder(
                        "update", "UpdateSequence#prepareUpdate", "UpdateReport#isWork", "UpdateSequence#run"))
                .check(classes);
        classes()
                .that(isListed(SERVE + "UpdateSequence"))
                .should(callFrom("prepareUpdate", "Runs#resolve"))
                .andShould(callInOrder("run", "Runner#countDown", "Runner#cancelled", "UpdateRun#stop"))
                .because("a cancelled countdown leaves the run before anything is stopped")
                .check(classes);
    }

    /** A standby that will not come up is found by a run that has warned nobody yet. */
    @Test
    void everyRunThatStopsServersRunsTheSameChoreography() {
        classes()
                .that(isListed(SERVE + "UpdateSequence"))
                .should(callInOrder(
                        "run", "UpdateSequence#openUpdateStandbys", CHOREOGRAPHY[1], CHOREOGRAPHY[2], CHOREOGRAPHY[3]))
                .andShould(callFrom("openUpdateStandbys", CHOREOGRAPHY[0]))
                .andShould(callFrom("run", "Choreography#close"))
                .check(classes);
        classes()
                .that(isListed(SERVE + "BackupSequence", SERVE + "RestartSequence"))
                .should(callInOrder("runUnderLock", CHOREOGRAPHY))
                .andShould(callFrom("runUnderLock", "Choreography#close"))
                .check(classes);
    }

    /** Failing is there to warn about archives that exist; failing instead of writing them is the loss. */
    @Test
    void aBackupSavesTheVolumesBeforeItDecidesTheRunFailed() {
        classes()
                .that(isListed(SERVE + "BackupSequence"))
                .should(callInOrder("runUnderLock", "UpdateRun#save", "Runner#settle"))
                .check(classes);
    }

    /** One follow nobody reads must not hold the one timer thread every follow shares. */
    @Test
    void theHeartbeatTimerHandsTheWriteOnAndSkipsATickStillOnItsWay() {
        classes()
                .that(isListed(LOG_FOLLOWS))
                .should(neverCallFrom("serve", "SseClient#sendComment"))
                .andShould(callFrom("serve", "ScheduledExecutorService#scheduleWithFixedDelay", "LogFollows#beat"))
                .andShould(callInOrder(
                        "beat",
                        "AtomicBoolean#compareAndSet",
                        "ExecutorService#submit",
                        "SseClient#sendComment",
                        "AtomicBoolean#set"))
                .andShould(callFrom("<init>", "Executors#newVirtualThreadPerTaskExecutor"))
                .andShould(callFrom("<init>", "Executors#newSingleThreadScheduledExecutor"))
                .check(classes);
    }

    /** Recreate is the button that must not fetch; deploy is the one that does. */
    @Test
    void recreatingNeverPullsAndDeployingDoes() {
        classes()
                .that(isListed(DEPLOYER))
                .should(neverCallFrom("recreate", "Compose#pull"))
                .andShould(callFrom("recreate", "Compose#hasLocalImage", "Compose#recreate"))
                .andShould(callFrom("deploy", "Compose#pull"))
                .check(classes);
    }
}

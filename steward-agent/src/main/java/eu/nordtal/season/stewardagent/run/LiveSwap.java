package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.settings.PackState;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * A live swap: counts down to the players on the SMP, then has smp send them the pack the proxy offers.
 * No service stops. Its one step after the countdown is smp's install of the pack.
 */
@Slf4j
final class LiveSwap {

    /** The artefact the install step names, as the pack's version jump. */
    static final String ARTEFACT = "resource-pack";

    /** How long smp may take until every player there loaded the pack: its hold of each, and some to spare. */
    static final Duration PATIENCE = Duration.ofMinutes(3);

    private LiveSwap() {}

    /** Carries the swap out within the planner, since nothing stops, and answers with how it ended. */
    static Kinds.Planned carryOut(
            final Runner runner, final UpdateRequest request, final Consumer<RunStage> progress, final Steps record) {
        final String smp = SeasonMode.SMP.server();
        final PackState pack = PackState.read(SettingStore.using(runner.database.dataSource()));
        if (!pack.present()) {
            // No build ever made a pack, so there is nothing to send.
            record.ended(RunStep.Kind.SCOPE, smp, RunStep.Outcome.SKIPPED, RunStep.Reason.UNKNOWN);
            return Kinds.Planned.outcome(Outcome.at(RunStage.NOTHING_TO_DO));
        }
        if (runner.directory.isHeld(smp)) {
            record.ended(RunStep.Kind.SCOPE, smp, RunStep.Outcome.SKIPPED, RunStep.Reason.HELD);
            return Kinds.Planned.outcome(Outcome.at(RunStage.NOTHING_TO_DO));
        }
        final RuntimeResult runtime = runner.containers.runtime();
        if (!ModeServers.isRunning(runtime, smp)) {
            // Players on a stopped or resting SMP get the pack at their next connect to the waiting room.
            record.ended(RunStep.Kind.SCOPE, smp, RunStep.Outcome.SKIPPED, RunStep.Reason.NO_CONTAINER);
            return Kinds.Planned.outcome(Outcome.at(RunStage.NOTHING_TO_DO));
        }
        if (!runner.countDownInPlace(request.id(), UpdateDirectory.UPDATE_COUNTDOWN, progress, record)) {
            return Kinds.Planned.outcome(Runner.cancelled());
        }
        progress.accept(RunStage.INSTALLING);
        final String url = Objects.requireNonNull(pack.url());
        final String sha1 = Objects.requireNonNull(pack.sha1());
        final Steps.Step install = record.begin(RunStep.Kind.INSTALL, smp).change(ARTEFACT, null, sha1, null);
        final Optional<Request<SmpRequest>> answer = Inbox.over(runner.database.dataSource(), SmpRequest.TABLE)
                .ask(new SmpRequest.SendPack(url, sha1), Actor.STEWARD, PATIENCE, runner.waiting);
        if (answer.isEmpty()) {
            log.warn("live swap unanswered by smp: request={} sha1={}", request.id(), sha1);
            install.failed(RunStep.Reason.NOT_HEALTHY);
            return Kinds.Planned.outcome(Outcome.at(RunStage.FAILED));
        }
        if (answer.get().status() != InboxStatus.DONE) {
            log.warn(
                    "live swap refused by smp: request={} status={}",
                    request.id(),
                    answer.get().status());
            install.failed(RunStep.Reason.REFUSED);
            return Kinds.Planned.outcome(Outcome.at(RunStage.FAILED));
        }
        install.done();
        log.info("live swap done: request={} sha1={}", request.id(), sha1);
        return Kinds.Planned.outcome(Outcome.at(RunStage.DONE));
    }
}

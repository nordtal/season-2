package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReport.Note;
import eu.nordtal.season.database.update.UpdateReport.Step;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.stewardagent.apply.ApplyResult;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.plan.Change;
import eu.nordtal.season.stewardagent.plan.PlanReport;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import eu.nordtal.season.stewardagent.plugin.PluginDirectory;
import eu.nordtal.season.stewardagent.schema.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Installs what has nothing installed before steward-agent reports ready, and writes the install as a run.
 *
 * A newer version of an installed jar stops a server, so it is an update run; nothing here stops anything.
 */
@Slf4j
public final class Bootstrap {

    private Bootstrap() {}

    public static void installMissing(
            final RunSpec config,
            final Database database,
            final Supplier<AgentWire.Topology> topology,
            final Clock clock,
            final Consumer<List<String>> handOver) {
        final Instant began = clock.instant();
        final SettingStore settings = SettingStore.using(database.dataSource());
        final PluginDirectory plugins = PluginDirectory.using(database.dataSource());
        Optional<UpdateReport> report;
        List<String> services = List.of();
        try {
            final AgentWire.Topology read = topology.get();
            final UpdatePlan missing =
                    Runs.resolve(config, read, plugins, settings).onlyMissing();
            services = services(missing);
            report = report(missing, handingOver(plan -> Runs.apply(config, read, plan, settings, plugins), handOver));
        } catch (final RuntimeException failure) {
            log.error("Start-up install: nothing could be resolved, so nothing was installed", failure);
            report = Optional.of(unexpected(UpdateReport.at(UpdateReport.Stage.FAILED), failure));
        }
        if (report.isEmpty()) {
            log.info("Start-up install: every volume already holds what belongs in it, so there is no run to write");
            return;
        }
        final UpdateReport written = report.get();
        try {
            final UpdateRequest run = Schema.updates(database)
                    .record(
                            new StewardRequest.Install(services),
                            Actor.STEWARD,
                            written.stage() == UpdateReport.Stage.FAILED ? UpdateStatus.FAILED : UpdateStatus.DONE,
                            UpdateReports.toJson(written),
                            Duration.between(began, clock.instant()));
            log.info("Start-up install written as run {}, {}", run.id(), written.stage());
        } catch (final RuntimeException failure) {
            log.error("Start-up install ended {}, and its run could not be written", written.stage(), failure);
        }
    }

    /**
     * Installs a plan, then gives the services' users ownership of their volumes, also after a failure.
     */
    static Function<UpdatePlan, ApplyResult> handingOver(
            final Function<UpdatePlan, ApplyResult> apply, final Consumer<List<String>> handOver) {
        return plan -> {
            try {
                return apply.apply(plan);
            } finally {
                handOver.accept(services(plan));
            }
        };
    }

    private static List<String> services(final UpdatePlan plan) {
        return plan.changes().stream()
                .map(Change::service)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * What the start-up install amounts to, or empty when nothing was missing and everything could be checked.
     *
     * @param missing the plan reduced to what has nothing installed, and what could not be checked
     * @param apply installs a plan; called only when something is missing
     */
    static Optional<UpdateReport> report(final UpdatePlan missing, final Function<UpdatePlan, ApplyResult> apply) {
        if (!missing.hasMissing() && !missing.hasFailures()) {
            return Optional.empty();
        }
        final UpdateReport planned = PlanReport.of(missing);
        if (!missing.hasMissing()) {
            return Optional.of(planned.withStage(UpdateReport.Stage.FAILED));
        }
        final ApplyResult result;
        try {
            result = apply.apply(missing);
        } catch (final RuntimeException failure) {
            log.error("Start-up install: the install failed part way through", failure);
            return Optional.of(unexpected(planned.withStage(UpdateReport.Stage.FAILED), failure));
        }
        // A line the plan failed keeps its reason. Only a line that waits for its files is overwritten.
        final List<String> waiting = planned.services().stream()
                .filter(line -> line.state() == UpdateReport.State.PLANNED)
                .map(UpdateReport.ServiceLine::service)
                .toList();
        final UpdateReport installed = Installed.into(planned, result, waiting);
        final boolean failed = missing.hasFailures()
                || result.hasFailures()
                || installed.services().stream().anyMatch(line -> line.state() == UpdateReport.State.FAILED)
                || installed.notes().stream().anyMatch(note -> note.outcome() == UpdateReport.Outcome.FAILED);
        return Optional.of(installed.withStage(failed ? UpdateReport.Stage.FAILED : UpdateReport.Stage.DONE));
    }

    private static UpdateReport unexpected(final UpdateReport report, final RuntimeException failure) {
        return report.withNote(Note.failed(Step.RUN, TEXTS.report().failedUnexpectedly(failure.toString())));
    }
}

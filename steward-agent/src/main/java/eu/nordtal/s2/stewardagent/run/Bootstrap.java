package eu.nordtal.s2.stewardagent.run;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.stewardagent.apply.ApplyResult;
import eu.nordtal.s2.stewardagent.config.RunSpec;
import eu.nordtal.s2.stewardagent.plan.Change;
import eu.nordtal.s2.stewardagent.plan.UpdatePlan;
import eu.nordtal.s2.stewardagent.plugin.PluginDirectory;
import lombok.extern.slf4j.Slf4j;

/**
 * Installs what has nothing installed, once, before steward-agent reports itself ready.
 *
 * A newer version of an installed jar stops a server, so it is a run; a failure here only logs.
 */
@Slf4j
public final class Bootstrap {

    private Bootstrap() {}

    /** Resolves what is missing and installs it; called before the run loop claims anything, so nothing races it. */
    public static void installMissing(final RunSpec config, final Database database) {
        final SettingStore settings = SettingStore.using(database.dataSource());
        final UpdatePlan missing;
        try {
            missing = Runs.resolve(config, PluginDirectory.using(database.dataSource()), settings)
                    .onlyMissing();
        } catch (final RuntimeException failure) {
            log.error(
                    "Bootstrap: nothing could be resolved, so no missing file was installed. Any server whose"
                            + " plugins folder is empty will refuse to start and say so.",
                    failure);
            return;
        }
        if (!missing.hasMissing()) {
            if (missing.hasFailures()) {
                log.warn(
                        "Bootstrap: nothing is missing among the artefacts that could be checked, but {} could not"
                                + " be checked at all. That is not the same as a full set of volumes. Nothing was"
                                + " installed:\n{}",
                        missing.withStatus(Change.Status.UNRESOLVED).size(),
                        Report.render(missing));
            } else {
                log.info("Bootstrap: every volume already holds a jar for everything that belongs in it, so nothing"
                        + " was installed. This is the normal case on a restart.");
            }
            return;
        }
        log.info(
                "Bootstrap: {} artefact(s) have nothing installed at all. Installing those, and only those, before"
                        + " this container reports ready.",
                missing.withStatus(Change.Status.MISSING).size());
        final ApplyResult result;
        try {
            result = Runs.apply(config, missing, settings);
        } catch (final RuntimeException failure) {
            log.error(
                    "Bootstrap: the install failed part way through. Some volumes may still be empty, and a server"
                            + " whose plugins folder is one of them will refuse to start and say so.",
                    failure);
            return;
        }
        if (result.hasFailures()) {
            log.error("Bootstrap finished with failures:\n{}", Report.render(result));
        } else if (result.skippedAnything()) {
            log.warn(
                    "Bootstrap could not install everything, and what it skipped it skipped entirely. A server whose"
                            + " plugins folder is still empty will refuse to start and say so:\n{}",
                    Report.render(result));
        } else {
            log.info("Bootstrap finished:\n{}", Report.render(result));
        }
    }
}

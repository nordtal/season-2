package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.settings.PackState;
import eu.nordtal.season.stewardagent.apply.PackWriter;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * The resource pack of an update, built before its countdown from the release that carries the run out.
 *
 * The players the run sends through the waiting room get the new pack there, and no build follows the run.
 */
@Slf4j
final class UpdatePack {

    private UpdatePack() {}

    /**
     * Builds the pack and writes an install step when the pack the proxy offers changed.
     *
     * @return the pack the proxy offered before, when the build changed it
     */
    static Optional<PackState> build(final Runner runner, final Steps record) {
        final SettingStore settings = SettingStore.using(runner.database.dataSource());
        final PackState before = active(settings);
        runner.pack.build();
        final PackState after = active(settings);
        if (after.sha1() == null || after.sha1().equals(before.sha1())) {
            return Optional.empty();
        }
        log.info("pack built for the run: from={} to={}", before.sha1(), after.sha1());
        record.begin(RunStep.Kind.INSTALL, null)
                .change(Topology.RESOURCE_PACK, before.sha1(), after.sha1(), null)
                .done();
        return Optional.of(before);
    }

    /** Offers {@code before} again after a cancelled run, which installed nothing of the release of the new pack. */
    static void restore(final Runner runner, final UpdateRequest request, final PackState before) {
        if (before.url() == null || before.sha1() == null) {
            log.info("run cancelled, no previous pack, new pack kept: request={}", request.id());
            return;
        }
        try {
            PackWriter.write(SettingStore.using(runner.database.dataSource()), before.url(), before.sha1());
            log.info("run cancelled, previous pack offered: request={} sha1={}", request.id(), before.sha1());
        } catch (final RuntimeException unwritten) {
            log.warn(
                    "run cancelled, previous pack not offered: request={} cause={}",
                    request.id(),
                    unwritten.getMessage());
        }
    }

    /** The pack the proxy offers now, an absent one when it cannot be read. */
    private static PackState active(final SettingStore settings) {
        try {
            return PackState.read(settings);
        } catch (final RuntimeException unread) {
            log.warn("active pack unreadable: cause={}", unread.getMessage());
            return new PackState(null, null);
        }
    }
}

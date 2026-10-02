package eu.nordtal.s2.stewardagent.apply;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.stewardagent.plan.Installation;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Makes each standby's {@code plugins/} an exact copy of the service it stands in for, configs included.
 *
 * Copied rather than resolved, so it matches what was just installed; a standby that is not mounted is skipped.
 */
@Slf4j
public final class Standbys {

    private Standbys() {}

    /**
     * Mirrors {@code plugins/} onto the standby of every named service that has one.
     *
     * @param volumesRoot where the services' volumes are mounted in this container
     * @param services the services this run touched; anything without a standby is ignored
     * @param topology which service has which standby
     * @return one row per standby that is mounted, under the standby's own compose service name
     */
    public static List<ApplyResult.Outcome> fill(
            final Path volumesRoot, final Collection<String> services, final AgentWire.Topology topology) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();
        for (final AgentWire.Service each : topology.services()) {
            final String service = each.standbyOf();
            if (service == null || !services.contains(service)) {
                continue;
            }
            final String standby = each.name();
            final Path target = volumesRoot.resolve(standby).resolve(Installation.PLUGINS);
            if (!Files.isDirectory(volumesRoot.resolve(standby))) {
                log.info(
                        "{} is not mounted here, so nothing was copied into it. That is a"
                                + " deployment without standby services; a run that needs one will find it"
                                + " empty and the container will refuse to start rather than run nothing.",
                        standby);
                continue;
            }
            final Path source = volumesRoot.resolve(service).resolve(Installation.PLUGINS);
            if (!Files.isDirectory(source)) {
                outcomes.add(new ApplyResult.Outcome(
                        standby,
                        Installation.PLUGINS,
                        ApplyResult.Status.FAILED,
                        service + " has no plugins/ directory at " + source + ", so there was"
                                + " nothing to copy. The standby is empty and will refuse to"
                                + " start."));
                continue;
            }
            outcomes.add(mirror(source, target, standby));
        }
        return List.copyOf(outcomes);
    }

    private static ApplyResult.Outcome mirror(final Path source, final Path target, final String standby) {
        final Tally tally = new Tally();
        try {
            Files.createDirectories(target);
            copyInto(source, target, tally);
        } catch (final IOException failed) {
            log.error("Could not fill {}'s plugins/ from {}: {}", standby, source, failed.getMessage());
            return new ApplyResult.Outcome(
                    standby,
                    Installation.PLUGINS,
                    ApplyResult.Status.FAILED,
                    "could not be filled from " + source + ": " + failed.getMessage()
                            + ". Started for a swap, this service would come up on whatever is"
                            + " still lying in it.");
        }
        if (tally.copied == 0 && tally.removed == 0) {
            return new ApplyResult.Outcome(
                    standby,
                    Installation.PLUGINS,
                    ApplyResult.Status.UNCHANGED,
                    "already the same " + tally.same + " file(s)");
        }
        return new ApplyResult.Outcome(
                standby,
                Installation.PLUGINS,
                ApplyResult.Status.DONE,
                tally.copied + " file(s) copied"
                        + (tally.removed == 0 ? "" : ", " + tally.removed + " removed")
                        + ", " + tally.same + " already the same");
    }

    /**
     * One directory, recursively, made equal to another.
     *
     * Files are compared by content, since timestamps collide and a changed file can keep its size.
     */
    private static void copyInto(final Path source, final Path target, final Tally tally) throws IOException {
        final Set<String> wanted = new LinkedHashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(source)) {
            for (final Path entry : entries) {
                final String name = entry.getFileName().toString();
                // Applier's half-written downloads, left by a run that died mid-phase.
                if (Applier.STAGING.equals(name)) {
                    continue;
                }
                wanted.add(name);
                final Path destination = target.resolve(name);
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                    copyInto(entry, destination, tally);
                } else if (isDifferent(entry, destination)) {
                    Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
                    tally.copied++;
                } else {
                    tally.same++;
                }
            }
        }
        try (DirectoryStream<Path> existing = Files.newDirectoryStream(target)) {
            for (final Path entry : existing) {
                if (wanted.contains(entry.getFileName().toString())) {
                    continue;
                }
                tally.removed += deleteRecursively(entry);
            }
        }
    }

    /** Whether the two files differ in content. */
    private static boolean isDifferent(final Path source, final Path destination) throws IOException {
        return !Files.isRegularFile(destination) || Files.mismatch(source, destination) != -1L;
    }

    /** Deletes a file or tree and returns how many files were removed. */
    private static int deleteRecursively(final Path entry) throws IOException {
        int removed = 0;
        if (Files.isDirectory(entry)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(entry)) {
                for (final Path child : children) {
                    removed += deleteRecursively(child);
                }
            }
            Files.delete(entry);
            return removed;
        }
        Files.delete(entry);
        return removed + 1;
    }

    /** What one mirror did, counted rather than listed. */
    private static final class Tally {
        private int copied;
        private int removed;
        private int same;
    }
}

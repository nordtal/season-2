package eu.nordtal.s2.steward.worker.apply;

import eu.nordtal.s2.steward.worker.plan.Installation;
import eu.nordtal.s2.steward.worker.plan.Topology;
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
 * Makes each standby's {@code plugins/} a copy of the service it stands in for (season-2-ops/119).
 *
 * <h2>Why this is a copy and not a second install</h2>
 * A standby exists to carry the network for the seconds its model is being restarted, and it has to
 * come up on <em>the jar that was just installed</em> - not on "the newest release", which is the
 * same thing only until somebody publishes one between the two resolves. Resolving a standby
 * separately would also double every row in every report and every call to GitHub, for an answer
 * that has to be identical anyway. So nothing is resolved here: the live service's
 * {@code plugins/} is mirrored across after {@link Applier} has finished with it, and the standby
 * is by construction what the live service is about to be.
 *
 * <h2>The whole directory, not just the jars</h2>
 * {@code plugins/} holds the jars <em>and</em> every configuration the plugin reads - including
 * {@code plugins/proxy/pack.yml}, which is the resource pack's URL and sha1. A standby with its own
 * pack.yml would hand a transferred player a different pack to download, and a standby with
 * <em>no</em> pack.yml is a proxy that refuses to start. Mirroring the directory is what makes
 * "identical to its model" true of the settings as well as of the code.
 *
 * <p><b>Extra files in the standby are deleted.</b> This is a copy, not a merge: a jar left behind
 * there is a plugin the replacement runs and the original does not.</p>
 *
 * <h2>A deployment without standbys is not an error</h2>
 * When the standby's directory is not mounted into this container at all, nothing is written and no
 * row appears in the report - a stack whose compose.yml predates this feature must not grow a
 * skipped line in every run. That silence is affordable for one reason: the failure it could hide
 * is caught loudly one step later, because a standby started with an empty {@code plugins/} is
 * refused by the Minecraft entrypoint, which stops the container and names the folder.
 */
@Slf4j
public final class Standbys {

    private Standbys() {}

    /**
     * Mirrors {@code plugins/} onto the standby of every named service that has one.
     *
     * @param volumesRoot where the services' volumes are mounted in this container
     * @param services    the services this run touched; anything without a standby is ignored
     * @return one row per standby that is mounted, under the standby's own compose service name
     */
    public static List<ApplyResult.Outcome> fill(final Path volumesRoot, final Collection<String> services) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();
        for (final String service : Topology.SERVICES_WITH_STANDBY) {
            if (!services.contains(service)) {
                continue;
            }
            final String standby = Topology.standbyOf(service);
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
     * <p>A file is copied when its bytes differ, and that is the comparison rather than a cheaper
     * one for a measured reason. Timestamps do not work: on this host two operations in the same
     * clock tick produce the identical modification time, so a copy of a file that has changed is
     * indistinguishable from one that has not. Size does not work either, and the file that proves
     * it is the one that matters most here - {@code pack.yml}, whose sha1 is forty hex characters
     * whatever the pack is. Reading a plugins folder twice is a few megabytes once per update.</p>
     */
    private static void copyInto(final Path source, final Path target, final Tally tally) throws IOException {
        final Set<String> wanted = new LinkedHashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(source)) {
            for (final Path entry : entries) {
                final String name = entry.getFileName().toString();
                // Applier's own half-written downloads. A run that died between its two phases
                // leaves them, and copying them would put a jar nobody verified into a folder a
                // server reads.
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

    /** Whether the two files differ in content. {@code Files.mismatch} answers -1 when they do not. */
    private static boolean isDifferent(final Path source, final Path destination) throws IOException {
        return !Files.isRegularFile(destination) || Files.mismatch(source, destination) != -1L;
    }

    /** @return how many files were removed. */
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

    /** What one mirror did, counted rather than listed: a plugins folder is dozens of files. */
    private static final class Tally {
        private int copied;
        private int removed;
        private int same;
    }
}

package eu.nordtal.season.database.update;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.messages.MessageRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The lines a step's facts read as, one renderer for Steward and the terminal.
 *
 * A step's artefacts read in one order: those with a version first, then those without, each by name.
 */
public final class RunLines {

    private RunLines() {}

    public static List<MessageRef> of(final RunStep.Row step) {
        final List<MessageRef> lines = new ArrayList<>();
        if (step.reason() != null) {
            lines.add(TEXTS.run().reason(step.reason()));
        }
        if (step.exitCode() != null) {
            lines.add(TEXTS.run().exitCode(step.exitCode()));
        }
        for (final RunStep.Change change : step.changes().stream()
                .sorted(Comparator.comparing((RunStep.Change change) -> change.from() == null && change.to() == null)
                        .thenComparing(RunStep.Change::artefact))
                .toList()) {
            lines.add(change(change));
        }
        for (final RunStep.Move move : step.players()) {
            lines.add(TEXTS.run().players(move.moved(), move.from(), move.to(), move.returned()));
        }
        if (!step.imagesRemoved().isEmpty()) {
            final long freed = step.imagesRemoved().stream()
                    .mapToLong(RunStep.ImageRemoved::bytes)
                    .sum();
            lines.add(TEXTS.run().imagesRemoved(step.imagesRemoved().size(), size(freed)));
        }
        if (step.cacheFreed() != null) {
            lines.add(TEXTS.run().cacheFreed(size(step.cacheFreed())));
        }
        if (step.room() != null) {
            lines.add(TEXTS.run()
                    .room(size(step.room().needed()), size(step.room().free())));
        }
        if (step.offsite() != null) {
            lines.add(TEXTS.run()
                    .offsite(step.offsite().snapshot(), size(step.offsite().bytesAdded())));
        }
        for (final RunStep.Archive archive : step.saved()) {
            lines.add(TEXTS.run()
                    .archiveSaved(
                            archive.file(),
                            size(archive.bytes()),
                            Duration.ofMillis(archive.tookMillis()),
                            archive.stopVerified()));
        }
        for (final RunStep.Archive archive : step.pruned()) {
            if (archive.rule() != null) {
                lines.add(TEXTS.run().archivePruned(archive.file(), archive.rule()));
            }
        }
        return List.copyOf(lines);
    }

    /** A fingerprint, as the resource pack reports its SHA-1. */
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{32,64}", Pattern.CASE_INSENSITIVE);

    /** Returns a change as its versions, else as what happened to its image, else as its name alone. */
    public static MessageRef change(final RunStep.Change change) {
        if (change.from() != null && change.to() != null) {
            return TEXTS.run().moved(change.artefact(), shown(change.from()), change.to());
        }
        if (change.to() != null) {
            return TEXTS.run().version(change.artefact(), change.to());
        }
        if (change.from() != null) {
            return TEXTS.run().version(change.artefact(), change.from());
        }
        return change.image() != null
                ? TEXTS.run().image(change.artefact(), change.image())
                : TEXTS.run().artefact(change.artefact());
    }

    /** Returns a version as it is, and a fingerprint by its first eight characters. */
    private static String shown(final String version) {
        return FINGERPRINT.matcher(version).matches() ? version.substring(0, 8) : version;
    }

    private static MessageRef size(final long bytes) {
        return ByteSize.of(bytes).message();
    }
}

package eu.nordtal.s2.updater.plan;

import eu.nordtal.s2.updater.apply.ApplyResult;
import eu.nordtal.s2.updater.source.RemoteFile;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An {@link UpdatePlan} as text a person reads before deciding whether to restart a network.
 *
 * <p>Plain text rather than Discord components, because the container's log, the Discord embed and
 * the in-game lines all carry this string verbatim out of {@code update_request.result} and must
 * not drift apart - and because this half is then testable without a bot token.</p>
 *
 * <p>The resolved release tag is printed even when nothing changed: "everything is up to date" and
 * "the release you meant is still a draft, so latest is last week's tag" produce identical rows,
 * and the tag is what tells them apart.</p>
 */
public final class Report {

    private static final String INDENT = "  ";

    /** Wide enough for "not installed", which is the longest status word. */
    private static final int LABEL_WIDTH = 15;

    private Report() {
    }

    public static @NotNull String render(final @NotNull UpdatePlan plan) {
        final StringBuilder out = new StringBuilder();

        out.append("nordtal season 2 - update check at ").append(plan.resolvedAt()).append('\n');
        if (plan.seasonTag() != null) {
            out.append("season release resolved to ").append(plan.seasonTag());
            if (plan.seasonPrerelease()) {
                out.append("  <- A PRE-RELEASE. Pinned by tag; /releases/latest never returns one.");
            }
            out.append('\n');
        }
        out.append('\n');

        // One outage, one explanation: a GitHub failure repeats the same ~450-character sentence on
        // every season row, which overruns a Discord embed's 4 000 characters and drops the summary
        // off the end. A repeated reason is printed once at the bottom and referenced by number.
        final Map<String, Integer> footnotes = footnotesOf(plan);

        // Grouped as the network is shaped, proxy first, with everything outside a Minecraft volume
        // collected at the end rather than filed under a server it does not run on.
        final Map<String, List<Change>> grouped = new LinkedHashMap<>();
        for (final Change change : plan.changes()) {
            grouped.computeIfAbsent(change.service() == null ? "(no volume)" : change.service(),
                    key -> new ArrayList<>()).add(change);
        }

        final int width = grouped.values().stream()
                .flatMap(List::stream)
                .mapToInt(change -> change.artifact().length())
                .max()
                .orElse(0);

        grouped.forEach((service, changes) -> {
            out.append(service).append('\n');

            // A service that is not mounted would otherwise repeat one sentence on every row it has.
            final String shared = sharedNote(changes);
            if (shared != null) {
                out.append(INDENT).append(noteText(shared, footnotes)).append('\n');
            }

            for (final Change change : changes) {
                final String detail = detail(change);
                out.append(INDENT)
                        .append(pad(change.artifact(), width))
                        .append("  ")
                        // Not padded when nothing follows: trailing spaces survive a copy into an embed.
                        .append(detail.isEmpty() ? label(change) : pad(label(change), LABEL_WIDTH))
                        .append(detail)
                        .append('\n');
                if (change.note() != null && !change.note().equals(shared)) {
                    out.append(INDENT).append(INDENT).append(pad("", width))
                            .append(noteText(change.note(), footnotes)).append('\n');
                }
            }
            out.append('\n');
        });

        if (!plan.unclaimed().isEmpty()) {
            out.append("jars nothing accounts for - left alone, never deleted:\n");
            for (final UpdatePlan.Unclaimed jar : plan.unclaimed()) {
                out.append(INDENT).append(jar.service()).append('/')
                        .append(Installation.PLUGINS).append('/').append(jar.fileName()).append('\n');
            }
            out.append('\n');
        }

        if (!footnotes.isEmpty()) {
            out.append("why:\n");
            footnotes.forEach((note, number) ->
                    out.append(INDENT).append('[').append(number).append("] ").append(note).append('\n'));
            out.append('\n');
        }

        out.append(summary(plan)).append('\n');
        return out.toString();
    }

    /**
     * The notes worth printing once instead of on every row that carries them: only the repeated
     * ones, insertion-ordered so the numbers run down the page.
     */
    private static Map<String, Integer> footnotesOf(final UpdatePlan plan) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final Change change : plan.changes()) {
            if (change.note() != null) {
                counts.merge(change.note(), 1, Integer::sum);
            }
        }
        final Map<String, Integer> numbered = new LinkedHashMap<>();
        counts.forEach((note, count) -> {
            if (count > 1) {
                numbered.put(note, numbered.size() + 1);
            }
        });
        return numbered;
    }

    /** A note in full, or its reference number when it is printed at the bottom. */
    private static String noteText(final String note, final Map<String, Integer> footnotes) {
        final Integer number = footnotes.get(note);
        return number == null ? note : "[" + number + "]";
    }

    /**
     * What a run did, in the same shape as the plan above so the two read as one page. Printed
     * <b>before</b> anything restarts: the whole value of the restart being a separate button is
     * that somebody sees this first.
     */
    public static @NotNull String render(final @NotNull ApplyResult result) {
        final StringBuilder out = new StringBuilder("what was done\n\n");

        final Map<String, List<ApplyResult.Outcome>> grouped = new LinkedHashMap<>();
        for (final ApplyResult.Outcome outcome : result.outcomes()) {
            grouped.computeIfAbsent(outcome.service() == null ? "(no volume)" : outcome.service(),
                    key -> new ArrayList<>()).add(outcome);
        }

        final int width = grouped.values().stream()
                .flatMap(List::stream)
                .mapToInt(outcome -> outcome.artifact().length())
                .max()
                .orElse(0);

        grouped.forEach((service, outcomes) -> {
            out.append(service).append('\n');
            for (final ApplyResult.Outcome outcome : outcomes) {
                final String detail = outcome.detail() == null ? "" : outcome.detail();
                out.append(INDENT)
                        .append(pad(outcome.artifact(), width))
                        .append("  ")
                        .append(detail.isEmpty()
                                ? outcome.status().name().toLowerCase(Locale.ROOT)
                                : pad(outcome.status().name().toLowerCase(Locale.ROOT), LABEL_WIDTH))
                        .append(detail)
                        .append('\n');
            }
            out.append('\n');
        });

        if (result.hasFailures()) {
            out.append("Something failed. DO NOT RESTART on this run: read the FAILED lines first -")
                    .append(" a server that was part-updated is the one state worth catching before")
                    .append(" the network goes down on it.\n");
        } else if (result.changedAnything()) {
            out.append("Everything asked for was done. A restart is what puts it into effect -")
                    .append(" nothing here changed a running server.\n");
        } else if (result.skippedAnything()) {
            // Neither a failure nor a no-op, and it must not read as either.
            out.append("Nothing was installed, and not because everything was current -")
                    .append(" every line above says why it was skipped. Read them before assuming")
                    .append(" the network is up to date.\n");
        } else {
            out.append("Nothing needed doing.\n");
        }
        return out.toString();
    }

    /** The one word in the status column. Uppercase for the two that need a person. */
    private static String label(final Change change) {
        return switch (change.status()) {
            case UP_TO_DATE -> "up to date";
            case OUTDATED -> "OUTDATED";
            case MISSING -> "not installed";
            case UNRESOLVED -> "UNRESOLVED";
            case MOUNT_MISSING -> "unknown";
            // Lower case: nothing here needs a person, it is somebody else's release schedule.
            case UNSUPPORTED -> "no build yet";
        };
    }

    private static String detail(final Change change) {
        final RemoteFile wanted = change.wanted();
        return switch (change.status()) {
            case UP_TO_DATE -> identity(change.installed(), wanted);
            case OUTDATED -> change.installed() + "  ->  " + identity(null, wanted);
            case MISSING -> "->  " + identity(null, wanted);
            case UNRESOLVED -> "";
            case MOUNT_MISSING -> "(newest is " + identity(null, wanted) + ")";
            // The note carries the sentence; the column would only repeat half of it.
            case UNSUPPORTED -> "";
        };
    }

    /** The note every row in a group shares, or {@code null} when they do not all share one. */
    private static String sharedNote(final List<Change> changes) {
        final String first = changes.getFirst().note();
        if (first == null || changes.size() < 2) {
            return null;
        }
        return changes.stream().allMatch(change -> first.equals(change.note())) ? first : null;
    }

    /** What a row is compared on: the filename for a jar, the hash for the pack. */
    private static String identity(final String installed, final RemoteFile wanted) {
        if (wanted == null) {
            return installed == null ? "?" : installed;
        }
        if (wanted.checksum() != null && "sha1".equals(wanted.checksum().algorithm())) {
            // The hash in full: two pack releases can share twelve leading hex characters on the
            // screen and none in the client, and this row exists to show that they differ.
            return wanted.fileName() + " (sha1 " + wanted.checksum().hex() + ")";
        }
        return wanted.fileName();
    }

    private static String summary(final UpdatePlan plan) {
        final long work = plan.changes().stream().filter(change -> change.status().isWork()).count();
        final long failed = plan.changes().stream().filter(change -> change.status().isFailure()).count();

        if (failed > 0 && work > 0) {
            return work + " artefact(s) would be updated, and " + failed
                    + " could not be checked - so this list is not the whole picture.";
        }
        if (failed > 0) {
            return "Nothing to update among the artefacts that could be checked, but " + failed
                    + " could not be checked at all. That is not the same as up to date.";
        }
        if (work > 0) {
            return work + " artefact(s) would be updated.";
        }

        // "Everything is up to date" is only about artefacts that have a build for this Minecraft
        // version; saying it while one has none would be true and misread.
        final long waiting = plan.changes().stream()
                .filter(change -> change.status() == Change.Status.UNSUPPORTED)
                .count();
        if (waiting > 0) {
            return "Everything with a build for this Minecraft version is up to date. " + waiting
                    + " artefact(s) have none yet - nothing is skipped for them, and the run that"
                    + " follows the day one appears installs it.";
        }
        return "Everything is up to date.";
    }

    private static String pad(final String value, final int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}

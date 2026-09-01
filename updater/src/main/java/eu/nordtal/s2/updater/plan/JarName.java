package eu.nordtal.s2.updater.plan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The project's one rule for reading a jar's filename: <b>everything before the last {@code -} is
 * the identity, everything after it is the version.</b>
 *
 * <pre>
 *   smp-0.2.0.jar                     -&gt; smp                  / 0.2.0
 *   network-control-0.2.0.jar         -&gt; network-control      / 0.2.0
 *   papermc-display-tags-2.0.0.jar    -&gt; papermc-display-tags / 2.0.0
 *   packetevents-spigot-2.13.0.jar    -&gt; packetevents-spigot  / 2.13.0
 *   Chunky-Bukkit-1.5.3.jar           -&gt; Chunky-Bukkit        / 1.5.3
 *   paper-26.2-121.jar                -&gt; paper-26.2           / 121
 * </pre>
 *
 * <h2>Why this rule and not a better one</h2>
 * It is not a good rule. It is {@code ${file%-*.jar}} out of
 * {@code deploy/minecraft/entrypoint.sh}, which is the rule the running deployment has used to
 * decide which jar supersedes which since before this module existed. Inventing a cleverer one
 * here would mean two programs disagreeing about which file is an old copy of which - and the way
 * that disagreement surfaces is Paper loading two versions of the same plugin, which it does
 * without complaining until something calls the wrong one.
 *
 * <p><b>The rule breaks on a qualifier.</b> A hypothetical
 * {@code packetevents-spigot-2.14.0-SNAPSHOT.jar} reads as prefix
 * {@code packetevents-spigot-2.14.0}, which matches nothing on disk, so it would be installed
 * <em>next to</em> the jar it replaces instead of over it. Nothing in this deployment ships a
 * qualifier today (measured against all six jars on 2026-09-01, above), and the guard is that
 * {@link #looksSuperseded} is only ever asked about a name this module resolved from an API in the
 * same run. If a source ever starts publishing qualifiers, this is the class that has to learn
 * about it, and the test that pins these six names is where that shows up.</p>
 */
public final class JarName {

    private static final String SUFFIX = ".jar";

    private JarName() {
    }

    /** Whether a directory entry is a jar at all. Case-sensitive: so is every filesystem we run on. */
    public static boolean isJar(final @NotNull String fileName) {
        return fileName.endsWith(SUFFIX) && fileName.length() > SUFFIX.length();
    }

    /**
     * The part that identifies the artefact: the filename with {@code -<version>.jar} removed.
     *
     * @return {@code null} for a name that is not a jar, carries no {@code -} at all, or has
     *         nothing after the last one. A split only exists when BOTH halves do:
     *         {@code server.jar} has no version and therefore no prefix that means anything, and
     *         {@code smp-.jar} is malformed rather than being version-less {@code smp}. Keeping
     *         the two halves in step matters because callers use the prefix to decide what a file
     *         supersedes - a name that yields a prefix but no version would supersede real jars
     *         while being unidentifiable itself.
     */
    public static @Nullable String prefixOf(final @NotNull String fileName) {
        return versionOf(fileName) == null ? null : splitStem(fileName, true);
    }

    /**
     * The version segment, as text.
     *
     * @return {@code null} under the same conditions as {@link #prefixOf}. Never parsed into
     *         numbers: this module compares filenames for equality and never for order, because
     *         "which of these two versions is newer" is a question the publishing API has already
     *         answered and a question no string comparison answers correctly (2.13.0 vs 2.9.0,
     *         1.5.3 vs 1.5.3+build.2).
     */
    public static @Nullable String versionOf(final @NotNull String fileName) {
        return splitStem(fileName, false);
    }

    /**
     * Both halves of the split, or {@code null} when there is no valid split. One method so the
     * two can never disagree about where the dash is.
     */
    private static @Nullable String splitStem(final @NotNull String fileName, final boolean wantPrefix) {
        if (!isJar(fileName)) {
            return null;
        }
        final String stem = fileName.substring(0, fileName.length() - SUFFIX.length());
        final int dash = stem.lastIndexOf('-');
        if (dash <= 0 || dash == stem.length() - 1) {
            return null;
        }
        return wantPrefix ? stem.substring(0, dash) : stem.substring(dash + 1);
    }

    /**
     * Whether {@code candidate} is an older copy of {@code wanted}: same prefix, different file.
     * <p>
     * This is the predicate that decides what gets deleted when a jar is swapped in (step 3 of
     * docs/updater.md). In step 1 nothing is deleted and it is only used to explain the report.
     * </p>
     */
    public static boolean looksSuperseded(final @NotNull String candidate, final @NotNull String wanted) {
        if (candidate.equals(wanted)) {
            return false;
        }
        final String candidatePrefix = prefixOf(candidate);
        return candidatePrefix != null && candidatePrefix.equals(prefixOf(wanted));
    }
}

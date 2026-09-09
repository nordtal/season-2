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
 * <p>It is not a good rule; it is {@code ${file%-*.jar}} out of
 * {@code deploy/minecraft/entrypoint.sh}, and the two must agree. Two programs disagreeing about
 * which file supersedes which surfaces as Paper loading two versions of the same plugin, silently,
 * until something calls the wrong one.</p>
 *
 * <p><b>The rule breaks on a qualifier</b>: {@code packetevents-spigot-2.14.0-SNAPSHOT.jar} reads
 * as prefix {@code packetevents-spigot-2.14.0}, matches nothing on disk and would be installed
 * beside the jar it replaces. Nothing here ships a qualifier today; if a source starts to, this is
 * the class that has to learn about it.</p>
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
     * @return {@code null} for a name that is not a jar, carries no {@code -}, or has nothing after
     *         the last one. A split only exists when both halves do: a name yielding a prefix but no
     *         version would supersede real jars while being unidentifiable itself.
     */
    public static @Nullable String prefixOf(final @NotNull String fileName) {
        return versionOf(fileName) == null ? null : splitStem(fileName, true);
    }

    /**
     * The version segment, as text.
     *
     * @return {@code null} under the same conditions as {@link #prefixOf}. Never parsed into
     *         numbers: filenames are compared for equality and never for order, because the
     *         publishing API has already answered which version is newer and no string comparison
     *         answers it correctly (2.13.0 vs 2.9.0, 1.5.3 vs 1.5.3+build.2).
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
     * This is what decides which jar is deleted when a new one is installed; a plan that only
     * reports uses it to explain a row and deletes nothing.
     */
    public static boolean looksSuperseded(final @NotNull String candidate, final @NotNull String wanted) {
        if (candidate.equals(wanted)) {
            return false;
        }
        final String candidatePrefix = prefixOf(candidate);
        return candidatePrefix != null && candidatePrefix.equals(prefixOf(wanted));
    }
}

package eu.nordtal.s2.internalapi.agent;

import org.jspecify.annotations.Nullable;

/**
 * The one rule for reading a jar's filename: before the last {@code -} is the identity, after it the version.
 *
 * It must match {@code entrypoint.sh}'s {@code ${file%-*.jar}}, and it breaks on a qualifier such as {@code -SNAPSHOT}.
 */
public final class JarName {

    private static final String SUFFIX = ".jar";

    private JarName() {}

    /** Whether a directory entry is a jar at all, case-sensitively. */
    public static boolean isJar(final String fileName) {
        return fileName.endsWith(SUFFIX) && fileName.length() > SUFFIX.length();
    }

    /**
     * The part that identifies the artefact: the filename with {@code -<version>.jar} removed.
     *
     * @return {@code null} for a name that is not a jar or does not split into two non-empty halves
     */
    public static @Nullable String prefixOf(final String fileName) {
        return versionOf(fileName) == null ? null : splitStem(fileName, true);
    }

    /**
     * The version segment as text, compared for equality and never for order.
     *
     * @return {@code null} under the same conditions as {@link #prefixOf}
     */
    public static @Nullable String versionOf(final String fileName) {
        return splitStem(fileName, false);
    }

    /** Both halves of the split, or {@code null} when there is no valid split. */
    private static @Nullable String splitStem(final String fileName, final boolean wantPrefix) {
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

    /** Whether {@code candidate} is an older copy of {@code wanted}: same prefix, different file. */
    public static boolean looksSuperseded(final String candidate, final String wanted) {
        if (candidate.equals(wanted)) {
            return false;
        }
        final String candidatePrefix = prefixOf(candidate);
        return candidatePrefix != null && candidatePrefix.equals(prefixOf(wanted));
    }
}

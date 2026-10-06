package eu.nordtal.season.internalapi.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The one rule for reading a jar's filename: the version starts at the last {@code -} that a digit follows.
 * A name with no such dash splits at its last {@code -}. It must match {@code entrypoint.sh}'s {@code jar_identity}.
 */
public final class JarName {

    private static final String SUFFIX = ".jar";

    /** Greedy, so the identity ends at the last dash a digit follows and a trailing word is version. */
    private static final Pattern VERSIONED = Pattern.compile("^(?<identity>.+)-(?<version>[0-9].*)$");

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
        final Matcher versioned = VERSIONED.matcher(stem);
        if (versioned.matches()) {
            return versioned.group(wantPrefix ? "identity" : "version");
        }
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

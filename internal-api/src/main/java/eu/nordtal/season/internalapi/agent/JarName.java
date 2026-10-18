package eu.nordtal.season.internalapi.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Splits a jar's filename: the version starts at the last {@code -} that a digit follows.
 * Otherwise the name splits at its last {@code -}.
 * It matches {@code jar_identity} in {@code entrypoint.sh}.
 */
public final class JarName {

    private static final String SUFFIX = ".jar";

    // Greedy, so that the identity ends at the last dash that a digit follows.
    private static final Pattern VERSIONED = Pattern.compile("^(?<identity>.+)-(?<version>[0-9].*)$");

    private JarName() {}

    public static boolean isJar(final String fileName) {
        return fileName.endsWith(SUFFIX) && fileName.length() > SUFFIX.length();
    }

    /**
     * Returns the filename without {@code -<version>.jar}.
     * Returns {@code null} if it is no jar or does not split into two non-empty parts.
     */
    public static @Nullable String prefixOf(final String fileName) {
        return versionOf(fileName) == null ? null : splitStem(fileName, true);
    }

    /**
     * Returns the version segment as text, for comparing equality and not order.
     * Returns {@code null} under the same conditions as {@link #prefixOf}.
     */
    public static @Nullable String versionOf(final String fileName) {
        return splitStem(fileName, false);
    }

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

    /** Returns whether {@code candidate} is an older copy of {@code wanted}: same prefix, different file. */
    public static boolean looksSuperseded(final String candidate, final String wanted) {
        if (candidate.equals(wanted)) {
            return false;
        }
        final String candidatePrefix = prefixOf(candidate);
        return candidatePrefix != null && candidatePrefix.equals(prefixOf(wanted));
    }
}

package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.stewardagent.source.Versions;
import org.jspecify.annotations.Nullable;

/**
 * Which release a run is for, against the release this agent is: an agent carries out runs of its own release only.
 *
 * A newer release goes to a one-shot steward-agent at that release, which also renews this agent last.
 */
final class Release {

    private Release() {}

    /** How the newest published release stands to this agent's own. */
    enum Standing {

        /** This agent's release, or one nothing can compare: the run goes ahead here. */
        OWN,

        /** A later release, which only a steward-agent at that release carries out. */
        NEWER,

        /** An earlier one: no schema goes back, so nothing installs it. */
        OLDER
    }

    /**
     * Places the release a plan resolved against this agent's own.
     *
     * @param tag the release tag, with or without its leading {@code v}; {@code null} when it did not resolve
     * @param own this process's version; {@code null} from a test, which runs everything as its own
     */
    static Standing of(final @Nullable String tag, final @Nullable String own) {
        if (tag == null || own == null) {
            return Standing.OWN;
        }
        final String release = version(tag);
        if (release.equals(own)) {
            return Standing.OWN;
        }
        try {
            final int order = Versions.compare(release, own);
            return order == 0 ? Standing.OWN : order > 0 ? Standing.NEWER : Standing.OLDER;
        } catch (final NumberFormatException unordered) {
            // The newest published release is the one to run, whatever its name says about order.
            return Standing.NEWER;
        }
    }

    /** The version a release tag names, without the {@code v} our tags carry. */
    static String version(final String tag) {
        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    /**
     * The release this process runs as, or {@code null} from a test.
     *
     * That is the {@code NORDTAL_RELEASE} its container was made with, its image's tag, or else its manifest version.
     */
    static @Nullable String ownVersion() {
        final String release = System.getenv("NORDTAL_RELEASE");
        if (release != null && !release.isBlank()) {
            return release;
        }
        return Release.class.getPackage().getImplementationVersion();
    }
}

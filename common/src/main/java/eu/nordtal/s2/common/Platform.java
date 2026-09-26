package eu.nordtal.s2.common;

/**
 * The platform this season is built against: one Minecraft version, one Velocity major.
 *
 * A property of the season, not of an installation, so it is source and not configuration.
 * {@code PlatformTest} holds it against {@code gradle/libs.versions.toml} and the other mirrors. Paper is
 * an exact version and Velocity a family, as the PaperMC Fill API groups them.
 */
public final class Platform {

    /**
     * The Minecraft version the whole network runs, as Fill and Modrinth spell it.
     *
     * The {@code paper} catalog entry reads {@code 26.2.build.NNN-stable}; this is the part before {@code .build.}.
     */
    public static final String MINECRAFT = "26.2";

    /** Fill's key for the Velocity major the proxy runs, not a version; its newest stable member is installed. */
    public static final String VELOCITY_FAMILY = "4.0.0";

    /**
     * The Velocity version {@code proxy} is compiled against, mirrored by the {@code velocity} catalog entry.
     *
     * The update report names a skew against the installed version but does not block on it.
     */
    public static final String VELOCITY_API = "4.2.0";

    /** The resource pack format {@link #MINECRAFT} reads, mirrored by {@code resource-pack/src/pack.mcmeta}. */
    public static final int PACK_FORMAT = 88;

    /**
     * What the three Paper plugins declare as {@code api-version} in their {@code paper-plugin.yml}.
     *
     * The same string as {@link #MINECRAFT} and kept as its own constant anyway, because the two
     * answer different questions: this one is the oldest API a plugin promises to work against, and
     * a season that deliberately stayed compatible with an older one would move them apart. Today
     * they agree, and {@code PlatformTest} asserts both that they agree and that all three
     * descriptors say so - three files, one fact, and no reason for any of them to disagree.
     */
    public static final String API_VERSION = "26.2";

    private Platform() {}
}

package eu.nordtal.s2.common;

/**
 * The platform this season is built against: one Minecraft version and one Velocity family.
 *
 * {@code PlatformTest} holds these constants against {@code gradle/libs.versions.toml} and the other mirrors.
 */
public final class Platform {

    /** The Minecraft version the whole network runs, as Fill and Modrinth spell it. */
    public static final String MINECRAFT = "26.2";

    /** Fill's key for the Velocity major the proxy runs, not a version; its newest stable member is installed. */
    public static final String VELOCITY_FAMILY = "4.0.0";

    /** The Velocity version {@code proxy} is compiled against, mirrored by the {@code velocity} catalog entry. */
    public static final String VELOCITY_API = "4.2.0";

    /** The resource pack format {@link #MINECRAFT} reads, mirrored by {@code resource-pack/src/pack.mcmeta}. */
    public static final int PACK_FORMAT = 88;

    /** The {@code api-version} the three Paper plugins declare: the oldest API they promise to work against. */
    public static final String API_VERSION = "26.2";

    private Platform() {}
}

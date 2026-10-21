package eu.nordtal.season.common;

/**
 * The Minecraft and Velocity versions of this season.
 * {@code PlatformTest} compares them with the other places that name them.
 */
public final class Platform {

    /** The Minecraft version of the whole network. */
    public static final String MINECRAFT = "26.2";

    /** Fill's key for the Velocity major version of the proxy. */
    public static final String VELOCITY_FAMILY = "4.0.0";

    /** The Velocity version that {@code proxy} compiles against. */
    public static final String VELOCITY_API = "4.3.0";

    /** The resource pack format that {@link #MINECRAFT} reads. {@code resource-pack/src/pack.mcmeta} must cover it. */
    public static final int PACK_FORMAT = 88;

    /** The {@code api-version} of the three Paper plugins. */
    public static final String API_VERSION = "26.2";

    private Platform() {}
}

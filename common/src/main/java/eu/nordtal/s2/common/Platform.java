package eu.nordtal.s2.common;

/**
 * The platform this season is built against: one Minecraft version, one Velocity major.
 *
 * <h2>Why this is source and not a YAML key</h2>
 * A platform version is a property of the <em>season</em>, not of an installation. Every plugin in
 * the organisation is compiled against exactly one Paper API and one Velocity API; the resource
 * pack's {@code pack_format} is chosen for that same version, and the message bundles, the fonts
 * and the world all assume it. So there is no deployment that could sensibly answer this question
 * differently from the source tree it runs - and until 2026-09-09 there was one that could: the
 * updater read {@code minecraft-version} and {@code velocity-version} out of {@code updater.yml},
 * with {@code compose.yml} feeding them from {@code PAPER_VERSION} / {@code VELOCITY_VERSION} in
 * {@code .env}. An operator who typed a different number there did not misconfigure the updater;
 * they pointed the whole network at a Minecraft version nothing in this repository was compiled
 * for, and the first sign of it would have been plugins failing to load.
 *
 * <p>The constants are mirrored by {@code gradle/libs.versions.toml}, which is what the modules
 * actually compile against, and {@code PlatformTest} in this module holds the two against each
 * other on every {@code check}. Moving a version is therefore one edit here, one in the catalog,
 * and a red build until both agree.</p>
 *
 * <h2>Paper is an exact version and Velocity is a family</h2>
 * These are asymmetric on purpose, and the asymmetry is the PaperMC Fill API's own. Fill groups
 * versions into families: {@code GET /v3/projects/paper} answers {@code "26.2": ["26.2",
 * "26.2-rc-2"]}, and {@code GET /v3/projects/velocity} answers {@code "4.0.0": ["4.1.2-SNAPSHOT",
 * "4.1.1", "4.1.0", …]} - so Fill's name for the whole Velocity 4 line is the string
 * {@code 4.0.0}.
 *
 * <p>{@link #MINECRAFT} is used as an <b>exact version</b>: a new Minecraft version is a season
 * decision and never the updater's, because it moves the API every plugin here is compiled against.
 * {@link #VELOCITY_FAMILY} is used as a <b>family</b>: the proxy follows the newest stable version
 * inside Velocity's major 4, which today resolves to {@link #VELOCITY_API} by a different road than
 * pinning it did. Velocity's minor releases do not move the Minecraft protocol, so the thing a
 * season decision is protecting is not at stake there.</p>
 */
public final class Platform {

    /**
     * The Minecraft version the whole network runs, as the PaperMC Fill API and Modrinth both spell
     * it. Used as the exact Fill version for the three Paper backends and as Modrinth's
     * {@code game_versions} filter.
     *
     * <p>Mirrored by {@code paper} in {@code gradle/libs.versions.toml}, which reads
     * {@code 26.2.build.NNN-stable} - this is the part in front of {@code .build.}.</p>
     */
    public static final String MINECRAFT = "26.2";

    /**
     * Fill's name for the Velocity major the proxy runs. <b>Not a version</b> - it is the key under
     * {@code "versions"} in {@code GET https://fill.papermc.io/v3/projects/velocity}, and the
     * newest stable member of it is what actually gets installed.
     */
    public static final String VELOCITY_FAMILY = "4.0.0";

    /**
     * The Velocity version {@code network-control} is <em>compiled</em> against, mirrored by
     * {@code velocity} in {@code gradle/libs.versions.toml}.
     *
     * <p>It exists so that the updater can say when the two have parted company. Following
     * {@link #VELOCITY_FAMILY} means the proxy can be moved to a newer 4.x by a run nobody
     * reviewed, and a plugin built against an older API then runs on a newer one - the same trap
     * {@code UpdaterSpec}'s Chunky comment describes for a {@code compileOnly} pin. The update
     * report names it and does not block on it: refusing the proxy's own update over a version skew
     * that is usually harmless would be the worse failure.</p>
     */
    public static final String VELOCITY_API = "4.1.1";

    /**
     * The resource pack format {@link #MINECRAFT} reads, mirrored by
     * {@code resource-pack/src/pack.mcmeta}.
     *
     * <p>Here since 2026-09-09, and it is the same argument as the two versions above rather than a
     * new one: the number is a fact about the Minecraft version, it is written down in exactly one
     * other place, and nothing compares the two. A pack whose format is a version behind is not a
     * pack that fails to load - the client accepts it and warns - so the way this goes wrong is a
     * season running on art nobody noticed was stale. 26.1 was 84 and 26.3's snapshots are 89.</p>
     */
    public static final int PACK_FORMAT = 88;

    /**
     * What the three Paper plugins declare as {@code api-version} in their {@code paper-plugin.yml}.
     *
     * <p>The same string as {@link #MINECRAFT} and kept as its own constant anyway, because the two
     * answer different questions: this one is the oldest API a plugin promises to work against, and
     * a season that deliberately stayed compatible with an older one would move them apart. Today
     * they agree, and {@code PlatformTest} asserts both that they agree and that all three
     * descriptors say so - three files, one fact, and no reason for any of them to disagree.</p>
     */
    public static final String API_VERSION = "26.2";

    private Platform() {
    }
}

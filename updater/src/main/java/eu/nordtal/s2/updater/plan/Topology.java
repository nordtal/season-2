package eu.nordtal.s2.updater.plan;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Which server runs which jars. A mirror of {@code deploy/compose.yml}, and it says so out loud.
 *
 * <h2>Why this is code and not configuration</h2>
 * Because it is not a deployment's decision. That the SMP server needs DisplayTags is not a
 * preference an operator expresses in a YAML file - it is
 * {@code smp/src/main/resources/paper-plugin.yml} declaring {@code load: BEFORE, required: true},
 * which means the SMP plugin does not enable without it. The same is true of every other row here.
 * A config key would only offer a way to write down something false.
 *
 * <p><b>The cost, stated plainly: this list and {@code compose.yml} are two copies of one fact.</b>
 * A fifth backend server is a change to both, in the same commit, and the failing test that
 * reminds you is {@code TopologyTest}. That is the same trade {@code common}'s {@code Glyphs}
 * makes against the resource pack's {@code default.json}, for the same reason - the alternative is
 * parsing a compose file at runtime to find out what we already know.</p>
 *
 * <h2>The bot and the updater are not in {@link #SERVICES}</h2>
 * Neither has a {@code plugins/} folder or a server jar; each <em>is</em> a jar in a volume of its
 * own. {@link #STANDALONE_JARS} is where they are named, and everything downstream treats them as
 * services with exactly one artefact and no subdirectory.
 */
public final class Topology {

    /** What kind of server jar a service runs, which is also the Fill API's project name. */
    public enum Kind {
        PAPER("paper"),
        VELOCITY("velocity");

        private final String fillProject;

        Kind(final String fillProject) {
            this.fillProject = fillProject;
        }

        public @NotNull String fillProject() {
            return fillProject;
        }
    }

    /**
     * @param name    the compose service name, which is also the directory under
     *                {@code volumes-root} and the volume's own name minus the {@code mc-} prefix.
     * @param plugins the artifact ids whose jars belong in this service's {@code plugins/} folder.
     */
    public record Service(@NotNull String name, @NotNull Kind kind, @NotNull List<String> plugins) {
    }

    // ---------------------------------------------------------------- artifact ids
    // The id is what the topology, the resolver and the report join on. For our own five jars it
    // happens to equal the jar's filename prefix; for the third-party three it does not
    // (packetevents -> packetevents-spigot-2.13.0.jar, chunky -> Chunky-Bukkit-1.5.3.jar), which
    // is exactly why the prefix is read back off the resolved filename instead of being assumed.

    public static final String NETWORK_CONTROL = "network-control";
    public static final String LIMBO = "limbo";
    public static final String HUNGER_GAMES = "hunger-games";
    public static final String SMP = "smp";
    public static final String DISCORD_BOT = "discord-bot";
    public static final String UPDATER = "updater";

    public static final String DISPLAY_TAGS = "display-tags";
    public static final String PACKETEVENTS = "packetevents";
    public static final String CHUNKY = "chunky";

    public static final String PAPER = "paper";
    public static final String VELOCITY = "velocity";

    /** The resource pack: not a jar, not installed anywhere, but a version that has to move. */
    public static final String RESOURCE_PACK = "resource-pack";

    /**
     * The six artefacts a season-2 release publishes as jars.
     * <p>
     * {@link #UPDATER} is in this list for the same reason the module exists: its own version has
     * to move by the mechanism it implements, or it becomes the one thing left being updated by
     * hand. It cannot run its own swap - no process replaces its own jar and keeps going - which
     * is why the restart is what brings it back on the new one.
     * </p>
     */
    public static final List<String> SEASON_JARS =
            List.of(NETWORK_CONTROL, LIMBO, HUNGER_GAMES, SMP, DISCORD_BOT, UPDATER);

    /**
     * The two artefacts that are a whole container each.
     * <p>
     * Since 2026-09-01 both run from a volume rather than from a jar baked into an image, so both
     * move by the same mechanism as everything else and roll back the same way. Their volume is
     * {@code <volumes-root>/<name>} and the jar sits in its root - no {@code plugins/}, nothing
     * else in there.
     * </p>
     *
     * <p><b>The updater installing its own new jar is deliberate and cannot take effect during the
     * run.</b> No process replaces the jar it is executing and keeps going; what happens is that
     * the new jar is placed, the old one is deleted, and the <em>next start</em> of this container
     * comes up on the new one. That start is the restart in step 6 - which is why the updater's own
     * version only ever moves across a restart, never during a run.</p>
     */
    public static final List<String> STANDALONE_JARS = List.of(DISCORD_BOT, UPDATER);

    /**
     * Whether this artefact is a container's whole jar rather than a plugin or a server jar.
     *
     * @param artifact an artifact id
     * @return {@code true} for the bot and the updater
     */
    public static boolean isStandalone(final @NotNull String artifact) {
        return STANDALONE_JARS.contains(artifact);
    }

    /** The four Minecraft services, in the order the report reads best: proxy first, then backends. */
    public static final List<Service> SERVICES = List.of(
            new Service(NETWORK_CONTROL, Kind.VELOCITY, List.of(NETWORK_CONTROL)),
            new Service(LIMBO, Kind.PAPER, List.of(LIMBO)),
            new Service(HUNGER_GAMES, Kind.PAPER, List.of(HUNGER_GAMES)),
            // The only service with required third-party plugins. DisplayTags is required by the
            // SMP plugin's own paper-plugin.yml; PacketEvents is required under DisplayTags;
            // Chunky pre-generates the world border and is loaded by :smp reflectively.
            new Service(SMP, Kind.PAPER, List.of(SMP, DISPLAY_TAGS, PACKETEVENTS, CHUNKY)));

    private Topology() {
    }
}

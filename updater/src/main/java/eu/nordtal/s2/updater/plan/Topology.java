package eu.nordtal.s2.updater.plan;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Which server runs which jars. A mirror of {@code compose.yml}, and it says so out loud.
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
     * @param name     the compose service name, which is also the directory under
     *                 {@code volumes-root} and the volume's own name minus the {@code mc-} prefix.
     * @param plugins  the artifact ids whose jars belong in this service's {@code plugins/} folder.
     * @param optional the subset of {@code plugins} whose <b>absence must not stop the container</b>
     *                 - see {@link #optional()}.
     */
    public record Service(@NotNull String name, @NotNull Kind kind, @NotNull List<String> plugins,
                          @NotNull List<String> optional) {

        /** A service every one of whose plugins the entrypoint guard demands. */
        public Service(final @NotNull String name, final @NotNull Kind kind,
                       final @NotNull List<String> plugins) {
            this(name, kind, plugins, List.of());
        }

        public Service {
            plugins = List.copyOf(plugins);
            optional = List.copyOf(optional);
            if (!plugins.containsAll(optional)) {
                throw new IllegalArgumentException(name + " marks a plugin optional that it does"
                        + " not run: " + optional + " is not inside " + plugins);
            }
        }

        /**
         * The plugins {@code EXPECTED_PLUGINS} in {@code compose.yml} has to ask for.
         *
         * <p><b>Every plugin here is one this container refuses to start without.</b> That guard
         * exists because a {@code plugins/} folder holding <em>some</em> of a server's jars looks
         * exactly like a healthy one - it is how an SMP with no season on it once started and
         * reported healthy. What it cannot be pointed at is an artefact that <em>cannot be
         * installed</em>: {@link Change.Status#UNSUPPORTED} is somebody else's release schedule,
         * and turning that into a server that will not boot buys nothing and costs a season.</p>
         */
        public @NotNull List<String> guarded() {
            return plugins.stream().filter(plugin -> !optional.contains(plugin)).toList();
        }
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

    /**
     * Simple Voice Chat's Bukkit plugin - {@code voicechat-bukkit-<version>.jar}, so the filename
     * prefix is {@code voicechat-bukkit} and not this id.
     *
     * <p>It is <b>optional for a player</b>: the audio is carried by a mod the client either has or
     * has not, and a client without it notices nothing at all. It is not optional for the server,
     * which is why it is an ordinary plugin row here and is asked for by the entrypoint guard: a
     * backend that quietly came up without it is a backend where everybody's voice chat is off and
     * nothing in the game says why.</p>
     */
    public static final String VOICE_CHAT = "voicechat";

    /**
     * Simple Voice Chat's Velocity plugin - {@code voicechat-velocity-<version>.jar}, on the proxy
     * and nowhere else. Here the artefact id and the filename prefix do coincide.
     *
     * <h2>What it buys, and why the proxy is in this at all</h2>
     * Audio is UDP and does not travel inside the Minecraft connection. Without this plugin every
     * backend's own voice port has to be published and reachable from the internet, each backend
     * needs a distinct port number, and each one's {@code voice_host} has to be set by hand in a
     * file nothing in this repository writes. With it, <b>one</b> UDP port on the proxy is the
     * whole of it: the plugin detects the address and port of each backend itself and forwards to
     * the right one, and each backend's {@code voice_host} is ignored (Simple Voice Chat wiki,
     * "Proxy Setup" and "Proxy Config File", read 2026-09-09).
     *
     * <p>The backends therefore publish no host port at all any more. They are reached over the
     * compose network by the proxy, which is a thing containers on one network can always do.</p>
     *
     * <h2>It is resolved from a pre-release, deliberately</h2>
     * This is the one artefact in {@link eu.nordtal.s2.updater.source.Modrinth#PRE_RELEASE_EXCEPTIONS},
     * because the project has never published a Velocity build marked {@code release} - not one, in
     * thirteen versions. Waiting for one is not a slower path to the same place. The reasoning, and
     * the reason it is a named constant instead of a setting, is on that field.
     *
     * <p>It is {@link Service#optional() optional} for the same reason {@link #VOICE_CHAT} is, and
     * one more: an alpha is exactly the kind of artefact whose next version may fail to resolve or
     * fail to load, and a proxy that will not start is the whole network.</p>
     */
    public static final String VOICE_CHAT_PROXY = "voicechat-velocity";

    /**
     * CoreProtect, the block logger - {@code CoreProtect-CE-<version>.jar}, so the filename prefix
     * is {@code CoreProtect-CE}.
     *
     * <p><b>It has no build for this Minecraft version and it is in the plan anyway</b> (owner,
     * 2026-09-08): the newest release, 24.0, stops at 26.1.2, checked against Modrinth on that
     * date. The row resolves as {@link Change.Status#UNSUPPORTED} and stays named in every report
     * until a compatible build appears, at which point the next run installs it and nobody edits
     * any code. The alternative - leaving it out until then - is a thing somebody has to
     * remember.</p>
     */
    public static final String CORE_PROTECT = "coreprotect";

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
            // The proxy carries voice chat's proxy half, which is what makes one published UDP port
            // enough for the whole network - see VOICE_CHAT_PROXY. It is optional here for the
            // strongest reason anything on this list is: this container is the network.
            new Service(NETWORK_CONTROL, Kind.VELOCITY, List.of(NETWORK_CONTROL, VOICE_CHAT_PROXY),
                    List.of(VOICE_CHAT_PROXY)),
            new Service(LIMBO, Kind.PAPER, List.of(LIMBO)),
            // Voice chat is on the two servers people play on and not on limbo: the waiting room
            // is seconds long and holds nobody who could be talked to (owner, 2026-09-08).
            new Service(HUNGER_GAMES, Kind.PAPER, List.of(HUNGER_GAMES, VOICE_CHAT)),
            // The only service with required third-party plugins. DisplayTags is required by the
            // SMP plugin's own paper-plugin.yml; PacketEvents is required under DisplayTags;
            // Chunky pre-generates the world border and is loaded by :smp reflectively.
            // CoreProtect is optional here in the one sense this record means it: the guard does not
            // ask for it. It cannot, while no build for this Minecraft version exists - a server
            // that refuses to start until a third party ships is worse than a season with no block
            // log on it.
            new Service(SMP, Kind.PAPER,
                    List.of(SMP, DISPLAY_TAGS, PACKETEVENTS, CHUNKY, VOICE_CHAT, CORE_PROTECT),
                    List.of(CORE_PROTECT)));

    private Topology() {
    }
}

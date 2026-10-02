package eu.nordtal.s2.internalapi.agent;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Which server runs which jars, a mirror of {@code compose.yml} that {@code TopologyTest} holds in step.
 *
 * Code, not configuration: each row is a jar that another jar on that service requires.
 */
public final class Topology {

    /** What kind of server jar a service runs, which is also the Fill API's project name. */
    public enum Kind {
        PAPER("paper", "paper"),
        VELOCITY("velocity", "velocity");

        private final String fillProject;
        private final String modrinthLoader;

        Kind(final String fillProject, final String modrinthLoader) {
            this.fillProject = fillProject;
            this.modrinthLoader = modrinthLoader;
        }

        public String fillProject() {
            return fillProject;
        }

        /** What Modrinth calls this platform, kept apart from {@link #fillProject()} because two vendors name them. */
        public String modrinthLoader() {
            return modrinthLoader;
        }
    }

    /**
     * One compose service with a plugins folder.
     *
     * @param name the compose service name, also its directory under {@code volumes-root}
     * @param plugins the artifact ids whose jars belong in its {@code plugins/} folder
     * @param optional the subset of {@code plugins} whose absence must not stop the container
     */
    public record Service(String name, Kind kind, List<String> plugins, List<String> optional) {

        /** A service every one of whose plugins the entrypoint guard demands. */
        public Service(final String name, final Kind kind, final List<String> plugins) {
            this(name, kind, plugins, List.of());
        }

        public Service {
            plugins = List.copyOf(plugins);
            optional = List.copyOf(optional);
            if (!plugins.containsAll(optional)) {
                throw new IllegalArgumentException(name + " marks a plugin optional that it does" + " not run: "
                        + optional + " is not inside " + plugins);
            }
        }

        /**
         * The plugins {@code EXPECTED_PLUGINS} in {@code compose.yml} has to ask for, which excludes the optional ones.
         *
         * An unsupported artefact must never become a server that will not boot.
         */
        public List<String> guarded() {
            return plugins.stream().filter(plugin -> !optional.contains(plugin)).toList();
        }
    }

    // The id is what topology, resolver and report join on; for third-party jars it is not the filename prefix.

    public static final String PROXY = "proxy";
    public static final String LIMBO = "limbo";
    public static final String HUNGER_GAMES = "hunger-games";
    public static final String SMP = "smp";
    public static final String DISCORD_BOT = "discord-bot";
    public static final String STEWARD = "steward";

    /** The service that applies the schema from steward-agent's image and exits; an update's install runs it. */
    public static final String MIGRATE = "migrate";

    public static final String DISPLAY_TAGS = "display-tags";
    public static final String PACKETEVENTS = "packetevents";

    /** Simple Voice Chat's Bukkit plugin, optional since a missing voice chat must not stop a server from starting. */
    public static final String VOICE_CHAT = "voicechat";

    /**
     * Simple Voice Chat's Velocity plugin, which forwards every backend's voice over the proxy's one UDP port.
     *
     * Resolved from a pre-release and optional, since a proxy that will not start is the whole network.
     */
    public static final String VOICE_CHAT_PROXY = "voicechat-velocity";

    /**
     * CoreProtect, the block logger, which stays in the plan as {@link Change.Status#UNSUPPORTED} until it has a build.
     */
    public static final String CORE_PROTECT = "coreprotect";

    public static final String PAPER = "paper";
    public static final String VELOCITY = "velocity";

    /** The resource pack: not a jar, not installed anywhere, but a version that has to move. */
    public static final String RESOURCE_PACK = "resource-pack";

    /** The four plugins a season-2 release publishes as jars; the bot and steward are in their images. */
    public static final List<String> SEASON_JARS = List.of(PROXY, LIMBO, HUNGER_GAMES, SMP);

    /** Every plugin Nordtal publishes itself, keyed by filename prefix, in the order the plugins tab lists them. */
    public static final Map<String, String> NORDTAL_PLUGINS = orderedMap(
            "papermc-display-tags",
            "Display Tags",
            SMP,
            "SMP",
            PROXY,
            "Proxy",
            LIMBO,
            "Limbo",
            HUNGER_GAMES,
            "Hunger Games");

    /** The data folder each Nordtal plugin keeps its config in, with the name the plugins tab shows. */
    public static final Map<String, String> NORDTAL_DATA_FOLDERS = orderedMap(
            "DisplayTags", "Display Tags", SMP, "SMP", PROXY, "Proxy", LIMBO, "Limbo", HUNGER_GAMES, "Hunger Games");

    /** Whether a jar with this filename prefix is one Nordtal publishes. */
    public static boolean isNordtal(final @Nullable String prefix) {
        return prefix != null && NORDTAL_PLUGINS.containsKey(prefix);
    }

    /** The filename prefix a fixed artefact's jar carries, where that is known up front. */
    public static @Nullable String nordtalPrefixOf(final String artifact) {
        if (DISPLAY_TAGS.equals(artifact)) {
            return "papermc-display-tags";
        }
        return NORDTAL_PLUGINS.containsKey(artifact) ? artifact : null;
    }

    private static Map<String, String> orderedMap(final String... pairs) {
        final Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /** The four Minecraft services, in the order the report reads best: proxy first, then backends. */
    public static final List<Service> SERVICES = List.of(
            // The proxy's voice chat half is optional because this container is the network.
            new Service(PROXY, Kind.VELOCITY, List.of(PROXY, VOICE_CHAT_PROXY), List.of(VOICE_CHAT_PROXY)),
            new Service(LIMBO, Kind.PAPER, List.of(LIMBO)),
            // Voice chat runs where people play, not on limbo.
            new Service(HUNGER_GAMES, Kind.PAPER, List.of(HUNGER_GAMES, VOICE_CHAT), List.of(VOICE_CHAT)),
            // The only service with required third-party plugins; CoreProtect and voice chat are optional.
            new Service(
                    SMP,
                    Kind.PAPER,
                    List.of(SMP, DISPLAY_TAGS, PACKETEVENTS, VOICE_CHAT, CORE_PROTECT),
                    List.of(VOICE_CHAT, CORE_PROTECT)));

    /**
     * Whether {@code service} is one of the four with a plugins folder, which decides whether the Plugins tab is drawn.
     *
     * @param service a compose service name
     * @return {@code true} for proxy, limbo, hunger-games and smp
     */
    public static boolean hasPlugins(final String service) {
        return SERVICES.stream().anyMatch(candidate -> candidate.name().equals(service));
    }

    /** What a replacement instance of a service is called: its own name and this. */
    public static final String STANDBY_SUFFIX = "-standby";

    /**
     * The services with a {@code -standby} counterpart in {@code compose.yml}, in the order a swap uses them.
     *
     * Nothing resolves for a standby: {@code Standbys} copies the live service's {@code plugins/} across.
     */
    public static final List<String> SERVICES_WITH_STANDBY = List.of(PROXY, LIMBO);

    /** The compose service name of {@code service}'s standby, whether or not it has one. */
    public static String standbyOf(final String service) {
        return service + STANDBY_SUFFIX;
    }

    /** Every standby compose.yml defines, in the order of {@link #SERVICES_WITH_STANDBY}. */
    public static List<String> standbyNames() {
        return SERVICES_WITH_STANDBY.stream().map(Topology::standbyOf).toList();
    }

    /**
     * The artefact id an added plugin resolves under on a service of this kind.
     *
     * The loader is in the id because one Modrinth project can be two jars, as Simple Voice Chat is.
     */
    public static String addedArtifact(final String slug, final Kind kind) {
        return kind == Kind.VELOCITY ? slug + "-velocity" : slug;
    }

    private Topology() {}
}

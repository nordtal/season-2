package eu.nordtal.season.internalapi.agent;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The names a run joins on: server kinds, artefact ids and the plugins Nordtal publishes.
 *
 * Which server runs which jars is not here: compose.yml's labels say it, and steward-agent serves them.
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

        /** The kind whose {@link #fillProject()} this is, which is how the label {@code eu.nordtal.server} names it. */
        public static Kind of(final String fillProject) {
            for (final Kind kind : values()) {
                if (kind.fillProject.equals(fillProject)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("no server kind is called '" + fillProject + "'");
        }

        /** What Modrinth calls this platform, kept apart from {@link #fillProject()} because two vendors name them. */
        public String modrinthLoader() {
            return modrinthLoader;
        }
    }

    /**
     * One compose service with a plugins folder, as its {@code eu.nordtal.plugins} label describes it.
     *
     * @param name the compose service name, also its directory under {@code volumes-root}
     * @param plugins the artifact ids whose jars belong in its {@code plugins/} folder
     * @param optional the subset of {@code plugins} whose absence must not stop the container
     * @param prefixes the jar filename prefix of each artifact whose prefix is not its id
     */
    public record Service(
            String name, Kind kind, List<String> plugins, List<String> optional, Map<String, String> prefixes) {

        /** A service whose every jar is named after its artifact id. */
        public Service(final String name, final Kind kind, final List<String> plugins, final List<String> optional) {
            this(name, kind, plugins, optional, Map.of());
        }

        /** A service every one of whose plugins the entrypoint guard demands. */
        public Service(final String name, final Kind kind, final List<String> plugins) {
            this(name, kind, plugins, List.of());
        }

        public Service {
            plugins = List.copyOf(plugins);
            optional = List.copyOf(optional);
            prefixes = Map.copyOf(prefixes);
            if (!plugins.containsAll(optional)) {
                throw new IllegalArgumentException(name + " marks a plugin optional that it does" + " not run: "
                        + optional + " is not inside " + plugins);
            }
        }

        /** The filename prefix of {@code artifact}'s jar on this service, its id unless the label names another. */
        public String prefixOf(final String artifact) {
            return prefixes.getOrDefault(artifact, artifact);
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

    private static Map<String, String> orderedMap(final String... pairs) {
        final Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
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

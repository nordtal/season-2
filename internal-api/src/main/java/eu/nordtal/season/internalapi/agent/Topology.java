package eu.nordtal.season.internalapi.agent;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The names that a run joins on: server kinds, artefact ids and the plugins that Nordtal publishes. */
public final class Topology {

    /** The kind of server jar, which is also the project name in the Fill API. */
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

        public static Kind of(final String fillProject) {
            for (final Kind kind : values()) {
                if (kind.fillProject.equals(fillProject)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("no server kind is called '" + fillProject + "'");
        }

        public String modrinthLoader() {
            return modrinthLoader;
        }
    }

    /**
     * A compose service with a plugins folder, as its {@code eu.nordtal.plugins} label describes it.
     *
     * @param name the compose service name, also its directory under {@code volumes-root}
     * @param plugins the artifact ids whose jars belong in its {@code plugins/} folder
     * @param optional the plugins whose absence does not stop the container
     * @param prefixes the jar filename prefix of each artifact whose prefix is not its id
     */
    public record Service(
            String name, Kind kind, List<String> plugins, List<String> optional, Map<String, String> prefixes) {

        public Service(final String name, final Kind kind, final List<String> plugins, final List<String> optional) {
            this(name, kind, plugins, optional, Map.of());
        }

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

        public String prefixOf(final String artifact) {
            return prefixes.getOrDefault(artifact, artifact);
        }
    }

    // The id joins topology, resolver and report. For a third-party jar it differs from the filename prefix.

    public static final String PROXY = "proxy";
    public static final String LIMBO = "limbo";
    public static final String HUNGER_GAMES = "hunger-games";
    public static final String SMP = "smp";
    public static final String DISCORD_BOT = "discord-bot";
    public static final String STEWARD = "steward";

    public static final String MIGRATE = "migrate";

    public static final String PACKETEVENTS = "packetevents";

    /** The Bukkit plugin of Simple Voice Chat. It is optional. */
    public static final String VOICE_CHAT = "voicechat";

    /**
     * The Velocity plugin of Simple Voice Chat.
     * It forwards the voice of every backend over the proxy's one UDP port.
     * It is optional.
     */
    public static final String VOICE_CHAT_PROXY = "voicechat-velocity";

    /** CoreProtect stays in the plan as {@link Change.Status#UNSUPPORTED} until it has a build. */
    public static final String CORE_PROTECT = "coreprotect";

    public static final String PAPER = "paper";
    public static final String VELOCITY = "velocity";

    public static final String RESOURCE_PACK = "resource-pack";

    public static final List<String> SEASON_JARS = List.of(PROXY, LIMBO, HUNGER_GAMES, SMP);

    /** Every plugin that Nordtal publishes, by filename prefix, in the order of the plugins tab. */
    public static final Map<String, String> NORDTAL_PLUGINS =
            orderedMap(SMP, "SMP", PROXY, "Proxy", LIMBO, "Limbo", HUNGER_GAMES, "Hunger Games");

    /** The data folder of each Nordtal plugin and the name the plugins tab shows. */
    public static final Map<String, String> NORDTAL_DATA_FOLDERS =
            orderedMap(SMP, "SMP", PROXY, "Proxy", LIMBO, "Limbo", HUNGER_GAMES, "Hunger Games");

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
     * Returns the artefact id of an added plugin on a service of this kind.
     * The loader is in the id, because one Modrinth project can be two jars.
     */
    public static String addedArtifact(final String slug, final Kind kind) {
        return kind == Kind.VELOCITY ? slug + "-velocity" : slug;
    }

    private Topology() {}
}

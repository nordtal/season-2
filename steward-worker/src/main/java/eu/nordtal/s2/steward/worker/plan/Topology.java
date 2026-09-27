package eu.nordtal.s2.steward.worker.plan;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Which server runs which jars. A mirror of {@code compose.yml}, and it says so out loud.
 *
 * Why this is code and not configuration: Because it is not a deployment's decision. That the SMP server needs
 * DisplayTags is not a preference an operator expresses in a YAML file - it is
 * {@code smp/src/main/resources/paper-plugin.yml} declaring {@code load: BEFORE, required: true}, which means the
 * SMP plugin does not enable without it. The same is true of every other row here. A config key would only offer a
 * way to write down something false.
 *
 * The cost, stated plainly: this list and {@code compose.yml} are two copies of one fact. A fifth backend server is
 * a change to both, in the same commit, and the failing test that reminds you is {@code TopologyTest}. That is the
 * same trade {@code common} 's {@code Glyphs} makes against the resource pack's {@code default.json}, for the same
 * reason - the alternative is parsing a compose file at runtime to find out what we already know.
 *
 * The bot and steward-worker are not in {@link #SERVICES}: Neither has a {@code plugins/} folder or a server jar;
 * each is a jar in a volume of its own. {@link #STANDALONE_JARS} is where they are named, and everything downstream
 * treats them as services with exactly one artefact and no subdirectory.
 *
 * How a service is named: A service is named after its role and not after the software that fills it - which is why
 * {@link #PROXY} is {@code proxy} and not {@code velocity}. A replacement instance of a service is that same name
 * plus {@code -standby} - {@code proxy-standby}, {@code limbo-standby} - and never a colour or a number: a standby
 * takes the ordinary role of the service it stands in for, running the same jar under the same configuration, so the
 * suffix is the only thing that has to distinguish the two.
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

        /**
         * What Modrinth calls this platform.
         *
         * The {@code loaders} filter on a version query and the {@code categories} facet on a search.
         *
         * Kept apart from {@link #fillProject()} even though the two strings are equal today. They are two vendors'
         * vocabularies and nothing keeps them in step: one is PaperMC's Fill API naming its own downloads, the other is
         * Modrinth naming a loader in a tag anybody can apply. Collapsing them into one field would make the day they
         * diverge a silent wrong query rather than a compile error.
         */
        public String modrinthLoader() {
            return modrinthLoader;
        }
    }

    /**
     * @param name the compose service name, which is also the directory under {@code volumes-root} and the
     *     volume's own name minus the {@code mc-} prefix.
     * @param plugins the artifact ids whose jars belong in this service's {@code plugins/} folder.
     * @param optional the subset of {@code plugins} whose absence must not stop the container - see
     *     {@link #optional()}.
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
         * The plugins {@code EXPECTED_PLUGINS} in {@code compose.yml} has to ask for.
         *
         * Every plugin here is one this container refuses to start without. That guard exists because a
         * {@code plugins/} folder holding some of a server's jars looks exactly like a healthy one - it is how an
         * SMP with no season on it once started and reported healthy. What it cannot be pointed at is an artefact
         * that cannot be installed: {@link Change.Status#UNSUPPORTED} is somebody else's release schedule, and
         * turning that into a server that will not boot buys nothing and costs a season.
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
    public static final String STEWARD_WORKER = "steward-worker";

    public static final String DISPLAY_TAGS = "display-tags";
    public static final String PACKETEVENTS = "packetevents";

    /**
     * Simple Voice Chat's Bukkit plugin.
     *
     * {@code voicechat-bukkit-<version>.jar}, so the filename prefix is {@code voicechat-bukkit} and not this id.
     *
     * It is optional, and that is a decision about players rather than about the jar: voice chat is something a
     * player either has a mod for or has not, and a network where nobody can talk is a worse evening than a network
     * with no voice chat at all - so a missing jar must not be a server that refuses to start. It is therefore
     * named in {@link Service#optional()} on both backends and absent from their {@code EXPECTED_PLUGINS}. The cost
     * is real and is the price: a backend that came up without it looks healthy, and the only thing that says
     * otherwise is the update report naming the row.
     */
    public static final String VOICE_CHAT = "voicechat";

    /**
     * Simple Voice Chat's Velocity plugin, on the proxy and nowhere else.
     *
     * {@code voicechat-velocity-<version>.jar}, and here the artefact id and the filename prefix do coincide.
     *
     * What it buys, and why the proxy is in this at all: Audio is UDP and does not travel inside the Minecraft
     * connection. Without this plugin every backend's own voice port has to be published and reachable from the
     * internet, each backend needs a distinct port number, and each one's {@code voice_host} has to be set by hand
     * in a file nothing in this repository writes. With it, one UDP port on the proxy is the whole of it: the
     * plugin detects the address and port of each backend itself and forwards to the right one, and each backend's
     * {@code voice_host} is ignored (Simple Voice Chat wiki, "Proxy Setup" and "Proxy Config File").
     *
     * The backends therefore publish no host port at all any more. They are reached over the compose network by the
     * proxy, which is a thing containers on one network can always do.
     *
     * It is resolved from a pre-release, deliberately: this is the one artefact in
     * {@link eu.nordtal.s2.steward.worker.source.Modrinth#PRE_RELEASE_EXCEPTIONS}, because the project has never
     * published a Velocity build marked {@code release} - not one, in thirteen versions. Waiting for one is not a
     * slower path to the same place. The reasoning, and the reason it is a named constant instead of a setting, is
     * on that field.
     *
     * It is {@link Service#optional() optional} for the same reason {@link #VOICE_CHAT} is, and one more: an alpha is
     * exactly the kind of artefact whose next version may fail to resolve or fail to load, and a proxy that will not
     * start is the whole network.
     */
    public static final String VOICE_CHAT_PROXY = "voicechat-velocity";

    /**
     * CoreProtect, the block logger.
     *
     * {@code CoreProtect-CE-<version>.jar}, so the filename prefix is {@code CoreProtect-CE}. It has no build for
     * this Minecraft version and it is in the plan anyway: the row resolves as
     * {@link Change.Status#UNSUPPORTED} and stays named in every report until a compatible build appears, at which
     * point the next run installs it and nobody edits any code. The alternative - leaving it out until then - is a
     * thing somebody has to remember.
     */
    public static final String CORE_PROTECT = "coreprotect";

    public static final String PAPER = "paper";
    public static final String VELOCITY = "velocity";

    /** The resource pack: not a jar, not installed anywhere, but a version that has to move. */
    public static final String RESOURCE_PACK = "resource-pack";

    /**
     * The six artefacts a season-2 release publishes as jars.
     *
     * {@link #STEWARD_WORKER} is in this list for the same reason the module exists: its own version has to move by the
     * mechanism it implements, or it becomes the one thing left being updated by hand. It cannot run its own swap - no
     * process replaces its own jar and keeps going - which is why the restart is what brings it back on the new one.
     */
    public static final List<String> SEASON_JARS =
            List.of(PROXY, LIMBO, HUNGER_GAMES, SMP, DISCORD_BOT, STEWARD_WORKER);

    /**
     * Every plugin Nordtal publishes itself, in the order the plugins tab lists them.
     *
     * The name-tag fork first, then the season's own jars. Keyed by the jar's filename prefix, which for the
     * season jars is the artefact id and for the fork is its repository name.
     */
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
            "Hunger Games",
            DISCORD_BOT,
            "Discord Bot",
            STEWARD_WORKER,
            "Steward Worker");

    /**
     * The data folder each Nordtal plugin keeps its config in, with the same name the plugins tab shows.
     *
     * A season plugin's folder is its module name; the fork's is its plugin name.
     */
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

    /**
     * The two artefacts that are a whole container each.
     *
     * Both run from a volume rather than from a jar baked into an image, so both move by the same
     * mechanism as everything else. They do not roll back by any mechanism: nothing pins a release, and
     * the way out of a bad one is to publish a better one. Their volume is {@code <volumes-root>/<name>} and the jar
     * sits in its root - no {@code plugins/}, nothing else in there.
     *
     * Steward-worker installing its own new jar is deliberate and cannot take effect during the run. No process
     * replaces the jar it is executing and keeps going; what happens is that the new jar is placed, the old one is
     * deleted, and the next start of this container comes up on the new one. That start is the restart in step 6 -
     * which is why the worker's own version only ever moves across a restart, never during a run.
     */
    public static final List<String> STANDALONE_JARS = List.of(DISCORD_BOT, STEWARD_WORKER);

    /**
     * Whether this artefact is a container's whole jar rather than a plugin or a server jar.
     *
     * @param artifact an artifact id
     * @return {@code true} for the bot and steward-worker
     */
    public static boolean isStandalone(final String artifact) {
        return STANDALONE_JARS.contains(artifact);
    }

    /** The four Minecraft services, in the order the report reads best: proxy first, then backends. */
    public static final List<Service> SERVICES = List.of(
            // The proxy carries voice chat's proxy half; it is optional because this container is the network.
            new Service(PROXY, Kind.VELOCITY, List.of(PROXY, VOICE_CHAT_PROXY), List.of(VOICE_CHAT_PROXY)),
            new Service(LIMBO, Kind.PAPER, List.of(LIMBO)),
            // Voice chat is on the two servers people play on, not on limbo, which holds nobody worth talking to.
            new Service(HUNGER_GAMES, Kind.PAPER, List.of(HUNGER_GAMES, VOICE_CHAT), List.of(VOICE_CHAT)),
            // The only service with required third-party plugins; CoreProtect and voice chat are optional here.
            new Service(
                    SMP,
                    Kind.PAPER,
                    List.of(SMP, DISPLAY_TAGS, PACKETEVENTS, VOICE_CHAT, CORE_PROTECT),
                    List.of(VOICE_CHAT, CORE_PROTECT)));

    /**
     * Whether {@code service} is one of the four with a plugins folder.
     *
     * The service page asks this through {@code hasPlugins} on {@code /api/services/{name}} so that its Plugins tab is
     * drawn or not drawn from one answer, rather than drawn and then taken away again when {@code /plugins} comes back
     * 404.
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
     * The services that have a {@code -standby} counterpart in {@code compose.yml}, in the order a swap uses them.
     *
     * Why this is a list of names and not two more {@link Service} s: because a standby runs the same jars as the
     * service it stands in for, and "the same" has to mean the same file rather than the same version number. A
     * {@code Service} row here would be resolved, planned, reported and downloaded a second time - two rows per
     * artefact in every report, two GitHub calls, and the standing possibility of a standby that came up on a
     * different build than the proxy it replaces because the newest release moved between the two resolves. So
     * nothing resolves for a standby: {@code Standbys} copies the live service's {@code plugins/} across after
     * every apply, and the standby is by construction what the live one was about to become.
     *
     * Only these two: a run that restarts the proxy needs a proxy to hold the players, and a run that restarts the
     * limbo - or the SMP - needs a waiting room that stays up. {@code hunger-games} is explicitly out of it, and
     * the SMP has no standby at all: nobody can play on a second copy of a world.
     */
    public static final List<String> SERVICES_WITH_STANDBY = List.of(PROXY, LIMBO);

    /**
     * The compose service name of {@code service}'s standby.
     *
     * @param service the name of a service, whether or not it actually has one
     * @return that name plus {@link #STANDBY_SUFFIX}
     */
    public static String standbyOf(final String service) {
        return service + STANDBY_SUFFIX;
    }

    /** Every standby compose.yml defines, in the order of {@link #SERVICES_WITH_STANDBY}. */
    public static List<String> standbyNames() {
        return SERVICES_WITH_STANDBY.stream().map(Topology::standbyOf).toList();
    }

    /**
     * The four services with every plugin an admin added folded in.
     *
     * This method is the feature: A plugin picked off Modrinth in the interface cannot land in {@link #SERVICES} - that
     * is a {@code List.of(...)} inside a jar that is already built. So it lands in {@code service_plugin} instead, and
     * this is where the two lists become one. Everything downstream - the resolve, the plan, the report, the install,
     * the swap, the restart - then treats an added plugin exactly like DisplayTags, which is the whole ask: an added
     * plugin rides the update cycle.
     *
     * Every added plugin is {@link Service#optional() optional}, without exception. That is not a shortcut, it is the
     * CoreProtect rule applied to the general case: {@code guarded()} is what {@code EXPECTED_PLUGINS} refuses to start
     * a container without, and a plugin somebody added on a Tuesday must never be able to keep the SMP down because its
     * author has not shipped a build for the next Minecraft drop yet. The fixed rows are guarded because the jar beside
     * them does not work without them; nothing can be true of an arbitrary Modrinth project.
     *
     * A row naming a service that does not exist is ignored rather than refused. Services are renamed, and a
     * leftover row must not be able to fail a resolve for the other three services. It shows up as an added plugin
     * that never installs, which is visible in the interface.
     *
     * A row naming an artefact the fixed list already carries is ignored too. The fixed entry wins: it is the one
     * with a reason behind it, and two rows for one jar would resolve twice and fight over the same file on disk.
     *
     * @param added every row of {@code service_plugin}, from {@code PluginDirectory#all()}
     * @return the same four services, in the same order, each carrying its own extra plugins
     */
    public static List<Service> servicesWith(
            final java.util.Collection<eu.nordtal.s2.common.plugin.ManagedPlugin> added) {
        if (added.isEmpty()) {
            return SERVICES;
        }
        final List<Service> merged = new java.util.ArrayList<>(SERVICES.size());
        for (final Service service : SERVICES) {
            final List<String> plugins = new java.util.ArrayList<>(service.plugins());
            final List<String> optional = new java.util.ArrayList<>(service.optional());
            for (final eu.nordtal.s2.common.plugin.ManagedPlugin plugin : added) {
                if (!plugin.service().equals(service.name())) {
                    continue;
                }
                final String artifact = addedArtifact(plugin.artifact(), service.kind());
                if (plugins.contains(artifact)) {
                    continue;
                }
                plugins.add(artifact);
                optional.add(artifact);
            }
            merged.add(
                    plugins.size() == service.plugins().size()
                            ? service
                            : new Service(service.name(), service.kind(), plugins, optional));
        }
        return List.copyOf(merged);
    }

    /**
     * The artefact id an added plugin resolves under on a service of this kind.
     *
     * Why the loader is in the id at all: because one Modrinth project can be two jars that move separately, and
     * the network already runs such a project: Simple Voice Chat is {@link #VOICE_CHAT} on the backends and
     * {@link #VOICE_CHAT_PROXY} on the proxy, one project id asked twice with a different loader. The resolver
     * keys what it found by artefact id, so the same slug added on {@code smp} and on {@code proxy} would
     * otherwise be one entry holding whichever jar was resolved last - a Velocity plugin in a Paper plugins
     * folder, or the other way round.
     *
     * The suffix is spelled exactly as the hand-written constant above spells it, so that the two halves of a
     * project look the same in a report whether they were written in Java or picked in a browser.
     */
    public static String addedArtifact(final String slug, final Kind kind) {
        return kind == Kind.VELOCITY ? slug + "-velocity" : slug;
    }

    private Topology() {}
}

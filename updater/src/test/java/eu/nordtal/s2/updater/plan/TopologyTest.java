package eu.nordtal.s2.updater.plan;

import eu.nordtal.s2.updater.config.UpdaterSpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Topology} and {@code compose.yml} are two copies of one fact. This is the test that
 * makes the second copy fail loudly instead of quietly.
 * <p>
 * It reads the real compose file rather than a fixture, on purpose: a fixture would be a third
 * copy, and the whole point is that there are only ever two and they agree. The file is found by
 * walking up from the module directory, so it does not depend on where Gradle puts the working
 * directory.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopologyTest {

    private final Map<String, Object> services = readComposeServices();

    @Test
    @DisplayName("every service in the topology is a service in compose.yml, with the same server kind")
    void servicesMatch() {
        for (final Topology.Service service : Topology.SERVICES) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> defined = (Map<String, Object>) services.get(service.name());
            assertNotNull(defined, "compose.yml has no service '" + service.name() + "'");

            @SuppressWarnings("unchecked")
            final Map<String, Object> environment = (Map<String, Object>) defined.get("environment");
            assertNotNull(environment, service.name() + " has no environment block");

            assertEquals(service.kind().fillProject(), String.valueOf(environment.get("SERVER_KIND")),
                    service.name() + " runs a different server than the topology says");
        }
    }

    @Test
    @DisplayName("every plugin the topology gives a service is one that service's guard asks for")
    void theEntrypointGuardAsksForEveryPlugin() {
        // The entrypoint refuses to start on a plugins folder that is missing any of these, which
        // is what finding B4 needed: a folder holding SOME of a server's jars looked exactly like a
        // healthy one, because the old guard only counted them.
        //
        // Counts rather than names, deliberately. For our own four jars the artefact id IS the
        // filename prefix, and those are asserted by name below; for the third-party three it is
        // not - `packetevents` resolves to packetevents-spigot-*.jar and `chunky` to
        // Chunky-Bukkit-*.jar - and Topology exists partly to avoid assuming that mapping. What has
        // to hold is that adding a plugin to a service here cannot be forgotten there.
        for (final Topology.Service service : Topology.SERVICES) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> defined = (Map<String, Object>) services.get(service.name());
            assertNotNull(defined, "compose.yml has no service '" + service.name() + "'");
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment = (Map<String, Object>) defined.get("environment");

            final Object raw = environment.get("EXPECTED_PLUGINS");
            assertNotNull(raw, service.name() + " has no EXPECTED_PLUGINS, so its entrypoint falls"
                    + " back to 'the folder is not empty' - the check that let an SMP with no season"
                    + " on it start and report healthy");

            final List<String> expected = List.of(defaultOf(String.valueOf(raw)).split("\\s+"));
            assertEquals(service.guarded().size(), expected.size(),
                    service.name() + " runs " + service.plugins() + " (of which " + service.optional()
                            + " is optional) but its guard asks for " + expected + ". A plugin added"
                            + " to the topology and not to compose.yml is one the container will"
                            + " happily start without.");
            assertTrue(expected.contains(service.name()),
                    service.name() + "'s own season jar is not in its EXPECTED_PLUGINS: " + expected);
        }
    }

    @Test
    @DisplayName("an artefact that may have no build for this version is not one the guard demands")
    void anOptionalPluginIsNotGuarded() {
        // The other direction of the test above, and the reason Service#optional exists at all.
        // EXPECTED_PLUGINS is a list of jars the container REFUSES TO START WITHOUT. Pointing it at
        // an artefact whose publisher has not built for this Minecraft version would hand somebody
        // else's release schedule the power to keep the SMP down - and there is one such artefact
        // today: CoreProtect's newest release, 24.0, stops at 26.1.2 (checked 2026-09-08).
        //
        // It stays in the plan while it waits, which is the half worth having: the run that follows
        // the day a build appears installs it, and nobody has to remember to add it back.
        final Topology.Service smp = Topology.SERVICES.stream()
                .filter(service -> service.name().equals(Topology.SMP))
                .findFirst()
                .orElseThrow();

        assertTrue(smp.plugins().contains(Topology.CORE_PROTECT),
                "smp no longer carries a CoreProtect row - if that was deliberate, this test and"
                        + " the artefact go together");
        assertTrue(smp.optional().contains(Topology.CORE_PROTECT),
                "CoreProtect is guarded again. Until a 26.2 build exists that is an SMP that will"
                        + " not start, every start, for a reason nobody here can act on.");
        assertFalse(smp.guarded().contains(Topology.CORE_PROTECT), "guarded() ignores optional()");

        // And it really is absent from the string an operator would edit, not merely absent from a
        // count. `${file%-*.jar}` on CoreProtect-CE-24.0.jar is CoreProtect-CE, so that - and not
        // the artefact id - is what a guard entry for it would look like.
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment =
                (Map<String, Object>) ((Map<String, Object>) services.get(Topology.SMP))
                        .get("environment");
        final String guard = defaultOf(String.valueOf(environment.get("EXPECTED_PLUGINS")));
        assertFalse(guard.toLowerCase(java.util.Locale.ROOT).contains("coreprotect"),
                "smp's EXPECTED_PLUGINS asks for CoreProtect: " + guard);
    }

    @Test
    @DisplayName("voice chat is one UDP port, on the proxy, and no backend publishes one")
    void voiceChatIsOneUdpPortOnTheProxy() {
        // This replaced a per-backend rule on 2026-09-09, and the two are worth contrasting because
        // the earlier one described a working arrangement too. Without Simple Voice Chat's Velocity
        // plugin, audio never touches the proxy: every backend publishes its own UDP port, each
        // needs a different number, each needs voice_host set by hand, and every one of those ports
        // has to be open to the internet. With the plugin the proxy detects each backend's voice
        // address and port itself and forwards to it over the internal network, so the whole of
        // what the outside world needs is ONE port (Simple Voice Chat wiki, "Proxy Setup" and
        // "Proxy Config File", read 2026-09-09).
        //
        // What this asserts is therefore the shape of the second arrangement, and the failure it
        // catches is a partial return to the first: a backend that grows a UDP port again is one
        // whose audio is expected to arrive somewhere the proxy is not looking.
        final List<String> proxyUdp = udpPorts(Topology.NETWORK_CONTROL);
        assertEquals(1, proxyUdp.size(), "the proxy publishes " + proxyUdp + " UDP. Voice chat needs"
                + " exactly one, because voicechat-proxy.properties ships port: -1 and therefore"
                + " binds the proxy's own port.");

        // "${PROXY_BIND:-0.0.0.0}:25565:25565/udp"
        final String mapping = proxyUdp.getFirst();
        final List<String> parts = fields(mapping.substring(0, mapping.length() - "/udp".length()));
        assertEquals(3, parts.size(), mapping + " is not bind:host:container");
        assertEquals(parts.get(1), parts.get(2), "the proxy maps UDP " + parts.get(1) + " to "
                + parts.get(2) + ". Simple Voice Chat hands the client the port it is bound to"
                + " INSIDE the container, so a remapped port answers the handshake and then times"
                + " out every packet after it.");

        // The same bind as the Minecraft port and the same number the TCP line ends on: the voice
        // endpoint is the Minecraft endpoint with a different protocol, and if those two ever
        // separate the client is told to talk to a port compose does not publish.
        final List<String> tcp = ports(Topology.NETWORK_CONTROL).stream()
                .filter(port -> !port.endsWith("/udp"))
                .toList();
        assertEquals(1, tcp.size(), "the proxy publishes " + tcp + " TCP");
        final List<String> tcpParts = fields(tcp.getFirst());
        assertEquals(tcpParts.getFirst(), parts.getFirst(), "voice is bound to " + parts.getFirst()
                + " and Minecraft to " + tcpParts.getFirst() + ". One endpoint, one address.");
        assertEquals(tcpParts.get(2), parts.get(2), "Velocity listens on " + tcpParts.get(2)
                + " inside the container and voice chat is published from " + parts.get(2)
                + ". port: -1 means they are the same port, so these cannot differ.");

        // And nobody else has one. A backend publishing UDP is either the old arrangement half
        // restored, or a port left behind by a plugin that moved.
        for (final Topology.Service service : Topology.SERVICES) {
            if (service.name().equals(Topology.NETWORK_CONTROL)) {
                continue;
            }
            assertEquals(List.of(), udpPorts(service.name()), service.name() + " publishes a UDP"
                    + " port. With voice chat's proxy plugin installed the backends are reached"
                    + " over the compose network and publish nothing; a port here is either a"
                    + " leftover or a second, disagreeing arrangement.");
        }
    }

    @Test
    @DisplayName("the proxy runs voice chat's proxy half, and it is not one the proxy refuses to start without")
    void theProxyVoicePluginIsOptional() {
        // The rule is Service#optional, and the reason here is stronger than anywhere else it is
        // used: this container is the network. voicechat-velocity is resolved from a pre-release
        // (Modrinth has never published a Velocity release of it), and a plugin on that footing is
        // exactly the one whose next version may fail to resolve or fail to load. Guarding on it
        // would turn that into a proxy that will not start - which is nobody being able to play,
        // for a feature that is optional for a player in the first place.
        final Topology.Service proxy = Topology.SERVICES.stream()
                .filter(service -> service.name().equals(Topology.NETWORK_CONTROL))
                .findFirst()
                .orElseThrow();

        assertTrue(proxy.plugins().contains(Topology.VOICE_CHAT_PROXY),
                "the proxy carries no voicechat-velocity row - without it every backend needs its"
                        + " own public UDP port back, and compose.yml publishes none");
        assertTrue(proxy.optional().contains(Topology.VOICE_CHAT_PROXY),
                "voicechat-velocity is guarded again");
        assertFalse(proxy.guarded().contains(Topology.VOICE_CHAT_PROXY), "guarded() ignores optional()");

        @SuppressWarnings("unchecked")
        final Map<String, Object> environment =
                (Map<String, Object>) ((Map<String, Object>) services.get(Topology.NETWORK_CONTROL))
                        .get("environment");
        final String guard = defaultOf(String.valueOf(environment.get("EXPECTED_PLUGINS")));
        assertFalse(guard.toLowerCase(java.util.Locale.ROOT).contains("voicechat"),
                "the proxy's EXPECTED_PLUGINS asks for voice chat: " + guard);
    }

    @Test
    @DisplayName("neither backend refuses to start over a missing voice chat jar")
    void voiceChatIsOptionalOnTheBackends() {
        // Owner, 2026-09-09, and it reverses what was built the day before. Voice chat is optional
        // for a player - the audio needs a client mod - so a missing jar costs a quiet evening,
        // while a guard entry for it costs the server. The two are not close.
        for (final String name : List.of(Topology.SMP, Topology.HUNGER_GAMES)) {
            final Topology.Service service = Topology.SERVICES.stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst()
                    .orElseThrow();

            assertTrue(service.plugins().contains(Topology.VOICE_CHAT),
                    name + " no longer runs voice chat at all");
            assertFalse(service.guarded().contains(Topology.VOICE_CHAT),
                    name + " refuses to start without voice chat");

            @SuppressWarnings("unchecked")
            final Map<String, Object> environment =
                    (Map<String, Object>) ((Map<String, Object>) services.get(name)).get("environment");
            // `${file%-*.jar}` on voicechat-bukkit-2.6.23.jar is voicechat-bukkit, so that - not
            // the artefact id - is what a guard entry for it would look like.
            final String guard = defaultOf(String.valueOf(environment.get("EXPECTED_PLUGINS")));
            assertFalse(guard.toLowerCase(java.util.Locale.ROOT).contains("voicechat"),
                    name + "'s EXPECTED_PLUGINS asks for voice chat: " + guard);
        }
    }

    /** Every published port of a compose service, as written. */
    private List<String> ports(final String service) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> defined = (Map<String, Object>) services.get(service);
        assertNotNull(defined, "compose.yml has no service '" + service + "'");
        @SuppressWarnings("unchecked")
        final List<Object> ports = (List<Object>) defined.get("ports");
        return ports == null ? List.of() : ports.stream().map(String::valueOf).toList();
    }

    private List<String> udpPorts(final String service) {
        return ports(service).stream().filter(port -> port.endsWith("/udp")).toList();
    }

    @Test
    @DisplayName("one player number, on the proxy and on every Paper backend")
    void oneNumberLimitsTheNetwork() {
        // This test has now asserted three different things, and the two it used to assert are why
        // it is worth reading rather than trusting. First: that all three backends carried the SAME
        // limit - because whichever one a player landed on decided, and the smallest of them was the
        // network's real limit. Before that, nothing set a limit at all, so Paper's default of 20
        // stood while the browser advertised 500 and the 21st player was refused with "Server full"
        // AFTER passing the login gate, accepting the resource pack and waiting in limbo. Then, from
        // 2026-09-03: that the backends carried a number DELIBERATELY UNRELATED to the network's, set
        // out of reach so only the proxy ever refused anybody.
        //
        // That last one was safe and still wrong, because the backends' number is the one every
        // screen ON a backend can reach: Bukkit.getMaxPlayers() is what a tab list has, so the
        // browser advertised 500 while the tab list said 3/1000. Since 2026-09-04 there is one
        // number - NETWORK_MAX_PLAYERS - and this test exists to keep it one. The admins the proxy
        // lets past a full network are let past the backends by the plugins themselves; see
        // common's FullServerAdmission.
        final List<String> limits = new java.util.ArrayList<>();
        for (final Topology.Service service : Topology.SERVICES) {
            if (service.kind() != Topology.Kind.PAPER) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> defined = (Map<String, Object>) services.get(service.name());
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment = (Map<String, Object>) defined.get("environment");

            assertNull(environment.get("BACKEND_MAX_PLAYERS"), service.name() + " sets"
                    + " BACKEND_MAX_PLAYERS again. The entrypoint no longer reads it, so this is"
                    + " either dead or - worse - a second player number, which is what made a"
                    + " backend advertise 3/1000 under a browser promising 500.");

            final Object raw = environment.get("MAX_PLAYERS");
            assertNotNull(raw, service.name() + " sets no MAX_PLAYERS, so it keeps Paper's default"
                    + " of 20 and refuses the 21st player after the login gate");
            limits.add(String.valueOf(raw));
        }
        assertEquals(1, new LinkedHashSet<>(limits).size(),
                "the Paper backends are configured from different values: " + limits
                        + ". They are supposed to be one number, and the smallest of them is the"
                        + " one that would be hit first.");

        @SuppressWarnings("unchecked")
        final Map<String, Object> proxy = (Map<String, Object>) services.get("network-control");
        @SuppressWarnings("unchecked")
        final Map<String, Object> proxyEnvironment = (Map<String, Object>) proxy.get("environment");
        final Object advertised =
                proxyEnvironment.get("NORDTAL_NETWORK_CONTROL_NETWORK_MAX_PLAYERS");
        assertNotNull(advertised, "the proxy is given no max-players, so network.yml's default"
                + " decides what the browser is told and .env cannot move it");
        assertNull(proxyEnvironment.get("NORDTAL_NETWORK_CONTROL_NETWORK_BACKEND_LIMIT"),
                "the proxy is still given backend-limit. NetworkSpec no longer declares that key,"
                        + " so the overlay never looks the variable up: it would sit in .env"
                        + " reading like the second player limit and moving nothing at all.");

        assertEquals(String.valueOf(advertised), limits.getFirst(),
                "the number the browser advertises and the number the backends run on come from"
                        + " different .env variables: " + advertised + " against " + limits.getFirst()
                        + ". One of them is what a player is promised and the other is what a tab"
                        + " list shows them; two variables is how those came to disagree.");
        assertTrue(String.valueOf(advertised).contains("NETWORK_MAX_PLAYERS"),
                "the one player number is not NETWORK_MAX_PLAYERS any more: " + advertised
                        + ". .env.example, deploy/README.md and NetworkSpec all name it.");
    }

    /**
     * A {@code bind:host:container} mapping split on the colons that separate it - not on the ones
     * inside a {@code ${VAR:-default}}, of which every field here has one.
     */
    private static List<String> fields(final String mapping) {
        final List<String> parts = new java.util.ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < mapping.length(); i++) {
            final char c = mapping.charAt(i);
            if (c == '$' && i + 1 < mapping.length() && mapping.charAt(i + 1) == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == ':' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return List.copyOf(parts);
    }

    /** {@code ${SMP_EXPECTED_PLUGINS:-smp …}} - what compose uses when .env says nothing. */
    private static String defaultOf(final String value) {
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^\\$\\{[A-Z0-9_]+:-(.*)}$").matcher(value);
        assertTrue(matcher.matches(), value + " has no default an unfilled .env would fall back to");
        return matcher.group(1);
    }

    @Test
    @DisplayName("compose.yml does not fetch plugins any more - two owners is one too many")
    void pluginOwnershipStaysWithTheUpdater() {
        // SEASON_PLUGINS and EXTRA_PLUGIN_URLS were removed on 2026-09-01 and must stay removed.
        // entrypoint.sh fetched `<module>-$SEASON_VERSION.jar` and deleted every other version of
        // the same plugin by prefix; an updater that puts 0.3.0 in a volume while .env still says
        // 0.2.0 would have the next restart delete exactly the jar it had just fetched. Re-adding
        // either line brings that collision back, silently, and this is the only place that says so.
        //
        // PACK_URL and PACK_SHA1 went for a different reason: a jcore environment override wins
        // over the file and is never written back, so with them set the updater would be writing a
        // new sha1 into a value nothing reads.
        for (final String forbidden : List.of("SEASON_PLUGINS", "EXTRA_PLUGIN_URLS",
                "NORDTAL_NETWORK_CONTROL_PACK_URL", "NORDTAL_NETWORK_CONTROL_PACK_SHA1")) {
            services.forEach((name, definition) -> {
                @SuppressWarnings("unchecked")
                final Map<String, Object> environment =
                        (Map<String, Object>) ((Map<String, Object>) definition).get("environment");
                if (environment != null) {
                    assertFalse(environment.containsKey(forbidden),
                            "compose.yml sets " + forbidden + " on '" + name + "' again. The updater"
                                    + " owns the jars and the pack now - see docs/updater.md.");
                }
            });
        }
    }

    @Test
    @DisplayName("every service the topology knows has its volume mounted into the updater")
    void theUpdaterCanSeeEveryServer() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> updater = (Map<String, Object>) services.get("updater");
        assertNotNull(updater, "compose.yml has no updater service");

        final String mounts = String.valueOf(updater.get("volumes"));
        for (final Topology.Service service : Topology.SERVICES) {
            // A server whose volume is not mounted reports as "unknown" for ever - which the
            // updater says out loud, but only if somebody reads it. Caught here instead.
            assertTrue(mounts.contains("/volumes/" + service.name()),
                    "the updater service does not mount /volumes/" + service.name()
                            + "; it would report that server as unmounted on every run");
        }
    }

    @Test
    @DisplayName("every server's plugins/ is the same directory for the server and for the updater")
    void thePluginDirectoryIsOneDirectory() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> updater = (Map<String, Object>) services.get("updater");
        final List<String> updaterMounts = mountsOf(updater);

        for (final Topology.Service service : Topology.SERVICES) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> definition = (Map<String, Object>) services.get(service.name());
            assertNotNull(definition, "compose.yml has no " + service.name() + " service");

            final String onTheServer = mountsOf(definition).stream()
                    .filter(mount -> mount.endsWith(":/data/plugins"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(service.name() + " mounts nothing onto"
                            + " /data/plugins. Since 2026-09-05 plugins/ is separate from the"
                            + " server's own volume; without this line the server reads an empty"
                            + " folder and the entrypoint stops the container."));

            final String onTheUpdater = updaterMounts.stream()
                    .filter(mount -> mount.endsWith(":/volumes/" + service.name() + "/plugins"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the updater does not mount "
                            + service.name() + "'s plugins/. It would then install into one place"
                            + " while the server reads another - and nothing would say so:"
                            + " `apply` reports success, the jars are on disk, and no server runs"
                            + " a single one of them."));

            // The two have to be the SAME source, expression for expression. A variable spelt
            // differently in the two places, or one side copying the default rather than the
            // variable, is exactly the silent split above.
            assertEquals(sourceOf(onTheServer), sourceOf(onTheUpdater),
                    service.name() + ": the server and the updater are pointed at two different"
                            + " plugin sources");

            // AND THE DEFAULT HAS TO BE A VOLUME NAME (2026-09-08, finding 151). It was
            // ${SERVERS_ROOT:-./deploy/servers}/<service>/plugins for three days, which put every
            // deployed config.yml, milestones.yml, sounds.yml and pack.yml inside the directory
            // Arcane's GitOps sync pulls - and that sync DELETES IGNORED FILES, so the first sync
            // after a hand edit takes the lot and every server comes back writing fresh defaults.
            //
            // Docker distinguishes a bind mount from a volume by nothing but the shape of the
            // string: anything containing a `/` is a path. A `.` is checked too because that is
            // what a relative path starts with here and what a stray `./` leaves behind.
            final String fallback = defaultOf(sourceOf(onTheServer));
            assertFalse(fallback.contains("/") || fallback.contains("."),
                    service.name() + "'s plugins/ defaults to '" + fallback + "', which Docker"
                            + " reads as a PATH and not as a volume name. Production sets none of"
                            + " these variables, so that default is what the host gets - and a"
                            + " path inside this checkout is deleted by Arcane's GitOps sync with"
                            + " every hand-edited plugin config in it. A local stack opts into the"
                            + " bind by setting the variable; the default must not.");
        }
    }

    /** The host side of a compose mount - everything before the last colon-separated field pair. */
    private static String sourceOf(final String mount) {
        final int split = mount.lastIndexOf(':');
        return mount.substring(0, split);
    }

    @SuppressWarnings("unchecked")
    private static List<String> mountsOf(final Map<String, Object> service) {
        final Object volumes = service.get("volumes");
        assertNotNull(volumes, "service has no volumes block");
        return ((List<Object>) volumes).stream().map(String::valueOf).toList();
    }

    @Test
    @DisplayName("the local env file answers every variable compose.yml requires")
    void theLocalEnvFileIsComplete() throws IOException {
        // COMPOSE INTERPOLATES THE WHOLE FILE BEFORE IT FILTERS BY PROFILE. So one `${X:?}` with no
        // value stops the local stack before an image is pulled, and it does that for services the
        // local selection never starts - every one of the bot's twelve. That is why the example
        // carries obvious placeholders for them rather than nothing, and why this is checked here:
        // the alternative is finding out from a `docker compose up` that refuses to parse.
        final String compose = Files.readString(findUpwards("compose.yml"), StandardCharsets.UTF_8);
        final String env = Files.readString(findUpwards("deploy/dev.env.example"), StandardCharsets.UTF_8);

        final java.util.Set<String> defined = env.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.contains("="))
                .map(line -> line.substring(0, line.indexOf('=')))
                .collect(java.util.stream.Collectors.toSet());

        final java.util.regex.Matcher required =
                java.util.regex.Pattern.compile("\\$\\{([A-Z0-9_]+):\\?").matcher(compose);
        final List<String> missing = new java.util.ArrayList<>();
        while (required.find()) {
            if (!defined.contains(required.group(1))) {
                missing.add(required.group(1));
            }
        }

        assertEquals(List.of(), missing.stream().distinct().sorted().toList(),
                "deploy/dev.env.example does not answer every required variable in compose.yml."
                        + " `deploy/dev up` would fail on the first of them, naming one variable"
                        + " and no others, however many are missing.");
    }

    @Test
    @DisplayName("the bot's and the updater's own volumes are mounted too, or neither could be updated")
    void theUpdaterCanSeeTheTwoStandaloneJars() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> updater = (Map<String, Object>) services.get("updater");
        final String mounts = String.valueOf(updater.get("volumes"));

        for (final String artifact : Topology.STANDALONE_JARS) {
            assertTrue(mounts.contains("/volumes/" + artifact),
                    "the updater does not mount /volumes/" + artifact + ", so it could never move"
                            + " that jar - which is the whole reason both stopped being images");
        }
    }

    @Test
    @DisplayName("the updater is in every profile selection, because everything else depends on it")
    void theUpdaterHasNoProfile() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> updater = (Map<String, Object>) services.get("updater");
        assertFalse(updater.containsKey("profiles"),
                "the updater has a profile again. It applies the schema and answers /update, so a"
                        + " selection without it is a stack that cannot correctly start.");
        assertEquals(List.of("serve"), updater.get("command"),
                "the compose service must run `serve`; every writing mode is asked for by name");
        assertNotNull(updater.get("healthcheck"),
                "without the healthcheck, depends_on: service_healthy on every other service is a"
                        + " dependency on nothing");
    }

    @Test
    @DisplayName("every process that can fail silently reports a readiness marker to its container")
    void everyLongRunningServiceHasAHealthcheck() {
        // FINDING 55. Until 2026-09-04 only `postgres` and `updater` had a healthcheck at all, while
        // CLAUDE.md justified the "a plugin whose config fails to load stops its whole server" rule
        // with the words "the port is open, so the healthcheck passes" - describing a mechanism this
        // file did not contain. The rule was right and the sentence quoted nothing.
        //
        // What has to hold now: the four Minecraft services and the bot each carry a check for the
        // marker their process refreshes, and it is the marker rather than the port that decides,
        // because an open port is exactly what a Paper server with a disabled plugin still has.
        final List<String> named = new java.util.ArrayList<>(List.of("bot"));
        Topology.SERVICES.forEach(service -> named.add(service.name()));

        for (final String name : named) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> service = (Map<String, Object>) services.get(name);
            assertNotNull(service, "compose.yml has no service '" + name + "'");

            @SuppressWarnings("unchecked")
            final Map<String, Object> healthcheck = (Map<String, Object>) service.get("healthcheck");
            assertNotNull(healthcheck, name + " has no healthcheck, so nothing outside its JVM"
                    + " reports anything about it - which is the state finding 55 is about: a"
                    + " container that is up, green by default, and running nothing useful");

            final String test = String.valueOf(healthcheck.get("test"));
            assertTrue(test.contains("/tmp/nordtal-ready"),
                    name + "'s healthcheck does not look at the readiness marker: " + test);
            assertNotNull(healthcheck.get("start_period"), name + " has no start_period, so a"
                    + " perfectly healthy server reports unhealthy while it is still loading");
        }
    }

    @Test
    @DisplayName("the staleness window in compose.yml is still the one Readiness beats to")
    void theStalenessWindowMatchesTheHelper() {
        // Two copies of one number, and they cannot be one: compose.yml's test is a shell command
        // inside a YAML file and can read nothing from Java. `-lt 90` there, STALE_AFTER here. A
        // window shortened below the beat interval would make every healthy container flap; one
        // widened would hide a dead process for longer than anybody reading either file expects.
        final java.util.regex.Pattern window = java.util.regex.Pattern.compile("-lt (\\d+)");
        final List<String> named = new java.util.ArrayList<>(List.of("bot"));
        Topology.SERVICES.forEach(service -> named.add(service.name()));

        for (final String name : named) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> service = (Map<String, Object>) services.get(name);
            @SuppressWarnings("unchecked")
            final Map<String, Object> healthcheck = (Map<String, Object>) service.get("healthcheck");
            assertNotNull(healthcheck, name + " has no healthcheck at all - see the case above");
            final java.util.regex.Matcher matcher = window.matcher(String.valueOf(healthcheck.get("test")));

            assertTrue(matcher.find(), name + "'s healthcheck no longer compares the marker's age"
                    + " against a window: " + healthcheck.get("test"));
            assertEquals(eu.nordtal.s2.common.health.Readiness.STALE_AFTER.toSeconds(),
                    Long.parseLong(matcher.group(1)),
                    name + "'s healthcheck window and Readiness.STALE_AFTER disagree");
        }
    }

    @Test
    @DisplayName("the Minecraft services still test the port as well as the marker")
    void theMinecraftServicesKeepTheirPortTest() {
        // The compose healthcheck REPLACES the one in deploy/minecraft/Dockerfile rather than adding
        // to it, so the TCP connect that image carries is repeated in the anchor. Dropping it would
        // trade one blind spot for another: the marker is written at the end of onEnable, which is
        // not the same instant the server starts accepting connections, and "healthy" on these four
        // is supposed to mean "accepts players".
        for (final Topology.Service service : Topology.SERVICES) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> defined = (Map<String, Object>) services.get(service.name());
            @SuppressWarnings("unchecked")
            final Map<String, Object> healthcheck = (Map<String, Object>) defined.get("healthcheck");
            assertNotNull(healthcheck, service.name() + " has no healthcheck at all - see above");
            final String test = String.valueOf(healthcheck.get("test"));

            assertTrue(test.contains("/dev/tcp/"), service.name() + " no longer connects to its own"
                    + " port, so a server that has stopped accepting players reports healthy: " + test);
            assertTrue(test.contains("bash"), service.name() + "'s healthcheck does not run under"
                    + " bash. /bin/sh in that image is dash, which has no /dev/tcp, so the port half"
                    + " would fail on every check: " + test);
        }
    }

    @Test
    @DisplayName("every service that reads the database waits for the schema")
    void everythingWaitsForTheUpdater() {
        services.forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> service = (Map<String, Object>) definition;
            // postgres is the database; postgres-backup only ever talks to it through pg_dump;
            // pack-host is an nginx serving one zip on loopback under a development-only profile
            // and reads nothing at all. None of the three can be out of step with a schema.
            if (name.equals("updater") || name.equals("postgres") || name.equals("postgres-backup")
                    || name.equals("pack-host")) {
                return;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> dependsOn = (Map<String, Object>) service.get("depends_on");
            assertNotNull(dependsOn, name + " does not wait for the updater, so it can come up"
                    + " against a schema older than itself after a redeploy");
            assertTrue(String.valueOf(dependsOn).contains("service_healthy"),
                    name + " depends on the updater but not on it being healthy, which waits for"
                            + " the container to exist rather than for the schema to be current");
        });
    }

    @Test
    @DisplayName("the two Arcane defaults repeated in compose.yml still match the spec's own")
    void theArcaneDefaultsAgreeWithTheSpec() {
        // They have to be repeated: an environment variable set to the empty string still wins
        // over the file in jcore's config system, so `${VAR:-}` in compose would blank out the
        // spec's default rather than fall back to it. Two copies, and this is the test that stops
        // them drifting.
        @SuppressWarnings("unchecked")
        final Map<String, Object> updater = (Map<String, Object>) services.get("updater");
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment = (Map<String, Object>) updater.get("environment");

        final UpdaterSpec.ArcaneSpec spec = new UpdaterSpec.ArcaneSpec() {
        };
        assertTrue(String.valueOf(environment.get("NORDTAL_UPDATER_ARCANE_ENVIRONMENT"))
                        .endsWith(":-" + spec.environment() + "}"),
                "compose.yml's ARCANE_ENVIRONMENT fallback is not '" + spec.environment()
                        + "' any more");
        assertTrue(String.valueOf(environment.get("NORDTAL_UPDATER_ARCANE_REDEPLOY_PATH"))
                        .endsWith(":-" + spec.redeployPath() + "}"),
                "compose.yml's ARCANE_REDEPLOY_PATH fallback is not '" + spec.redeployPath()
                        + "' any more");
    }

    @Test
    @DisplayName("every volume a backup saves is a volume compose.yml declares, prefix included")
    void theBackupNamesRealVolumes() {
        // TWO COPIES OF ONE FACT, the same shape Topology and compose.yml already are. Arcane
        // addresses a volume by its REAL Docker name, which is compose's `name:` plus an
        // underscore plus the key under `volumes:` - so a volume renamed here and not there is a
        // 404 from Arcane on the one night it matters, and a run that reports a failed backup for
        // a volume that has not existed for weeks.
        //
        // A 404 is the GOOD version of getting this wrong. The bad one is a typo that happens to
        // name a volume Docker will simply CREATE on first use: Arcane would snapshot an empty
        // directory and report success for ever.
        final String project = composeProject();
        final Set<String> declared = composeVolumes();

        for (final String volume : defaults().backup().volumes()) {
            assertTrue(volume.startsWith(project + "_"),
                    "backup.volumes lists '" + volume + "', which does not start with compose's own"
                            + " project name '" + project + "_'. Docker prefixes every volume in a"
                            + " compose project, and Arcane only knows the prefixed name.");
            final String key = volume.substring(project.length() + 1);
            assertTrue(declared.contains(key),
                    "backup.volumes lists '" + volume + "', but compose.yml declares no volume '"
                            + key + "'. Docker creates a volume it has never seen on first use, so"
                            + " this would snapshot an empty directory and report success.");
        }
    }

    @Test
    @DisplayName("every service a backup stops is a service compose.yml runs")
    void theBackupStopsRealServices() {
        // A name Arcane does not list is reported as "Arcane does not list a container for this
        // service" and aborts the run before anything is saved - which is the right direction to
        // fail in, and still an outage for nothing at a quarter to five in the morning.
        for (final String service : defaults().backup().stopServices()) {
            assertNotNull(services.get(service), "backup.stop-services names '" + service
                    + "', which is not a service in compose.yml. The run would stop nothing, save"
                    + " nothing and report a failure.");
        }
    }

    /** The compose project name, which is the prefix Docker puts on every volume in it. */
    private static String composeProject() {
        final Path compose = findUpwards("compose.yml");
        try (Reader reader = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) new Yaml().load(reader);
            final Object name = root.get("name");
            assertNotNull(name, "compose.yml has no top-level name:, so the volume prefix is the"
                    + " directory name and depends on where somebody cloned this repository");
            return String.valueOf(name);
        } catch (final IOException unreadable) {
            throw new IllegalStateException("could not read " + compose, unreadable);
        }
    }

    /** The keys under compose.yml's top-level {@code volumes:} block. */
    private static Set<String> composeVolumes() {
        final Path compose = findUpwards("compose.yml");
        try (Reader reader = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) new Yaml().load(reader);
            @SuppressWarnings("unchecked")
            final Map<String, Object> volumes = (Map<String, Object>) root.get("volumes");
            assertNotNull(volumes, compose + " has no volumes block");
            return new LinkedHashSet<>(volumes.keySet());
        } catch (final IOException unreadable) {
            throw new IllegalStateException("could not read " + compose, unreadable);
        }
    }

    /** {@link UpdaterSpec} answering nothing but its own defaults. */
    private static UpdaterSpec defaults() {
        return new UpdaterSpec() {
            @Override
            public BackupSpec backup() {
                return new BackupSpec() {
                };
            }

            @Override
            public ArcaneSpec arcane() {
                return new ArcaneSpec() {
                };
            }
        };
    }

    @Test
    @DisplayName("the bootstrap default repeated in compose.yml still matches the spec's own")
    void theBootstrapDefaultAgreesWithTheSpec() {
        // Same reason as the two Arcane defaults above: an empty environment variable wins over the
        // file, so the fallback has to say what the spec says rather than nothing.
        @SuppressWarnings("unchecked")
        final Map<String, Object> updater = (Map<String, Object>) services.get("updater");
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment = (Map<String, Object>) updater.get("environment");

        // arcane() is the one member of UpdaterSpec without a default, so it has to be supplied
        // even though this test only reads bootstrap().
        final UpdaterSpec spec = new UpdaterSpec() {
            @Override
            public BackupSpec backup() {
                // Defaults throughout: this test is not about a backup, and BackupSpec's own
                // defaults are the production ones.
                return new BackupSpec() {
                };
            }

            @Override
            public ArcaneSpec arcane() {
                return new ArcaneSpec() {
                };
            }
        };
        assertTrue(String.valueOf(environment.get("NORDTAL_UPDATER_BOOTSTRAP"))
                        .endsWith(":-" + spec.bootstrap() + "}"),
                "compose.yml's UPDATER_BOOTSTRAP fallback is not '" + spec.bootstrap()
                        + "' any more. With it off, a first deployment cannot come up without"
                        + " somebody running `updater apply` on the host.");
    }

    @Test
    @DisplayName("every image of ours defaults to one the release workflow actually pushes")
    void ourImagesArePulledAndNotInventedLocally() {
        // THIS IS THE TEST FOR A REAL OUTAGE. compose.yml defaulted the Minecraft image to
        // `ghcr.io/nordtal/minecraft:local`, a tag nothing has ever pushed, on the assumption that
        // the host would build it. Arcane deploys by PULLING - its Redeploy never builds - so the
        // deploy failed with `denied` from the registry, which is also what a private package
        // answers and therefore explains nothing. A `build:` block next to it made it look fine.
        //
        // The rule this pins down: if an image is ours, its DEFAULT must be a ghcr.io/nordtal
        // reference tagged from IMAGE_TAG, because that is exactly what release.yml publishes.
        services.forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> service = (Map<String, Object>) definition;
            final String image = String.valueOf(service.get("image"));
            if (!image.contains("nordtal/")) {
                return;
            }
            assertTrue(image.contains(":-ghcr.io/nordtal/"),
                    "compose.yml's '" + name + "' defaults to the image " + image + ", which is not"
                            + " a ghcr.io/nordtal reference. Arcane pulls and never builds, so an"
                            + " image only this host can produce fails the deploy with `denied`.");
            assertTrue(image.contains("${IMAGE_TAG:-latest}"),
                    "compose.yml's '" + name + "' does not take its tag from IMAGE_TAG. One variable"
                            + " pins every image for a rollback; a second way to spell it is a way"
                            + " for two of them to disagree.");
        });
    }

    @Test
    @DisplayName("DisplayTags really is required by smp, which is why the topology lists it")
    void theRequiredPluginsAreRequiredBySmpsOwnManifest() throws IOException {
        // The topology's smp row is not a preference. Checked against the manifest that enforces
        // it rather than against a comment about it.
        final Path manifest = findUpwards("smp/src/main/resources/paper-plugin.yml");
        final String text = Files.readString(manifest, StandardCharsets.UTF_8);

        assertTrue(text.contains("DisplayTags"), manifest + " no longer names DisplayTags");
        assertTrue(text.contains("required: true"), manifest + " no longer requires it");
        assertTrue(smpPlugins().contains(Topology.DISPLAY_TAGS),
                "smp requires DisplayTags but Topology does not list it, so the updater would"
                        + " never install it");
    }

    @Test
    @DisplayName("no Minecraft service exists in compose.yml that the topology does not know about")
    void nothingIsMissedOut() {
        // The direction that actually catches a fifth backend server: adding one to compose.yml
        // without adding it here would otherwise mean an updater that quietly never touches it.
        final Set<String> known = new LinkedHashSet<>();
        Topology.SERVICES.forEach(service -> known.add(service.name()));

        services.forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment =
                    (Map<String, Object>) ((Map<String, Object>) definition).get("environment");
            if (environment != null && environment.containsKey("SERVER_KIND")) {
                assertTrue(known.contains(name),
                        "compose.yml runs a Minecraft service '" + name + "' that Topology does not know."
                                + " Add it to Topology.SERVICES - the updater will not touch it otherwise.");
            }
        });
    }

    private static java.util.List<String> smpPlugins() {
        return Topology.SERVICES.stream()
                .filter(service -> service.name().equals(Topology.SMP))
                .findFirst()
                .orElseThrow()
                .plugins();
    }

    private static Map<String, Object> readComposeServices() {
        final Path compose = findUpwards("compose.yml");
        try (Reader reader = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            final Object loaded = new Yaml().load(reader);
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) loaded;
            @SuppressWarnings("unchecked")
            final Map<String, Object> services = (Map<String, Object>) root.get("services");
            assertNotNull(services, compose + " has no services block");
            return services;
        } catch (final IOException unreadable) {
            throw new IllegalStateException("could not read " + compose, unreadable);
        }
    }

    private static Path findUpwards(final String relative) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            final Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("could not find " + relative + " above " + Path.of("").toAbsolutePath());
    }
}

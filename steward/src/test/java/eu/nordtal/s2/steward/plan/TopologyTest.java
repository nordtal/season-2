package eu.nordtal.s2.steward.plan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.Platform;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.steward.config.BackupSpec;
import eu.nordtal.s2.steward.config.StewardSpec;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.yaml.snakeyaml.Yaml;

/**
 * Makes a fact {@link Topology} and {@code compose.yml} both hold fail loudly when the two copies drift.
 *
 * It reads the real compose file, since a fixture would be a third copy.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopologyTest {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Map<String, Object> services = readComposeServices();

    @Test
    void everyServiceInTheTopologyIsAServiceInComposeYmlWithTheSameServerKind() {
        for (final Topology.Service service : Topology.SERVICES) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> defined = (Map<String, Object>) services.get(service.name());
            assertNotNull(defined, "compose.yml has no service '" + service.name() + "'");

            @SuppressWarnings("unchecked")
            final Map<String, Object> environment = (Map<String, Object>) defined.get("environment");
            assertNotNull(environment, service.name() + " has no environment block");

            assertEquals(
                    service.kind().fillProject(),
                    String.valueOf(environment.get("SERVER_KIND")),
                    service.name() + " runs a different server than the topology says");
        }
    }

    @Test
    void everyPluginTheTopologyGivesAServiceIsOneThatServicesGuardAsksFor() {
        // Counts, not names: an artefact id is not its filename prefix, so this only checks the plugin is asked for.
        for (final Topology.Service service : Topology.SERVICES) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> defined = (Map<String, Object>) services.get(service.name());
            assertNotNull(defined, "compose.yml has no service '" + service.name() + "'");
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment = (Map<String, Object>) defined.get("environment");

            final Object raw = environment.get("EXPECTED_PLUGINS");
            assertNotNull(
                    raw,
                    service.name() + " has no EXPECTED_PLUGINS, so its entrypoint falls"
                            + " back to 'the folder is not empty' - the check that let an SMP with no season"
                            + " on it start and report healthy");

            final List<String> expected =
                    WHITESPACE.splitAsStream(defaultOf(String.valueOf(raw))).toList();
            assertEquals(
                    service.guarded().size(),
                    expected.size(),
                    service.name() + " runs " + service.plugins() + " (of which " + service.optional()
                            + " is optional) but its guard asks for " + expected + ". A plugin added"
                            + " to the topology and not to compose.yml is one the container will"
                            + " happily start without.");
            assertTrue(
                    expected.contains(service.name()),
                    service.name() + "'s own season jar is not in its EXPECTED_PLUGINS: " + expected);
        }
    }

    @Test
    void anArtefactThatMayHaveNoBuildForThisVersionIsNotOneTheGuardDemands() {
        // Service#optional exists so an artefact with no build for this version cannot keep the SMP down.
        final Topology.Service smp = Topology.SERVICES.stream()
                .filter(service -> service.name().equals(Topology.SMP))
                .findFirst()
                .orElseThrow();

        assertTrue(
                smp.plugins().contains(Topology.CORE_PROTECT),
                "smp no longer carries a CoreProtect row - if that was deliberate, this test and"
                        + " the artefact go together");
        assertTrue(
                smp.optional().contains(Topology.CORE_PROTECT),
                "CoreProtect is guarded again. Until a 26.2 build exists that is an SMP that will"
                        + " not start, every start, for a reason nobody here can act on.");
        assertFalse(smp.guarded().contains(Topology.CORE_PROTECT), "guarded() ignores optional()");

        // Checked against the guard string itself, not just a count: `${file%-*.jar}` is what an entry would read.
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment =
                (Map<String, Object>) ((Map<String, Object>) services.get(Topology.SMP)).get("environment");
        final String guard = defaultOf(String.valueOf(environment.get("EXPECTED_PLUGINS")));
        assertFalse(
                guard.toLowerCase(java.util.Locale.ROOT).contains("coreprotect"),
                "smp's EXPECTED_PLUGINS asks for CoreProtect: " + guard);
    }

    @Test
    void voiceChatIsOneUdpPortOnTheGuardAndNoServiceOfTheNetworkPublishesOne() {
        // The proxy detects each backend's voice address and forwards it, so only the guard needs to publish it.
        for (final Topology.Service service : Topology.SERVICES) {
            assertEquals(
                    List.of(),
                    ports(service.name()),
                    service.name() + " publishes "
                            + ports(service.name()) + ". Nothing in the"
                            + " network is reachable from outside except through caddy - a port here is"
                            + " either a leftover or a second, disagreeing arrangement, and it takes the"
                            + " number away from the guard that needs it.");
        }

        // port: -1 binds whatever port Velocity bound and hands the client that number; a remap breaks it.
        final String voice = udpPorts(GUARD).stream()
                .filter(port -> port.contains(":25565:25565/udp"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(GUARD + " publishes " + udpPorts(GUARD)
                        + " UDP and none of them is 25565 onto 25565. Voice chat needs exactly that"
                        + " one, because the plugin binds the proxy's own port."));
        final List<String> parts = fields(voice.substring(0, voice.length() - "/udp".length()));
        assertEquals(3, parts.size(), voice + " is not bind:host:container");

        // The voice endpoint is the Minecraft endpoint under a different protocol; separated, the client hears no port.
        final String game = ports(GUARD).stream()
                .filter(port -> !port.endsWith("/udp") && port.endsWith(":25565"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(GUARD + " publishes no TCP port onto 25565,"
                        + " so the guard is listening for Minecraft nowhere"));
        final List<String> gameParts = fields(game);
        assertEquals(
                gameParts.getFirst(),
                parts.getFirst(),
                "voice is bound to " + parts.getFirst() + " and Minecraft to " + gameParts.getFirst()
                        + ". One endpoint, one address.");
        assertEquals(
                gameParts.get(2),
                parts.get(2),
                "the guard listens on " + gameParts.get(2)
                        + " for Minecraft and publishes voice from " + parts.get(2)
                        + ". port: -1 means they are the same port, so these cannot differ.");
    }

    /** The guard in front of 25565, deliberately not a {@link Topology} service, so a run never restarts it. */
    private static final String GUARD = "caddy";

    @Test
    void theGuardPrefersTheLiveProxyAndFallsBackToTheStandby() {
        // `first` and the order of the upstreams are the rule: the standby answers only when the live proxy refuses.
        final String caddyfile = configContent("caddyfile");
        assertTrue(
                caddyfile.contains("layer4 {"),
                "the caddy config has no layer4 app any more, so"
                        + " 25565 is published by a container that cannot speak it");
        assertTrue(
                caddyfile.contains("lb_policy first"),
                "the guard no longer prefers one upstream over the other: with any other policy"
                        + " half the players land on the standby while the live proxy is up");
        final int live = caddyfile.indexOf("upstream proxy:25565");
        final int spare = caddyfile.indexOf("upstream proxy-standby:25565");
        assertTrue(live > 0 && spare > 0, "the guard does not name both proxies as upstreams: " + caddyfile);
        assertTrue(
                live < spare,
                "the standby is named before the live proxy, and `first` takes"
                        + " them in order - every player would be parked on the standby");

        // Both halves of the PROXY protocol, which are two files apart and only work together.
        assertTrue(
                caddyfile.contains("proxy_protocol v2"),
                "the guard stopped writing a PROXY header, so Velocity sees the guard's address for"
                        + " every player - and with haproxy-protocol still true it sees nothing at"
                        + " all");
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment =
                (Map<String, Object>) ((Map<String, Object>) services.get(Topology.PROXY)).get("environment");
        assertTrue(
                String.valueOf(environment.get("VELOCITY_HAPROXY")).contains("true"),
                "the proxy is not told to expect a PROXY header (VELOCITY_HAPROXY is "
                        + environment.get("VELOCITY_HAPROXY") + "), while the guard writes one."
                        + " One without the other is a network that answers nobody.");
    }

    @Test
    void theProxyRunsVoiceChatsProxyHalfAndItIsNotOneTheProxyRefusesToStartWithout() {
        // voicechat-velocity is a pre-release; guarding on it lets its next bad build stop the proxy from starting.
        final Topology.Service proxy = Topology.SERVICES.stream()
                .filter(service -> service.name().equals(Topology.PROXY))
                .findFirst()
                .orElseThrow();

        assertTrue(
                proxy.plugins().contains(Topology.VOICE_CHAT_PROXY),
                "the proxy carries no voicechat-velocity row - without it every backend needs its"
                        + " own public UDP port back, and compose.yml publishes none");
        assertTrue(proxy.optional().contains(Topology.VOICE_CHAT_PROXY), "voicechat-velocity is guarded again");
        assertFalse(proxy.guarded().contains(Topology.VOICE_CHAT_PROXY), "guarded() ignores optional()");

        @SuppressWarnings("unchecked")
        final Map<String, Object> environment =
                (Map<String, Object>) ((Map<String, Object>) services.get(Topology.PROXY)).get("environment");
        final String guard = defaultOf(String.valueOf(environment.get("EXPECTED_PLUGINS")));
        assertFalse(
                guard.toLowerCase(java.util.Locale.ROOT).contains("voicechat"),
                "the proxy's EXPECTED_PLUGINS asks for voice chat: " + guard);
    }

    @Test
    void neitherBackendRefusesToStartOverAMissingVoiceChatJar() {
        // Voice chat needs a client mod, so a missing jar costs a quiet evening while a guard entry costs the server.
        for (final String name : List.of(Topology.SMP, Topology.HUNGER_GAMES)) {
            final Topology.Service service = Topology.SERVICES.stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst()
                    .orElseThrow();

            assertTrue(service.plugins().contains(Topology.VOICE_CHAT), name + " no longer runs voice chat at all");
            assertFalse(service.guarded().contains(Topology.VOICE_CHAT), name + " refuses to start without voice chat");

            @SuppressWarnings("unchecked")
            final Map<String, Object> environment =
                    (Map<String, Object>) ((Map<String, Object>) services.get(name)).get("environment");
            // `${file%-*.jar}` on the voicechat jar is what a guard entry would look like.
            final String guard = defaultOf(String.valueOf(environment.get("EXPECTED_PLUGINS")));
            assertFalse(
                    guard.toLowerCase(java.util.Locale.ROOT).contains("voicechat"),
                    name + "'s EXPECTED_PLUGINS asks for voice chat: " + guard);
        }
    }

    @Test
    void theWaitingRoomHasNoVoiceChatAndThatIsHowItStaysSilent() {
        // Simple Voice Chat needs its Bukkit plugin on the server a player stands on; the limbo has never had it.
        final Topology.Service limbo = Topology.SERVICES.stream()
                .filter(candidate -> candidate.name().equals(Topology.LIMBO))
                .findFirst()
                .orElseThrow();

        assertFalse(
                limbo.plugins().contains(Topology.VOICE_CHAT),
                "the limbo carries voice chat, so two people waiting can hear each other");
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
    void noServiceIsGivenAPlayerLimit() {
        // The limit is the network's players setting, which the proxy alone enforces and an admin changes in Steward.
        for (final Map.Entry<String, Object> service : services.entrySet()) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment =
                    (Map<String, Object>) ((Map<String, Object>) service.getValue()).get("environment");
            if (environment == null) {
                continue;
            }
            for (final String variable : environment.keySet()) {
                assertFalse(
                        variable.contains("MAX_PLAYERS"),
                        service.getKey() + " is given " + variable + ", a second player limit beside the network's"
                                + " setting, which no admin sees in Steward and a change there cannot move.");
            }
        }
    }

    /** Splits a {@code bind:host:container} mapping on its separating colons, not those inside a default. */
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

    /** {@code ${SMP_EXPECTED_PLUGINS:-smp …}}, what compose uses when .env says nothing. */
    private static String defaultOf(final String value) {
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^\\$\\{[A-Z0-9_]+:-(.*)}$").matcher(value);
        assertTrue(matcher.matches(), value + " has no default an unfilled .env would fall back to");
        return matcher.group(1);
    }

    @Test
    void theServerVersionInComposeYmlIsTheOneCommonDeclaresAsALiteral() {
        // The literal is asserted, not merely required to exist, because a `${...:-26.2}` would pass a shape check.
        for (final Topology.Service service : Topology.SERVICES) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> defined = (Map<String, Object>) services.get(service.name());
            assertNotNull(defined, "compose.yml has no service '" + service.name() + "'");
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment = (Map<String, Object>) defined.get("environment");

            final Object version = environment.get("SERVER_VERSION");
            assertNotNull(
                    version,
                    service.name() + " sets no SERVER_VERSION, so its entrypoint" + " cannot name the jar it runs");

            // Paper is an exact Minecraft version and the proxy is Fill's name for Velocity's major; see Platform.
            final String expected =
                    "velocity".equals(service.kind().fillProject()) ? Platform.VELOCITY_FAMILY : Platform.MINECRAFT;
            assertEquals(
                    expected,
                    String.valueOf(version),
                    service.name() + "'s SERVER_VERSION is '" + version + "' and eu.nordtal.s2"
                            + ".common.Platform says '" + expected + "'. Those are the version the"
                            + " container runs and the version every plugin in it was compiled"
                            + " against; a deployment where they differ loads no plugins.");
        }

        // steward reads Platform directly, so nothing here should feed it a version.
        @SuppressWarnings("unchecked")
        final Map<String, Object> steward = (Map<String, Object>) services.get("steward");
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment = (Map<String, Object>) steward.get("environment");
        for (final String retired : List.of(
                "NORDTAL_STEWARD_MINECRAFT_VERSION",
                "NORDTAL_STEWARD_VELOCITY_VERSION",
                "NORDTAL_STEWARD_PAPER_BUILD",
                "NORDTAL_STEWARD_VELOCITY_BUILD")) {
            assertNull(
                    environment.get(retired),
                    "compose.yml sets " + retired + " again. The two"
                            + " versions are constants in :common and there is no build pin anywhere -"
                            + " see the comment in StewardSpec where those four keys stood.");
        }
    }

    @Test
    void composeYmlDoesNotFetchPluginsAnyMoreTwoOwnersIsOneTooMany() {
        // entrypoint.sh deletes every other plugin version by prefix; a jcore PACK_SHA1 override is never written back.
        for (final String forbidden :
                List.of("SEASON_PLUGINS", "EXTRA_PLUGIN_URLS", "NORDTAL_PROXY_PACK_URL", "NORDTAL_PROXY_PACK_SHA1")) {
            services.forEach((name, definition) -> {
                @SuppressWarnings("unchecked")
                final Map<String, Object> environment =
                        (Map<String, Object>) ((Map<String, Object>) definition).get("environment");
                if (environment != null) {
                    assertFalse(
                            environment.containsKey(forbidden),
                            "compose.yml sets " + forbidden + " on '" + name + "' again. Steward"
                                    + " owns the jars and the pack now.");
                }
            });
        }
    }

    @Test
    void noServiceIsGivenAnOptionalOverrideOfASettingStewardEdits() {
        // What a service needs before Steward can reach it: secrets, sign-in and where to fetch releases from.
        final Set<String> bootstrap = Set.of(
                "NORDTAL_STEWARD_SEASON_REPO",
                "NORDTAL_STEWARD_GITHUB_TOKEN",
                "NORDTAL_STEWARD_BUNQ_API_KEY",
                "NORDTAL_STEWARD_BUNQ_ACCOUNT_ID",
                "NORDTAL_PROXY_NETWORK_PUBLIC_ADDRESS",
                "NORDTAL_STEWARD_WEB_WEBAUTHN_RELYING_PARTY_ID",
                "NORDTAL_STEWARD_WEB_DISCORD_CLIENT_ID",
                "NORDTAL_STEWARD_WEB_DISCORD_CLIENT_SECRET",
                "NORDTAL_STEWARD_WEB_DISCORD_BOT_TOKEN",
                "NORDTAL_STEWARD_WEB_WEB_PUSH_PUBLIC_KEY",
                "NORDTAL_STEWARD_WEB_WEB_PUSH_PRIVATE_KEY");
        final Pattern optional = Pattern.compile("\\$\\{[A-Z0-9_]+:-}");
        final List<String> overrides = new java.util.ArrayList<>();
        services.forEach((name, definition) -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment =
                    (Map<String, Object>) ((Map<String, Object>) definition).get("environment");
            if (environment == null) {
                return;
            }
            environment.forEach((variable, value) -> {
                if (variable.startsWith("NORDTAL_")
                        && !bootstrap.contains(variable)
                        && optional.matcher(String.valueOf(value)).matches()) {
                    overrides.add(name + ": " + variable);
                }
            });
        });
        assertEquals(
                List.of(),
                overrides,
                "compose.yml passes optional overrides of config keys. A value set in the host's environment"
                        + " file replaces what Steward shows and saves, a list as a whole, and nothing on the page"
                        + " says the edit went nowhere. Settings are made in Steward.");
    }

    @Test
    void everyServiceTheTopologyKnowsHasItsVolumeMountedIntoSteward() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> steward = (Map<String, Object>) services.get("steward");
        assertNotNull(steward, "compose.yml has no steward service");

        final String mounts = String.valueOf(steward.get("volumes"));
        for (final Topology.Service service : Topology.SERVICES) {
            // A server whose volume is not mounted reports as "unknown" for ever. Caught here.
            assertTrue(
                    mounts.contains("/volumes/" + service.name()),
                    "the steward service does not mount /volumes/" + service.name()
                            + "; it would report that server as unmounted on every run");
        }
    }

    /** The volumes {@code backup.volumes} names, asked of the spec so no second list exists. */
    private static final Set<String> BACKED_UP = Set.copyOf(defaults().backup().volumes());

    /** {@link StewardSpec} answering nothing but its own defaults. */
    private static StewardSpec defaults() {
        return new StewardSpec() {
            @Override
            public BunqSpec bunq() {
                // Empty credentials are a valid season: "no bank account". Nothing here asks bunq anything.
                return new BunqSpec() {};
            }

            @Override
            public UpdateSpec update() {
                return new UpdateSpec() {};
            }

            @Override
            public BackupSpec backup() {
                return new BackupSpec() {
                    // backup.remote has no default of its own, so this hands its defaults back by name.
                    @Override
                    public RemoteSpec remote() {
                        return new RemoteSpec() {};
                    }

                    @Override
                    public RetentionSpec retention() {
                        return new RetentionSpec() {};
                    }
                };
            }

            @Override
            public AgentSpec agent() {
                // Defaults: this test never recreates a container.
                return new AgentSpec() {};
            }
        };
    }

    @Test
    void everyServersPluginsIsTheSameDirectoryForTheServerAndForSteward() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> steward = (Map<String, Object>) services.get("steward");
        final List<String> stewardMounts = mountsOf(steward);
        @SuppressWarnings("unchecked")
        final List<String> agentMounts = mountsOf((Map<String, Object>) services.get(AgentWire.SERVICE));

        for (final Topology.Service service : Topology.SERVICES) {
            assertPluginsDirectoryIsShared(service, stewardMounts, agentMounts);
        }
    }

    private void assertPluginsDirectoryIsShared(
            final Topology.Service service, final List<String> stewardMounts, final List<String> agentMounts) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> definition = (Map<String, Object>) services.get(service.name());
        assertNotNull(definition, "compose.yml has no " + service.name() + " service");

        final String onTheServer = mountsOf(definition).stream()
                .filter(mount -> mount.endsWith(":/data/plugins"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(service.name() + " mounts nothing onto"
                        + " /data/plugins. plugins/ is separate from the"
                        + " server's own volume; without this line the server reads an empty"
                        + " folder and the entrypoint stops the container."));

        final String onSteward = stewardMounts.stream()
                .filter(mount -> mount.endsWith(":/volumes/" + service.name() + "/plugins"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("steward does not mount "
                        + service.name() + "'s plugins/. It would then install into one place"
                        + " while the server reads another - and nothing would say so:"
                        + " `apply` reports success, the jars are on disk, and no server runs"
                        + " a single one of them."));

        // Compared expression for expression: a variable spelt differently anywhere is a silent split.
        assertEquals(
                sourceOf(onTheServer),
                sourceOf(onSteward),
                service.name() + ": the server and steward are pointed at two different" + " plugin sources");

        assertPluginsBackupMatchesSpec(service, agentMounts, onTheServer);

        // The default is a path under NORDTAL_DIR, not bare and relative, which resolves inside the agent image.
        final String fallback = defaultOf(sourceOf(onTheServer));
        assertTrue(
                fallback.startsWith("${NORDTAL_DIR"),
                service.name() + "'s plugins/ defaults to '" + fallback + "', which does not"
                        + " hang off NORDTAL_DIR. Production sets none of these variables, so"
                        + " that default is what the host gets - and the installation is a"
                        + " directory now, so the default has to be one: an absolute path,"
                        + " built from the one variable deploy/nordtal.sh writes.");
        assertTrue(
                fallback.contains("/"),
                service.name() + "'s plugins/ defaults to '" + fallback + "', which Docker"
                        + " reads as a VOLUME NAME and not as a path - anything without a `/`"
                        + " in it is a volume. Setting one of these variables to a name is"
                        + " still the way back (deploy/dev.env.example does exactly that); the"
                        + " default is not.");
    }

    // Whether a plugins/ volume is saved is backup.volumes' own decision, asked of the spec, not named here.
    private void assertPluginsBackupMatchesSpec(
            final Topology.Service service, final List<String> agentMounts, final String onTheServer) {
        final String backupVolume = "nordtal-s2_mc-" + service.name() + "-plugins";
        final Optional<String> forTheBackup = agentMounts.stream()
                .filter(mount -> mount.endsWith(":/backup-sources/" + backupVolume + ":ro"))
                .findFirst();
        if (BACKED_UP.contains(backupVolume)) {
            assertTrue(
                    forTheBackup.isPresent(),
                    "backup.volumes lists " + backupVolume
                            + " and steward-agent does not mount it, so it is not saved");
            // `:ro` is a third field; sourceOf reads up to the destination, so drop it first.
            final String mount = forTheBackup.orElseThrow();
            assertEquals(
                    sourceOf(onTheServer),
                    sourceOf(mount.substring(0, mount.length() - ":ro".length())),
                    service.name() + ": the backup reads a different plugin source than the" + " server runs from");
        } else {
            assertTrue(
                    forTheBackup.isEmpty(),
                    backupVolume + " is mounted for the backup and"
                            + " backup.volumes does not list it - it would be mounted and never saved");
        }
    }

    @Test
    void theBundlesTheInterfaceShowsAreTheBundlesTheServicesActuallyRead() {
        // Saving a bundle IS the reload, so a volume spelt differently here shows a form and quietly changes nothing.
        @SuppressWarnings("unchecked")
        final Map<String, Object> editor = (Map<String, Object>) services.get("steward");
        assertNotNull(editor, "compose.yml has no steward service");
        final List<String> stewardMounts = mountsOf(editor);

        // The interface shows a bundle under the compose service name, the same name the server owns it under.
        for (final Topology.Service service : Topology.SERVICES) {
            assertServerConfigMatchesInterface(service, stewardMounts);
        }

        // The bot, whose own name is the directory name a bundle's service reports to the browser.
        assertOwnConfigMatchesInterface("discord-bot", stewardMounts);
    }

    private void assertServerConfigMatchesInterface(final Topology.Service service, final List<String> stewardMounts) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> definition = (Map<String, Object>) services.get(service.name());
        final String onTheServer = mountsOf(definition).stream()
                .filter(mount -> mount.endsWith(":/data/plugins"))
                .findFirst()
                .orElseThrow();
        final String onTheInterface = stewardMounts.stream()
                .filter(mount -> mount.endsWith(":/configs/" + service.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("steward mounts nothing at"
                        + " /configs/" + service.name() + ", so that server's messages are in"
                        + " no form at all."
                        + " A volume that is not mounted is not an error to the page - it lists"
                        + " what it finds - so this is invisible from the browser."));

        assertEquals(
                sourceOf(onTheServer),
                sourceOf(onTheInterface),
                service.name() + ": the interface edits one directory and the server reads"
                        + " another. Saving would report success and change nothing.");

        assertFalse(
                onTheInterface.endsWith(":ro"),
                service.name() + "'s bundles are mounted read-only into steward, so the form"
                        + " is drawn and the save fails: every bundle in the stack is editable from the interface.");
    }

    private void assertOwnConfigMatchesInterface(final String each, final List<String> stewardMounts) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> owner = (Map<String, Object>) services.get(each);
        assertNotNull(owner, "compose.yml has no " + each + " service");
        final String onTheOwner = mountsOf(owner).stream()
                .filter(mount -> mount.endsWith(":/app/config"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(each + " mounts nothing at /app/config"));
        final String onTheInterface = stewardMounts.stream()
                .filter(mount -> mount.endsWith(":/configs/" + each))
                .findFirst()
                .orElseThrow(() -> new AssertionError("steward mounts nothing at"
                        + " /configs/" + each + ", so that service has no form in the"
                        + " interface"));
        assertEquals(
                sourceOf(onTheOwner),
                sourceOf(onTheInterface),
                each + ": the interface edits one volume and the service reads another");
    }

    /** The host side of a compose mount, everything before the last colon-separated field pair. */
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

    /** One entry of compose.yml's {@code configs:} block, where the caddy configuration is written inline. */
    private static String configContent(final String name) {
        final Path compose = findUpwards("compose.yml");
        try (Reader reader = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) new Yaml().load(reader);
            @SuppressWarnings("unchecked")
            final Map<String, Object> configs = (Map<String, Object>) root.get("configs");
            assertNotNull(configs, compose + " has no configs block");
            @SuppressWarnings("unchecked")
            final Map<String, Object> one = (Map<String, Object>) configs.get(name);
            assertNotNull(one, compose + " has no config '" + name + "'");
            return String.valueOf(one.get("content"));
        } catch (final IOException unreadable) {
            throw new AssertionError("could not read " + compose, unreadable);
        }
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
        throw new IllegalStateException(
                "could not find " + relative + " above " + Path.of("").toAbsolutePath());
    }

    @Test
    void exactlyTheFourMinecraftServicesHavePluginsAndNothingElseDoes() {
        assertAll(
                () -> assertTrue(Topology.hasPlugins(Topology.SMP)),
                () -> assertTrue(Topology.hasPlugins(Topology.PROXY)),
                () -> assertTrue(Topology.hasPlugins(Topology.LIMBO)),
                () -> assertTrue(Topology.hasPlugins(Topology.HUNGER_GAMES)),
                () -> assertFalse(Topology.hasPlugins("postgres")),
                () -> assertFalse(Topology.hasPlugins(Topology.DISCORD_BOT)),
                () -> assertFalse(Topology.hasPlugins(Topology.standbyOf(Topology.PROXY))));
    }
}

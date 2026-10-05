package eu.nordtal.season.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.ComposeFile;
import eu.nordtal.season.common.Platform;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.topology.DeclaredTopology;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * What compose.yml has to hold together: its labels, the entrypoint's view of them, and the mounts they imply.
 *
 * It reads the real compose file, through the agent's own parser where a label is concerned.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopologyTest {

    private static final AgentWire.Topology TOPOLOGY = DeclaredTopology.topology();

    private static final List<Topology.Service> SERVERS = TOPOLOGY.servers();

    private final ComposeFile compose = ComposeFile.get();

    @Test
    void theEntrypointReadsTheKindAndThePluginsTheAgentReads() {
        // An alias, not a copy: a literal written into the environment again would be a second owner.
        for (final Topology.Service server : SERVERS) {
            assertEntrypointReadsLabels(server.name(), server.name());
        }
        for (final String standby : TOPOLOGY.standbys()) {
            assertEntrypointReadsLabels(
                    standby,
                    TOPOLOGY.services().stream()
                            .filter(service -> standby.equals(service.name()))
                            .findFirst()
                            .orElseThrow()
                            .standbyOf());
        }
    }

    private void assertEntrypointReadsLabels(final String name, final String labelled) {
        final Map<String, String> environment = compose.service(name).environment();
        final Map<String, String> labels = compose.service(labelled).labels();
        assertEquals(
                labels.get("eu.nordtal.server"),
                environment.get("SERVER_KIND"),
                name + "'s entrypoint runs a different server than its label says");
        assertEquals(
                labels.get("eu.nordtal.plugins"),
                environment.get("SERVER_PLUGINS"),
                name + "'s entrypoint guards other plugins than its label names");
    }

    @Test
    void anArtefactThatMayHaveNoBuildForThisVersionIsNotOneTheGuardDemands() {
        // `?` exists so an artefact with no build for this version cannot keep the SMP down.
        final Topology.Service smp = server(Topology.SMP);

        assertTrue(
                smp.plugins().contains(Topology.CORE_PROTECT),
                "smp no longer carries CoreProtect - if that was deliberate, this test and the artefact go together");
        assertTrue(
                smp.optional().contains(Topology.CORE_PROTECT),
                "CoreProtect is guarded again. Until a 26.2 build exists that is an SMP that will"
                        + " not start, every start, for a reason nobody here can act on.");
    }

    @Test
    void voiceChatIsOneUdpPortOnTheGuardAndNoServiceOfTheNetworkPublishesOne() {
        // The proxy detects each backend's voice address and forwards it, so only the guard needs to publish it.
        for (final Topology.Service service : SERVERS) {
            assertEquals(
                    List.of(),
                    compose.service(service.name()).ports(),
                    service.name() + " publishes "
                            + compose.service(service.name()).ports() + ". Nothing in the"
                            + " network is reachable from outside except through caddy - a port here is"
                            + " either a leftover or a second, disagreeing arrangement, and it takes the"
                            + " number away from the guard that needs it.");
        }

        // port: -1 binds whatever port Velocity bound and hands the client that number; a remap breaks it.
        final String voice = compose.service(GUARD).udpPorts().stream()
                .filter(port -> port.contains(":25565:25565/udp"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        GUARD + " publishes " + compose.service(GUARD).udpPorts()
                                + " UDP and none of them is 25565 onto 25565. Voice chat needs exactly that"
                                + " one, because the plugin binds the proxy's own port."));
        final List<String> parts = ComposeFile.fields(voice.substring(0, voice.length() - "/udp".length()));
        assertEquals(3, parts.size(), voice + " is not bind:host:container");

        // The voice endpoint is the Minecraft endpoint under a different protocol; separated, the client hears no port.
        final String game = compose.service(GUARD).ports().stream()
                .filter(port -> !port.endsWith("/udp") && port.endsWith(":25565"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(GUARD + " publishes no TCP port onto 25565,"
                        + " so the guard is listening for Minecraft nowhere"));
        final List<String> gameParts = ComposeFile.fields(game);
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

    /** The guard in front of 25565, deliberately no server, so a run never restarts it with them. */
    private static final String GUARD = "caddy";

    @Test
    void theGuardPrefersTheLiveProxyAndFallsBackToTheStandby() {
        // `first` and the order of the upstreams are the rule: the standby answers only when the live proxy refuses.
        final String caddyfile = compose.configContent("caddyfile");
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
        final Map<String, String> environment = compose.service(Topology.PROXY).environment();
        assertTrue(
                String.valueOf(environment.get("VELOCITY_HAPROXY")).contains("true"),
                "the proxy is not told to expect a PROXY header (VELOCITY_HAPROXY is "
                        + environment.get("VELOCITY_HAPROXY") + "), while the guard writes one."
                        + " One without the other is a network that answers nobody.");
    }

    @Test
    void theProxyRunsVoiceChatsProxyHalfAndItIsNotOneTheProxyRefusesToStartWithout() {
        // voicechat-velocity is a pre-release; guarding on it lets its next bad build stop the proxy from starting.
        final Topology.Service proxy = server(Topology.PROXY);

        assertTrue(
                proxy.plugins().contains(Topology.VOICE_CHAT_PROXY),
                "the proxy carries no voicechat-velocity - without it every backend needs its"
                        + " own public UDP port back, and compose.yml publishes none");
        assertTrue(proxy.optional().contains(Topology.VOICE_CHAT_PROXY), "voicechat-velocity is guarded again");
    }

    @Test
    void neitherBackendRefusesToStartOverAMissingVoiceChatJar() {
        // Voice chat needs a client mod, so a missing jar costs a quiet evening while a guard entry costs the server.
        for (final String name : List.of(Topology.SMP, Topology.HUNGER_GAMES)) {
            final Topology.Service service = server(name);

            assertTrue(service.plugins().contains(Topology.VOICE_CHAT), name + " no longer runs voice chat at all");
            assertTrue(service.optional().contains(Topology.VOICE_CHAT), name + " refuses to start without voice chat");
        }
    }

    @Test
    void theWaitingRoomHasNoVoiceChatAndThatIsHowItStaysSilent() {
        // Simple Voice Chat needs its Bukkit plugin on the server a player stands on; the limbo has never had it.
        assertFalse(
                server(Topology.LIMBO).plugins().contains(Topology.VOICE_CHAT),
                "the limbo carries voice chat, so two people waiting can hear each other");
    }

    private static Topology.Service server(final String name) {
        return SERVERS.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("compose.yml labels no server '" + name + "'"));
    }

    @Test
    void noServiceIsGivenAPlayerLimit() {
        // The limit is the network's players setting, which the proxy alone enforces and an admin changes in Steward.
        for (final Map.Entry<String, ComposeFile.Service> service :
                compose.services().entrySet()) {
            for (final String variable : service.getValue().environment().keySet()) {
                assertFalse(
                        variable.contains("MAX_PLAYERS"),
                        service.getKey() + " is given " + variable + ", a second player limit beside the network's"
                                + " setting, which no admin sees in Steward and a change there cannot move.");
            }
        }
    }

    @Test
    void theServerVersionInComposeYmlIsTheOneCommonDeclaresAsALiteral() {
        // The literal is asserted, not merely required to exist, because a `${...:-26.2}` would pass a shape check.
        for (final Topology.Service service : SERVERS) {
            final String version = compose.service(service.name()).environment().get("SERVER_VERSION");
            assertNotNull(
                    version,
                    service.name() + " sets no SERVER_VERSION, so its entrypoint" + " cannot name the jar it runs");

            // Paper is an exact Minecraft version and the proxy is Fill's name for Velocity's major; see Platform.
            final String expected =
                    "velocity".equals(service.kind().fillProject()) ? Platform.VELOCITY_FAMILY : Platform.MINECRAFT;
            assertEquals(
                    expected,
                    version,
                    service.name() + "'s SERVER_VERSION is '" + version + "' and eu.nordtal.season"
                            + ".common.Platform says '" + expected + "'. Those are the version the"
                            + " container runs and the version every plugin in it was compiled"
                            + " against; a deployment where they differ loads no plugins.");
        }

        // steward-agent reads Platform directly, so nothing here should feed it a version.
        final Map<String, String> environment =
                compose.service(AgentWire.SERVICE).environment();
        for (final String retired : List.of(
                "NORDTAL_STEWARD_AGENT_MINECRAFT_VERSION",
                "NORDTAL_STEWARD_AGENT_VELOCITY_VERSION",
                "NORDTAL_STEWARD_AGENT_PAPER_BUILD",
                "NORDTAL_STEWARD_AGENT_VELOCITY_BUILD")) {
            assertNull(
                    environment.get(retired),
                    "compose.yml sets " + retired + " again. The two"
                            + " versions are constants in :common and there is no build pin anywhere -"
                            + " see the comment in StewardSpec where those four keys stood.");
        }
    }

    @Test
    void composeYmlDoesNotFetchPluginsAnyMoreTwoOwnersIsOneTooMany() {
        // entrypoint.sh deletes every other plugin version by prefix; an environment PACK_SHA1 is never written back.
        for (final String forbidden :
                List.of("SEASON_PLUGINS", "EXTRA_PLUGIN_URLS", "NORDTAL_PROXY_PACK_URL", "NORDTAL_PROXY_PACK_SHA1")) {
            compose.services()
                    .forEach((name, service) -> assertFalse(
                            service.environment().containsKey(forbidden),
                            "compose.yml sets " + forbidden + " on '" + name + "' again. Steward"
                                    + " owns the jars and the pack now."));
        }
    }

    @Test
    void noServiceIsGivenAnOptionalOverrideOfASettingStewardEdits() {
        // What a service needs before Steward can reach it: secrets, sign-in, releases and the offsite copy.
        final Set<String> bootstrap = Set.of(
                "NORDTAL_STEWARD_AGENT_SEASON_REPO",
                "NORDTAL_STEWARD_AGENT_GITHUB_TOKEN",
                "NORDTAL_STEWARD_AGENT_OFFSITE_REPOSITORY",
                "NORDTAL_STEWARD_AGENT_OFFSITE_PASSWORD",
                "NORDTAL_STEWARD_BUNQ_API_KEY",
                "NORDTAL_STEWARD_BUNQ_ACCOUNT_ID",
                "NORDTAL_PROXY_NETWORK_PUBLIC_ADDRESS",
                "NORDTAL_STEWARD_WEB_WEBAUTHN_RELYING_PARTY_ID",
                "NORDTAL_STEWARD_WEB_DISCORD_CLIENT_ID",
                "NORDTAL_STEWARD_WEB_DISCORD_CLIENT_SECRET",
                "NORDTAL_STEWARD_WEB_DISCORD_BOT_TOKEN",
                "NORDTAL_STEWARD_WEB_DISCORD_ROOT_ID",
                "NORDTAL_STEWARD_WEB_WEB_PUSH_PUBLIC_KEY",
                "NORDTAL_STEWARD_WEB_WEB_PUSH_PRIVATE_KEY");
        final Pattern optional = Pattern.compile("\\$\\{[A-Z0-9_]+:-}");
        final List<String> overrides = new java.util.ArrayList<>();
        compose.services()
                .forEach((name, service) -> service.environment().forEach((variable, value) -> {
                    if (variable.startsWith("NORDTAL_")
                            && !bootstrap.contains(variable)
                            && optional.matcher(value).matches()) {
                        overrides.add(name + ": " + variable);
                    }
                }));
        assertEquals(
                List.of(),
                overrides,
                "compose.yml passes optional overrides of config keys. A value set in the host's environment"
                        + " file replaces what Steward shows and saves, a list as a whole, and nothing on the page"
                        + " says the edit went nowhere. Settings are made in Steward.");
    }

    @Test
    void everyServerHasItsVolumeMountedIntoTheAgent() {
        final String mounts = String.valueOf(compose.service(AgentWire.SERVICE).mounts());
        for (final Topology.Service service : SERVERS) {
            // A server whose volume is not mounted reports as "unknown" for ever. Caught here.
            assertTrue(
                    mounts.contains("/volumes/" + service.name()),
                    AgentWire.SERVICE + " does not mount /volumes/" + service.name()
                            + "; it would report that server as unmounted on every run");
        }
    }

    @Test
    void everyServersPluginsIsTheSameDirectoryForTheServerAndForTheAgent() {
        final List<String> agentMounts = compose.service(AgentWire.SERVICE).mounts();

        for (final Topology.Service service : SERVERS) {
            assertPluginsDirectoryIsShared(service, agentMounts);
        }
    }

    private void assertPluginsDirectoryIsShared(final Topology.Service service, final List<String> agentMounts) {
        final String onTheServer = compose.service(service.name()).mounts().stream()
                .filter(mount -> mount.endsWith(":/data/plugins"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(service.name() + " mounts nothing onto"
                        + " /data/plugins. plugins/ is separate from the"
                        + " server's own volume; without this line the server reads an empty"
                        + " folder and the entrypoint stops the container."));

        final String onSteward = agentMounts.stream()
                .filter(mount -> mount.endsWith(":/volumes/" + service.name() + "/plugins"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(AgentWire.SERVICE + " does not mount "
                        + service.name() + "'s plugins/. It would then install into one place"
                        + " while the server reads another - and nothing would say so:"
                        + " `apply` reports success, the jars are on disk, and no server runs"
                        + " a single one of them."));

        // Compared expression for expression: a variable spelt differently anywhere is a silent split.
        assertEquals(
                ComposeFile.sourceOf(onTheServer),
                ComposeFile.sourceOf(onSteward),
                service.name() + ": the server and the agent are pointed at two different plugin sources");

        assertPluginsBackupMatchesSpec(service, agentMounts, onTheServer);

        // The default is a path under NORDTAL_DIR, not bare and relative, which resolves inside the agent image.
        final String fallback = ComposeFile.defaultOf(ComposeFile.sourceOf(onTheServer));
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
                .filter(mount -> mount.endsWith(":/backup-sources/" + backupVolume))
                .findFirst();
        // The mount is the backup set, so a plugins volume mounted for it is saved and must be the server's own.
        forTheBackup.ifPresent(mount -> assertEquals(
                ComposeFile.sourceOf(onTheServer),
                ComposeFile.sourceOf(mount),
                service.name() + ": the backup reads a different plugin source than the server runs from"));
    }

    @Test
    void theBundlesTheInterfaceShowsAreTheBundlesTheServicesActuallyRead() {
        // Saving a bundle IS the reload, so a volume spelt differently shows a form and quietly changes nothing.
        final List<String> stewardMounts = compose.service(AgentWire.SERVICE).mounts();

        // The interface shows a bundle under the compose service name, the same name the server owns it under.
        for (final Topology.Service service : SERVERS) {
            assertServerConfigMatchesInterface(service, stewardMounts);
        }

        // The bot has no plugins folder: its directory stays empty, and its name finds its jar in its image.
        assertTrue(
                stewardMounts.stream().anyMatch(mount -> mount.endsWith(":/configs/discord-bot")),
                AgentWire.SERVICE + " mounts nothing at /configs/discord-bot, so the bot's messages are in no form");
    }

    private void assertServerConfigMatchesInterface(final Topology.Service service, final List<String> stewardMounts) {
        final String onTheServer = compose.service(service.name()).mounts().stream()
                .filter(mount -> mount.endsWith(":/data/plugins"))
                .findFirst()
                .orElseThrow();
        final String onTheInterface = stewardMounts.stream()
                .filter(mount -> mount.endsWith(":/configs/" + service.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(AgentWire.SERVICE + " mounts nothing at"
                        + " /configs/" + service.name() + ", so that server's messages are in"
                        + " no form at all."
                        + " A volume that is not mounted is not an error to the page - it lists"
                        + " what it finds - so this is invisible from the browser."));

        assertEquals(
                ComposeFile.sourceOf(onTheServer),
                ComposeFile.sourceOf(onTheInterface),
                service.name() + ": the interface edits one directory and the server reads"
                        + " another. Saving would report success and change nothing.");

        assertFalse(
                onTheInterface.endsWith(":ro"),
                service.name() + "'s bundles are mounted read-only into the agent, so the form"
                        + " is drawn and the save fails: every bundle in the stack is editable from the interface.");
    }

    @Test
    void exactlyTheFourMinecraftServicesHavePluginsAndTwoOfThemAStandby() {
        assertEquals(
                List.of(Topology.PROXY, Topology.LIMBO, Topology.HUNGER_GAMES, Topology.SMP),
                SERVERS.stream().map(Topology.Service::name).toList(),
                "the proxy comes first: a report reads best in that order");
        assertEquals(List.of("proxy-standby", "limbo-standby"), TOPOLOGY.standbys());
        assertFalse(TOPOLOGY.hasPlugins("proxy-standby"), "a standby's plugins are a copy, never resolved");
    }

    @Test
    void aRunRenewsPostgresLastAndNeverTheAgentTheMigrateServiceOrAStandby() {
        assertEquals(List.of("postgres"), TOPOLOGY.renewed(AgentWire.Renewal.LAST), "the report goes through it");
        for (final String never : List.of(AgentWire.SERVICE, Topology.MIGRATE, "proxy-standby", "limbo-standby")) {
            assertNull(
                    TOPOLOGY.services().stream()
                            .filter(service -> never.equals(service.name()))
                            .findFirst()
                            .orElseThrow()
                            .renewal(),
                    never + " carries eu.nordtal.renew, and no run may make it again");
        }
        assertTrue(
                TOPOLOGY.renewed(AgentWire.Renewal.RUN)
                        .containsAll(
                                SERVERS.stream().map(Topology.Service::name).toList()),
                "a server whose image a run may not renew is one an update can never bring to its release");
    }
}

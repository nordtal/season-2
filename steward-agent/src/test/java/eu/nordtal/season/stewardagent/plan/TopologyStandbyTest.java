package eu.nordtal.season.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.topology.ComposeFile;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.yaml.snakeyaml.Yaml;

/** The standby-pair half of {@link TopologyTest}, which reads compose.yml on its own. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopologyStandbyTest {

    private static final String GUARD = "caddy";

    private final Map<String, Object> services = readComposeServices();

    /** Environment keys a standby must set differently from its model, each with its reason. */
    private static final Map<String, String> TELLS_THE_PAIR_APART = Map.of(
            "NORDTAL_PROXY_NETWORK_STANDBY",
            "It is the only thing that tells the two proxies apart, and a proxy cannot work it out" + " for itself.");

    @Test
    void everyStandbyIsItsModelAgainOnItsOwnVolumes() {
        // A standby has to feel identical to its model, and differ only in the volumes and the published port.
        for (final String standby : ComposeFile.topology().standbys()) {
            assertStandbyMatchesItsModel(
                    ComposeFile.topology().services().stream()
                            .filter(service -> standby.equals(service.name()))
                            .findFirst()
                            .orElseThrow()
                            .standbyOf(),
                    standby);
        }
    }

    private void assertStandbyMatchesItsModel(final String model, final String standby) {

        @SuppressWarnings("unchecked")
        final Map<String, Object> defined = (Map<String, Object>) services.get(standby);
        assertNotNull(
                defined,
                "compose.yml has no service '" + standby + "'. Without it there"
                        + " is nobody to transfer players to and an update takes the network down the"
                        + " way it always did.");
        @SuppressWarnings("unchecked")
        final Map<String, Object> itsModel = (Map<String, Object>) services.get(model);

        assertStandbyEnvironmentMatchesExceptWhereItMustDiffer(model, standby, itsModel, defined);

        assertEquals(
                List.of("standby"),
                defined.get("profiles"),
                standby + " is not in a profile of its own. In `mc` it would run all season"
                        + " beside the service it exists to replace, on a host that has been"
                        + " out of memory once already.");
        assertFalse(
                String.valueOf(itsModel.get("profiles")).contains("standby"),
                model + " is in the standby profile, so the pair would start together");

        // The volumes must not be shared: two Paper processes on one /data fight over session.lock.
        for (final String mountPoint : List.of(":/data", ":/data/plugins")) {
            assertNotEquals(
                    sourceOf(mountEndingIn(itsModel, mountPoint)),
                    sourceOf(mountEndingIn(defined, mountPoint)),
                    standby + " mounts the same source as " + model + " at " + mountPoint
                            + ". Two servers on one directory is not a standby, it is one"
                            + " server started twice.");
        }

        // The same plugins/ rule the live services keep: what the agent fills has to be what the container reads.
        @SuppressWarnings("unchecked")
        final Map<String, Object> agent = (Map<String, Object>) services.get(AgentWire.SERVICE);
        final String onSteward = mountsOf(agent).stream()
                .filter(mount -> mount.endsWith(":/volumes/" + standby + "/plugins"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(AgentWire.SERVICE + " does not mount " + standby
                        + "'s plugins/, so Standbys#fill has nowhere to copy the jars. The"
                        + " standby would be started for a swap with an empty folder and"
                        + " refuse to boot."));
        assertEquals(
                sourceOf(mountEndingIn(defined, ":/data/plugins")),
                sourceOf(onSteward),
                standby + ": the agent fills one directory and the container reads" + " another");
    }

    private void assertStandbyEnvironmentMatchesExceptWhereItMustDiffer(
            final String model,
            final String standby,
            final Map<String, Object> itsModel,
            final Map<String, Object> defined) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> theirs =
                new java.util.LinkedHashMap<>((Map<String, Object>) itsModel.get("environment"));
        @SuppressWarnings("unchecked")
        final Map<String, Object> ours =
                new java.util.LinkedHashMap<>((Map<String, Object>) defined.get("environment"));

        // A proxy cannot tell which of the two it is; this one variable must be present on both and differ.
        for (final Map.Entry<String, String> apart : TELLS_THE_PAIR_APART.entrySet()) {
            final String key = apart.getKey();
            if (!theirs.containsKey(key) && !ours.containsKey(key)) {
                continue;
            }
            assertNotNull(theirs.get(key), model + " does not set " + key + " at all. " + apart.getValue());
            assertNotNull(ours.get(key), standby + " does not set " + key + " at all. " + apart.getValue());
            assertNotEquals(
                    String.valueOf(theirs.remove(key)),
                    String.valueOf(ours.remove(key)),
                    model + " and " + standby + " agree on " + key + ", so one of them is"
                            + " playing the other's part. " + apart.getValue());
        }

        assertEquals(
                theirs,
                ours,
                standby + " is configured differently from " + model + ". It runs the same jars"
                        + " under the same name and it is the process carrying every player for"
                        + " the length of a swap; a setting that reaches only one of the two is"
                        + " a setting players meet in half the season. compose.yml merges one"
                        + " YAML anchor into both, so this fails when somebody has copied the"
                        + " block instead of merging it - the only key allowed to differ is"
                        + " " + TELLS_THE_PAIR_APART.keySet() + ".");
    }

    @Test
    void theStandbyIsReachedOnASecondPortAndThatIsThePortAClientIsTold() {
        final String standby = ComposeFile.topology().standbyOf(Topology.PROXY).orElseThrow();
        assertEquals(
                List.of(),
                ports(standby),
                standby + " publishes " + ports(standby)
                        + ". It is reached through the guard instead, which forwards with a"
                        + " PROXY header - a connection arriving any other way is one Velocity drops,"
                        + " because haproxy-protocol is true in its velocity.toml.");

        // The guard publishes it instead, on one host port in both protocols: TCP for the client, UDP for voice.
        final List<String> tcp = ports(GUARD).stream()
                .filter(port -> !port.endsWith("/udp") && port.contains("PROXY_STANDBY_PORT"))
                .toList();
        final List<String> udp = udpPorts(GUARD).stream()
                .filter(port -> port.contains("PROXY_STANDBY_PORT"))
                .toList();
        assertEquals(
                1,
                tcp.size(),
                GUARD + " publishes " + tcp + " for the standby - it needs"
                        + " exactly one TCP port, which is where a transferred client arrives");
        assertEquals(1, udp.size(), GUARD + " publishes " + udp + " for the standby");

        final List<String> tcpParts = fields(tcp.getFirst());
        final String withProtocol = udp.getFirst();
        final List<String> udpParts = fields(withProtocol.substring(0, withProtocol.length() - "/udp".length()));
        assertEquals(
                tcpParts.get(1),
                udpParts.get(1),
                "the guard publishes the standby's Minecraft and voice on two different host ports");

        // Not the live proxy's own port: sharing it sends a transferred player back to the proxy they are leaving.
        final String game = ports(GUARD).stream()
                .filter(port -> !port.endsWith("/udp") && port.endsWith(":25565"))
                .findFirst()
                .orElseThrow();
        assertNotEquals(
                fields(game).get(1),
                tcpParts.get(1),
                "the guard publishes the standby on"
                        + " the same host port as the live proxy, so a transfer sends a player nowhere");

        // Two literals are two chances to write 25566 and 25567 the wrong way round, so both are asserted here.
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment =
                (Map<String, Object>) ((Map<String, Object>) services.get(Topology.PROXY)).get("environment");
        assertEquals(
                tcpParts.get(1),
                String.valueOf(environment.get("NORDTAL_PROXY_NETWORK_STANDBY_PORT")),
                "the proxy sends a client to a port the guard does not listen on");
    }

    @Test
    void theProxiesAreToldTheAddressPlayersReachThisNetworkOn() {
        // A transfer names an address to the client, so a compose name or a bind address cannot stand in for it.
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment =
                (Map<String, Object>) ((Map<String, Object>) services.get(Topology.PROXY)).get("environment");
        final Object address = environment.get("NORDTAL_PROXY_NETWORK_PUBLIC_ADDRESS");
        assertNotNull(
                address,
                "the proxy is given no public address, so network.yml's empty"
                        + " default stands, no transfer is ever offered, and nothing in .env can change"
                        + " that - a jcore override is only read for a key the spec declares.");
        assertTrue(
                String.valueOf(address).contains("NETWORK_PUBLIC_ADDRESS"),
                "the public address does not come from NETWORK_PUBLIC_ADDRESS: " + address
                        + " - that is the name deploy/nordtal.sh writes into the env file");
    }

    @Test
    void theProxiesKnowTheStandbyWaitingRoomByName() {
        // An unregistered Velocity server cannot be connected to; a registered but down one costs nothing until needed.
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment =
                (Map<String, Object>) ((Map<String, Object>) services.get(Topology.PROXY)).get("environment");
        final String servers = defaultOf(String.valueOf(environment.get("VELOCITY_SERVERS")));
        final String standby = ComposeFile.topology().standbyOf(Topology.LIMBO).orElseThrow();
        assertTrue(
                servers.contains(standby + "=" + standby + ":25565"),
                "VELOCITY_SERVERS does not register " + standby + ": " + servers + ". Neither"
                        + " proxy could then send anybody to the standby waiting room, which is"
                        + " where every player spends a swap.");
    }

    /** The one mount of a service whose destination is {@code ending}. */
    private static String mountEndingIn(final Map<String, Object> service, final String ending) {
        return mountsOf(service).stream()
                .filter(mount -> mount.endsWith(ending))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("no mount ending in " + ending + " on " + service.get("container_name")));
    }

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

    /** A {@code bind:host:container} mapping split on its separating colons, not on the ones inside a default. */
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

    private static String defaultOf(final String value) {
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^\\$\\{[A-Z0-9_]+:-(.*)}$").matcher(value);
        assertTrue(matcher.matches(), value + " has no default an unfilled .env would fall back to");
        return matcher.group(1);
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
}

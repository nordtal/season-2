package eu.nordtal.season.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.ComposeFile;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.topology.DeclaredTopology;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The standby-pair half of {@link TopologyTest}, which reads compose.yml on its own. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopologyStandbyTest {

    private static final String GUARD = "caddy";

    private final ComposeFile compose = ComposeFile.get();

    /** Environment keys a standby must set differently from its model, each with its reason. */
    private static final Map<String, String> TELLS_THE_PAIR_APART = Map.of(
            "NORDTAL_PROXY_NETWORK_STANDBY",
            "It is the only thing that tells the two proxies apart, and a proxy cannot work it out" + " for itself.");

    @Test
    void everyStandbyIsItsModelAgainOnItsOwnVolumes() {
        // A standby has to feel identical to its model, and differ only in the volumes and the published port.
        for (final String standby : DeclaredTopology.topology().standbys()) {
            assertStandbyMatchesItsModel(
                    DeclaredTopology.topology().services().stream()
                            .filter(service -> standby.equals(service.name()))
                            .findFirst()
                            .orElseThrow()
                            .standbyOf(),
                    standby);
        }
    }

    private void assertStandbyMatchesItsModel(final String model, final String standby) {
        // Without the standby service there is nobody to transfer players to, and an update takes the network down.
        final ComposeFile.Service defined = compose.service(standby);
        final ComposeFile.Service itsModel = compose.service(model);

        assertStandbyEnvironmentMatchesExceptWhereItMustDiffer(model, standby, itsModel, defined);

        assertEquals(
                List.of("standby"),
                defined.profiles(),
                standby + " is not in a profile of its own. In `mc` it would run all season"
                        + " beside the service it exists to replace, on a host that has been"
                        + " out of memory once already.");
        assertFalse(
                itsModel.profiles().contains("standby"),
                model + " is in the standby profile, so the pair would start together");

        // The volumes must not be shared: two Paper processes on one /data fight over session.lock.
        for (final String mountPoint : List.of(":/data", ":/data/plugins")) {
            assertNotEquals(
                    ComposeFile.sourceOf(itsModel.mountEndingIn(mountPoint)),
                    ComposeFile.sourceOf(defined.mountEndingIn(mountPoint)),
                    standby + " mounts the same source as " + model + " at " + mountPoint
                            + ". Two servers on one directory is not a standby, it is one"
                            + " server started twice.");
        }

        // The same plugins/ rule the live services keep: what the agent fills has to be what the container reads.
        final String onSteward = compose.service(AgentWire.SERVICE).mounts().stream()
                .filter(mount -> mount.endsWith(":/volumes/" + standby + "/plugins"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(AgentWire.SERVICE + " does not mount " + standby
                        + "'s plugins/, so Standbys#fill has nowhere to copy the jars. The"
                        + " standby would be started for a swap with an empty folder and"
                        + " refuse to boot."));
        assertEquals(
                ComposeFile.sourceOf(defined.mountEndingIn(":/data/plugins")),
                ComposeFile.sourceOf(onSteward),
                standby + ": the agent fills one directory and the container reads" + " another");
    }

    private void assertStandbyEnvironmentMatchesExceptWhereItMustDiffer(
            final String model,
            final String standby,
            final ComposeFile.Service itsModel,
            final ComposeFile.Service defined) {
        final Map<String, String> theirs = new java.util.LinkedHashMap<>(itsModel.environment());
        final Map<String, String> ours = new java.util.LinkedHashMap<>(defined.environment());

        // A proxy cannot tell which of the two it is; this one variable must be present on both and differ.
        for (final Map.Entry<String, String> apart : TELLS_THE_PAIR_APART.entrySet()) {
            final String key = apart.getKey();
            if (!theirs.containsKey(key) && !ours.containsKey(key)) {
                continue;
            }
            assertNotNull(theirs.get(key), model + " does not set " + key + " at all. " + apart.getValue());
            assertNotNull(ours.get(key), standby + " does not set " + key + " at all. " + apart.getValue());
            assertNotEquals(
                    theirs.remove(key),
                    ours.remove(key),
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
        final String standby =
                DeclaredTopology.topology().standbyOf(Topology.PROXY).orElseThrow();
        assertEquals(
                List.of(),
                compose.service(standby).ports(),
                standby + " publishes " + compose.service(standby).ports()
                        + ". It is reached through the guard instead, which forwards with a"
                        + " PROXY header - a connection arriving any other way is one Velocity drops,"
                        + " because haproxy-protocol is true in its velocity.toml.");

        // The guard publishes it instead, on one host port in both protocols: TCP for the client, UDP for voice.
        final List<String> tcp = compose.service(GUARD).ports().stream()
                .filter(port -> !port.endsWith("/udp") && port.contains("PROXY_STANDBY_PORT"))
                .toList();
        final List<String> udp = compose.service(GUARD).udpPorts().stream()
                .filter(port -> port.contains("PROXY_STANDBY_PORT"))
                .toList();
        assertEquals(
                1,
                tcp.size(),
                GUARD + " publishes " + tcp + " for the standby - it needs"
                        + " exactly one TCP port, which is where a transferred client arrives");
        assertEquals(1, udp.size(), GUARD + " publishes " + udp + " for the standby");

        final List<String> tcpParts = ComposeFile.fields(tcp.getFirst());
        final String withProtocol = udp.getFirst();
        final List<String> udpParts =
                ComposeFile.fields(withProtocol.substring(0, withProtocol.length() - "/udp".length()));
        assertEquals(
                tcpParts.get(1),
                udpParts.get(1),
                "the guard publishes the standby's Minecraft and voice on two different host ports");

        // Not the live proxy's own port: sharing it sends a transferred player back to the proxy they are leaving.
        final String game = compose.service(GUARD).ports().stream()
                .filter(port -> !port.endsWith("/udp") && port.endsWith(":25565"))
                .findFirst()
                .orElseThrow();
        assertNotEquals(
                ComposeFile.fields(game).get(1),
                tcpParts.get(1),
                "the guard publishes the standby on"
                        + " the same host port as the live proxy, so a transfer sends a player nowhere");

        // Two literals are two chances to write 25566 and 25567 the wrong way round, so both are asserted here.
        final Map<String, String> environment = compose.service(Topology.PROXY).environment();
        assertEquals(
                tcpParts.get(1),
                environment.get("NORDTAL_PROXY_NETWORK_STANDBY_PORT"),
                "the proxy sends a client to a port the guard does not listen on");
    }

    @Test
    void theProxiesAreToldTheAddressPlayersReachThisNetworkOn() {
        // A transfer names an address to the client, so a compose name or a bind address cannot stand in for it.
        final Map<String, String> environment = compose.service(Topology.PROXY).environment();
        final String address = environment.get("NORDTAL_PROXY_NETWORK_PUBLIC_ADDRESS");
        assertNotNull(
                address,
                "the proxy is given no public address, so network.yml's empty"
                        + " default stands, no transfer is ever offered, and nothing in .env can change"
                        + " that - a stored override is only read for a key the spec declares.");
        assertTrue(
                address.contains("NETWORK_PUBLIC_ADDRESS"),
                "the public address does not come from NETWORK_PUBLIC_ADDRESS: " + address
                        + " - that is the name deploy/nordtal.sh writes into the env file");
    }

    @Test
    void theProxiesKnowTheStandbyWaitingRoomByName() {
        // An unregistered Velocity server cannot be connected to; a registered but down one costs nothing until needed.
        final Map<String, String> environment = compose.service(Topology.PROXY).environment();
        final String servers = ComposeFile.defaultOf(environment.get("VELOCITY_SERVERS"));
        final String standby =
                DeclaredTopology.topology().standbyOf(Topology.LIMBO).orElseThrow();
        assertTrue(
                servers.contains(standby + "=" + standby + ":25565"),
                "VELOCITY_SERVERS does not register " + standby + ": " + servers + ". Neither"
                        + " proxy could then send anybody to the standby waiting room, which is"
                        + " where every player spends a swap.");
    }
}

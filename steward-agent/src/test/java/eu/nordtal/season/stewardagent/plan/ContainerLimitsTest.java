package eu.nordtal.season.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.ComposeFile;
import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.internalapi.BankWire;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.topology.DeclaredTopology;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rights and limits compose.yml gives each container, beside the accounts {@link TopologyDeploymentTest} holds. */
class ContainerLimitsTest {

    private static final List<Topology.Service> SERVERS =
            DeclaredTopology.topology().servers();

    /** The compose service that runs PostgreSQL. */
    private static final String DATABASE = "postgres";

    /** The compose service that carries HTTPS and the game ports. */
    private static final String CADDY = "caddy";

    /** Every service that runs a jar of ours on the image template. */
    private static final List<String> JVM_SERVICES =
            List.of(Topology.DISCORD_BOT, Topology.STEWARD, AgentWire.SERVICE, BankWire.SERVICE);

    private final ComposeFile compose = ComposeFile.get();

    /**
     * Caddy and the database keep root, since their volumes are root's, but only the capabilities they use.
     *
     * Caddy's binary carries cap_net_bind_service and does not start without it. The database's entrypoint chowns and
     * chmods its data directory, which belongs to {@code postgres}, and switches to that account; without
     * DAC_READ_SEARCH it cannot read that directory on a restart. steward-agent keeps Docker's default set.
     */
    @Test
    void caddyAndTheDatabaseKeepRootWithOnlyTheCapabilitiesTheyUse() {
        final Map<String, List<String>> kept = Map.of(
                CADDY, List.of("NET_BIND_SERVICE"),
                DATABASE, List.of("CHOWN", "DAC_READ_SEARCH", "FOWNER", "SETGID", "SETUID"));
        assertAll(kept.entrySet().stream().map(entry -> () -> {
            final ComposeFile.Service service = compose.service(entry.getKey());
            assertEquals(List.of("ALL"), service.list("cap_drop"), "'" + entry.getKey() + "' keeps Docker's defaults");
            assertEquals(entry.getValue(), service.list("cap_add"), "'" + entry.getKey() + "' adds another capability");
        }));
        List.of(CADDY, DATABASE, AgentWire.SERVICE)
                .forEach(name -> assertTrue(
                        compose.service(name).list("security_opt").contains("no-new-privileges:true"),
                        "'" + name + "' could gain privileges through a setuid binary"));
    }

    /** Every service that keeps running has a memory and a pids limit, so a runaway takes only itself down. */
    @Test
    void everyServiceThatKeepsRunningHasAMemoryAndAPidsLimit() {
        final Map<String, ComposeFile.Service> running = new java.util.LinkedHashMap<>();
        compose.services().forEach((name, service) -> {
            if (!service.text("restart")
                    .orElseThrow(() -> new AssertionError("'" + name + "' sets no restart"))
                    .equals("no")) {
                running.put(name, service);
            }
        });
        assertTrue(
                running.keySet().containsAll(List.of(CADDY, DATABASE, AgentWire.SERVICE)), running.keySet()::toString);
        assertAll(running.entrySet().stream().map(entry -> () -> {
            assertTrue(entry.getValue().has("mem_limit"), "'" + entry.getKey() + "' has no memory limit");
            assertTrue(entry.getValue().has("pids_limit"), "'" + entry.getKey() + "' has no pids limit");
        }));
    }

    /** A JVM of ours takes half its limit as its heap; left alone it would take a quarter and the serial collector. */
    @Test
    void everyJvmServiceTakesHalfItsMemoryLimitAsItsHeap() {
        assertTrue(
                RepositoryRoot.read("deploy/jvm/entrypoint.sh").contains("java ${JAVA_OPTS:-}"),
                "the JVM entrypoint no longer passes JAVA_OPTS on");
        assertAll(JVM_SERVICES.stream()
                .map(name -> () -> assertEquals(
                        "-XX:MaxRAMPercentage=50 -XX:+UseG1GC",
                        compose.service(name).environment().get("JAVA_OPTS"),
                        "'" + name + "' sizes its heap without its memory limit")));
    }

    /**
     * A Minecraft server's default limit leaves half again its heap, and at least a gigabyte, above the heap.
     *
     * HEAP is committed in full at the start, and Paper's chunks, threads and plugins live beside it. .env.example
     * names each limit with the same default.
     */
    @Test
    void everyMinecraftServersMemoryLimitLeavesRoomAboveItsHeap() {
        final String example = RepositoryRoot.read(".env.example");
        final List<String> servers = new java.util.ArrayList<>();
        compose.services().forEach((name, service) -> {
            if ("./deploy/minecraft".equals(service.block("build").get("context"))) {
                servers.add(name);
            }
        });
        assertTrue(servers.size() >= SERVERS.size(), servers::toString);
        assertAll(servers.stream().map(name -> () -> {
            final ComposeFile.Service service = compose.service(name);
            final String limitText = service.text("mem_limit").orElseThrow(() -> new AssertionError(name));
            final long heap = bytes(service.defaultedEnvironment("HEAP"));
            final long limit = bytes(ComposeFile.defaultOf(limitText));
            assertTrue(
                    limit >= Math.max(heap * 3 / 2, heap + (1L << 30)),
                    "'" + name + "' gets " + limit + " bytes for a heap of " + heap);
            final java.util.regex.Matcher variable =
                    java.util.regex.Pattern.compile("\\$\\{(\\w+):-").matcher(limitText);
            assertTrue(variable.find(), "'" + name + "' takes its limit from no variable: " + limitText);
            assertTrue(
                    example.contains("#" + variable.group(1) + "=" + ComposeFile.defaultOf(limitText) + "\n"),
                    ".env.example does not name " + variable.group(1) + " with its default");
        }));
    }

    /** A size as compose and the JVM write it: digits and k, m or g. */
    private static long bytes(final String size) {
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d+)([kKmMgG])").matcher(size);
        assertTrue(matcher.matches(), size + " is no size");
        final int shift = switch (Character.toLowerCase(matcher.group(2).charAt(0))) {
            case 'k' -> 10;
            case 'm' -> 20;
            default -> 30;
        };
        return Long.parseLong(matcher.group(1)) << shift;
    }
}

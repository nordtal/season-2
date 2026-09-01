package eu.nordtal.s2.updater.plan;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Topology} and {@code deploy/compose.yml} are two copies of one fact. This is the test that
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
        final Path compose = findUpwards("deploy/compose.yml");
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

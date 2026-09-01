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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("the season jar each service carries is the one compose asks the entrypoint for")
    void seasonPluginsMatch() {
        for (final Topology.Service service : Topology.SERVICES) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> defined = (Map<String, Object>) services.get(service.name());
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment = (Map<String, Object>) defined.get("environment");

            final String plugins = String.valueOf(environment.get("SEASON_PLUGINS"));
            for (final String artifact : service.plugins()) {
                if (!Topology.SEASON_JARS.contains(artifact)) {
                    // The third-party three arrive through EXTRA_PLUGIN_URLS, whose contents are
                    // full URLs and are checked below rather than here.
                    continue;
                }
                assertTrue(plugins.contains(artifact + "-"),
                        service.name() + "'s SEASON_PLUGINS does not carry " + artifact + ": " + plugins);
            }
        }
    }

    @Test
    @DisplayName("the SMP server's required third-party plugins are the three the topology names")
    void thirdPartyPluginsMatch() {
        @SuppressWarnings("unchecked")
        final Map<String, Object> smp = (Map<String, Object>) services.get(Topology.SMP);
        @SuppressWarnings("unchecked")
        final Map<String, Object> environment = (Map<String, Object>) smp.get("environment");
        final String urls = String.valueOf(environment.get("EXTRA_PLUGIN_URLS"));

        // Matched on the substring each plugin's URL must contain, not on a whole URL: the version
        // in it is exactly what this module exists to stop being hand-maintained, so pinning it
        // here would guarantee a failing test on the first successful update.
        assertTrue(urls.contains("papermc-display-tags"), urls);
        assertTrue(urls.contains("packetevents"), urls);
        assertTrue(urls.contains("Chunky"), urls);

        assertEquals(Set.of(Topology.SMP, Topology.DISPLAY_TAGS, Topology.PACKETEVENTS, Topology.CHUNKY),
                new LinkedHashSet<>(smpPlugins()));
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

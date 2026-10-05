package eu.nordtal.season.stewardagent;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.stewardagent.backup.BackupRoutes;
import eu.nordtal.season.stewardagent.bundles.BundleRoutes;
import eu.nordtal.season.stewardagent.bundles.ImageJars;
import eu.nordtal.season.stewardagent.descriptor.PluginDescriptors;
import eu.nordtal.season.stewardagent.docker.Console;
import eu.nordtal.season.stewardagent.docker.Containers;
import eu.nordtal.season.stewardagent.docker.Docker;
import eu.nordtal.season.stewardagent.logs.LogStreams;
import eu.nordtal.season.stewardagent.measure.HostMetrics;
import eu.nordtal.season.stewardagent.measure.Sampler;
import eu.nordtal.season.stewardagent.measure.VolumeSizes;
import eu.nordtal.season.stewardagent.topology.ComposeTopology;
import io.javalin.config.JavalinConfig;
import java.nio.file.Path;
import java.time.Clock;
import org.jspecify.annotations.Nullable;

/**
 * Every route steward-agent serves but the deployments: topology, containers, logs, samples, backups, descriptors.
 *
 * Built from the daemon and the paths alone, so a test can put the real routes over a stand-in daemon.
 */
public final class AgentApi implements AutoCloseable {

    private final AgentRoutes routes;
    private final BackupRoutes backups;
    private final LogStreams logs;
    private final Sampler sampler;
    private final BundleRoutes bundles;
    private final PluginDescriptors descriptors;
    private final VolumeSizes sizes;
    private final ComposeTopology topology;

    /**
     * Wires the routes of one compose project.
     *
     * @param definitions compose.yml's services, which every label is read from
     * @param hashes compose's hash of each service's definition, which a container's own label is compared with
     */
    public AgentApi(
            final Docker docker,
            final String project,
            final ComposeTopology.Definitions definitions,
            final Containers.Definitions hashes,
            final Paths paths,
            final Clock clock,
            final Scheduler scheduler) {
        this.topology = new ComposeTopology(definitions, paths.backupSources().toString(), clock);
        this.sampler = new Sampler(docker, new HostMetrics(), project, clock, scheduler);
        this.logs = new LogStreams(docker, project, paths.volumesRoot(), clock, scheduler);
        this.routes = new AgentRoutes(
                docker,
                new Containers(docker, project, hashes),
                new Console(docker, project, topology::consoles),
                sampler,
                topology);
        this.backups = new BackupRoutes(paths.backups());
        final ImageJars images = ImageJars.fromContainers(
                docker, project, java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "image-jars"));
        this.bundles = new BundleRoutes(paths.configs(), images);
        this.descriptors = new PluginDescriptors(
                paths.configs(),
                images,
                () -> topology.read().services().stream()
                        .map(service -> new PluginDescriptors.Service(service.name(), service.image()))
                        .toList());
        this.sizes = new VolumeSizes(paths.volumesRoot());
    }

    /**
     * Where the volumes this process reads are mounted.
     *
     * @param volumesRoot the services' volumes, one directory per service, or {@code null} for none
     * @param configs one directory per service whose jars carry message bundles, the bot's empty
     * @param backupSources every volume a backup saves, one directory per Docker volume name, writable for a restore
     * @param backups where archives are written, mounted at the same path in the database's container
     */
    public record Paths(@Nullable Path volumesRoot, Path configs, Path backupSources, Path backups) {

        /** Where compose.yml mounts them, which a setting of the same name moves. */
        public static final Paths DEFAULTS =
                new Paths(Path.of("/volumes"), Path.of("/configs"), Path.of("/backup-sources"), Path.of("/backups"));
    }

    /** Starts the sampler, whose first round is taken at once. */
    public void start() {
        sampler.start();
    }

    /** What compose.yml's labels say, the one reading the routes and the runs share. */
    public ComposeTopology topology() {
        return topology;
    }

    public void register(final JavalinConfig config) {
        routes.register(config);
        backups.register(config);
        config.routes.sse(AgentWire.LOGS, logs::follow);
        config.routes.get(AgentWire.LOG_CAPACITY, logs::capacity);
        config.routes.get(AgentWire.DISK, sizes::route);
        bundles.register(config);
        config.routes.get(AgentWire.DESCRIPTORS, ctx -> ctx.json(descriptors.read()));
    }

    /** Ends the follows first, or Jetty spins on a stream it cannot close, then the sampler. */
    @Override
    public void close() {
        logs.close();
        sampler.close();
    }
}

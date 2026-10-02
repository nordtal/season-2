package eu.nordtal.s2.stewardagent.plan;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.JarName;
import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.stewardagent.plugin.ManagedPlugin;
import eu.nordtal.s2.stewardagent.plugin.PluginDirectory;
import eu.nordtal.s2.stewardagent.source.Modrinth;
import eu.nordtal.s2.stewardagent.source.RemoteFile;
import io.javalin.http.BadGatewayResponse;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plugins on one server: the routes that list, search and add, and the removal a run carries out.
 *
 * The list is the jars on disk; only a jar a {@code service_plugin} row claims can be removed.
 */
public final class PluginsApi {

    private static final Logger log = LoggerFactory.getLogger(PluginsApi.class);

    /**
     * The only host an icon may be loaded from.
     *
     * A stored icon URL is fetched by every admin's browser, so anything not served by Modrinth is dropped.
     */
    private static final String ICON_HOST = "https://cdn.modrinth.com/";

    private final PluginDirectory plugins;
    private final Modrinth modrinth;
    private final @Nullable Path volumesRoot;
    private final String gameVersion;
    private final JarIdentity identity;
    private final Map<String, String> fixedProjects;

    private final Clock clock;
    private final Supplier<List<Topology.Service>> servers;

    /**
     * Builds the API.
     *
     * @param servers the servers as compose.yml's labels describe them, read again on every request
     * @param volumesRoot where the services' volumes are mounted here, or {@code null} when none are, which the list
     *     reports
     * @param gameVersion the Minecraft version every search and resolve is filtered to
     * @param fixedProjects the Modrinth project id of each plugin the network gives, keyed by artefact id
     */
    public PluginsApi(
            final Supplier<List<Topology.Service>> servers,
            final PluginDirectory plugins,
            final Modrinth modrinth,
            final @Nullable Path volumesRoot,
            final String gameVersion,
            final Map<String, String> fixedProjects,
            final Clock clock) {
        this.servers = Objects.requireNonNull(servers, "servers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fixedProjects = Map.copyOf(fixedProjects);
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.modrinth = Objects.requireNonNull(modrinth, "modrinth");
        this.volumesRoot = volumesRoot;
        this.gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        this.identity = new JarIdentity(modrinth, clock::instant);
    }

    /** {@code GET /api/services/{name}/plugins} */
    public void list(final Context ctx) {
        final Topology.Service service = serviceOf(ctx.pathParam("name"));
        final List<ManagedPlugin> added = plugins.on(service.name());
        final Installation installed = scan(service.name());

        // Unclaimed jars are the ones the network gives; Modrinth ones are drawn like an added plugin.
        final List<Installation.Jar> given = installed.plugins().stream()
                .filter(jar -> ownerOf(jar, added) == null && !isNordtal(jar))
                .toList();
        final Map<String, Modrinth.Project> published = identity.identify(given);

        final List<String> claimed = new ArrayList<>();
        final List<PluginRow> rows = new ArrayList<>(installedRows(installed, added, published, claimed));
        rows.addAll(absentRows(service, installed, published));
        rows.addAll(notYetInstalledRows(added, claimed));
        rows.sort(Comparator.comparing(row -> row.name.toLowerCase(java.util.Locale.ROOT)));

        ctx.json(new AgentWire.Plugins(
                service.name(),
                service.kind().modrinthLoader(),
                gameVersion,
                installed.mounted(),
                noteReleases(rows.stream().map(PluginRow::wire).toList(), plugins.releases(service.name()))));
    }

    /** One row per jar in the volume, named and pictured from a row or from Modrinth where either answers. */
    private List<PluginRow> installedRows(
            final Installation installed,
            final List<ManagedPlugin> added,
            final Map<String, Modrinth.Project> published,
            final List<String> claimed) {
        final List<PluginRow> rows = new ArrayList<>();
        for (final Installation.Jar jar : installed.plugins()) {
            final String prefix = jar.prefix();
            final ManagedPlugin row = ownerOf(jar, added);
            if (row != null) {
                claimed.add(row.artifact());
            }
            final PluginRow described = describe(row, prefix, jar, true);
            final Modrinth.Project project = published.get(jar.fileName());
            if (project != null) {
                described.publishedAs(project);
            }
            if (row == null && isNordtal(jar)) {
                described.name = String.valueOf(Topology.NORDTAL_PLUGINS.get(prefix));
                described.rank = rankOf(prefix);
            }
            described.group = row != null
                    ? AgentWire.PluginGroup.ADDED
                    : isNordtal(jar) ? AgentWire.PluginGroup.NORDTAL : AgentWire.PluginGroup.PREINSTALLED;
            rows.add(described);
        }
        return rows;
    }

    /** The plugins the network gives that are absent from disk, marked not running and with no install action. */
    private List<PluginRow> absentRows(
            final Topology.Service service,
            final Installation installed,
            final Map<String, Modrinth.Project> published) {
        final List<PluginRow> rows = new ArrayList<>();
        final List<String> absent = absentFixed(service, installed.plugins(), published, fixedProjects);
        final Map<String, Modrinth.Project> titles = identity.projects(
                absent.stream().map(fixedProjects::get).filter(Objects::nonNull).toList());
        for (final String artifact : absent) {
            final String nordtal = nordtalPrefixOf(service, artifact);
            final PluginRow described = new PluginRow(
                    nordtal != null ? String.valueOf(Topology.NORDTAL_PLUGINS.get(nordtal)) : artifact,
                    nordtal != null ? AgentWire.PluginGroup.NORDTAL : AgentWire.PluginGroup.PREINSTALLED);
            described.artifact = artifact;
            final Modrinth.Project project = titles.get(fixedProjects.getOrDefault(artifact, ""));
            if (project != null) {
                described.publishedAs(project);
            }
            if (nordtal != null) {
                described.rank = rankOf(nordtal);
            }
            rows.add(described);
        }
        return rows;
    }

    /** A row nothing on disk answered for is not installed yet, even if its jar was deleted underneath it. */
    private List<PluginRow> notYetInstalledRows(final List<ManagedPlugin> added, final List<String> claimed) {
        final List<PluginRow> rows = new ArrayList<>();
        for (final ManagedPlugin plugin : added) {
            if (!claimed.contains(plugin.artifact())) {
                final PluginRow described = describe(plugin, plugin.filePrefix(), null, false);
                described.group = AgentWire.PluginGroup.ADDED;
                rows.add(described);
            }
        }
        return rows;
    }

    private static PluginRow describe(
            final @Nullable ManagedPlugin plugin,
            final @Nullable String prefix,
            final Installation.@Nullable Jar jar,
            final boolean running) {
        // The title when there is a row, else the filename prefix, never the artefact id.
        final PluginRow row = new PluginRow(
                plugin != null ? plugin.title() : prefix == null ? jarName(jar) : prefix,
                AgentWire.PluginGroup.PREINSTALLED);
        row.running = running;
        // What may be deleted is exactly what has a row.
        row.removable = plugin != null;
        row.filePrefix = prefix;
        if (jar != null) {
            row.fileName = jar.fileName();
            row.version = jar.version();
            // Read before anything is deleted, since the confirmation must name it; no descriptor, no field.
            row.dataFolder = PluginFolder.nameIn(jar.path());
        }
        if (plugin != null) {
            row.artifact = plugin.artifact();
            row.projectId = plugin.projectId();
            row.added = plugin.added();
            row.addedBy = plugin.addedBy();
            row.iconUrl = plugin.iconUrl();
            row.pageUrl = plugin.pageUrl();
        }
        return row;
    }

    /**
     * The rows with the release whose run moved each jar into place, by file name.
     *
     * A jar nobody noted, such as one copied in by hand or installed before the note existed, gets none.
     */
    static List<AgentWire.Plugin> noteReleases(final List<AgentWire.Plugin> rows, final Map<String, String> releases) {
        return rows.stream()
                .map(row -> row.fileName() == null ? row : row.released(releases.get(row.fileName())))
                .toList();
    }

    /** One plugin row while the three sources fill it in; {@link #wire} is what leaves the agent. */
    private static final class PluginRow {
        private String name;
        private AgentWire.PluginGroup group;
        private @Nullable Integer rank;
        private boolean running;
        private boolean removable;
        private @Nullable String filePrefix;
        private @Nullable String fileName;
        private @Nullable String version;
        private @Nullable String dataFolder;
        private @Nullable String artifact;
        private @Nullable String projectId;
        private @Nullable Instant added;
        private @Nullable String addedBy;
        private @Nullable String iconUrl;
        private @Nullable String pageUrl;

        PluginRow(final String name, final AgentWire.PluginGroup group) {
            this.name = name;
            this.group = group;
        }

        /** Named and pictured as Modrinth publishes it. */
        void publishedAs(final Modrinth.Project project) {
            name = project.title();
            projectId = project.projectId();
            iconUrl = icon(project.iconUrl());
            pageUrl = project.pageUrl();
        }

        AgentWire.Plugin wire() {
            return new AgentWire.Plugin(
                    name,
                    group,
                    rank,
                    running,
                    removable,
                    filePrefix,
                    fileName,
                    version,
                    null,
                    dataFolder,
                    artifact,
                    projectId,
                    added,
                    addedBy,
                    iconUrl,
                    pageUrl);
        }
    }

    private static @Nullable ManagedPlugin ownerOf(final Installation.Jar jar, final List<ManagedPlugin> added) {
        final String prefix = jar.prefix();
        return prefix == null
                ? null
                : added.stream()
                        .filter(plugin -> plugin.filePrefix().equals(prefix))
                        .findFirst()
                        .orElse(null);
    }

    /** A jar Nordtal publishes: a season jar ({@code smp-0.9.5.jar}) or the name-tag fork. */
    private static boolean isNordtal(final Installation.Jar jar) {
        return Topology.isNordtal(jar.prefix());
    }

    private static int rankOf(final @Nullable String prefix) {
        return List.copyOf(Topology.NORDTAL_PLUGINS.keySet()).indexOf(prefix);
    }

    /**
     * The service's fixed plugins that are not on the disk, in topology order.
     *
     * Modrinth ones match by identified project, else by a jar prefix starting with the artefact id.
     */
    static List<String> absentFixed(
            final Topology.Service service,
            final List<Installation.Jar> jars,
            final Map<String, Modrinth.Project> identified,
            final Map<String, String> fixedProjects) {
        final List<String> absent = new ArrayList<>();
        for (final String artifact : service.plugins()) {
            final String nordtal = nordtalPrefixOf(service, artifact);
            final String project = fixedProjects.get(artifact);
            final boolean present;
            if (nordtal != null) {
                present = jars.stream().anyMatch(jar -> nordtal.equals(jar.prefix()));
            } else if (project != null) {
                present = identified.values().stream().anyMatch(found -> project.equals(found.projectId()))
                        || jars.stream()
                                .anyMatch(jar -> jar.prefix() != null
                                        && jar.prefix()
                                                .toLowerCase(java.util.Locale.ROOT)
                                                .startsWith(artifact));
            } else {
                continue;
            }
            if (!present) {
                absent.add(artifact);
            }
        }
        return absent;
    }

    private static String jarName(final Installation.@Nullable Jar jar) {
        return jar == null ? "?" : jar.fileName();
    }

    /** {@code GET /api/services/{name}/plugins/search?q=} */
    public void search(final Context ctx) {
        final Topology.Service service = serviceOf(ctx.pathParam("name"));
        // Blank is allowed: an empty search box shows the popular plugins.
        final String query = Optional.ofNullable(ctx.queryParam("q")).orElse("");
        final List<Modrinth.Hit> hits;
        try {
            hits = modrinth.search(query, gameVersion, service.kind().modrinthLoader());
        } catch (final IOException failed) {
            // 502, not 500: what failed is somebody else's API.
            throw new BadGatewayResponse("Modrinth could not be searched: " + failed.getMessage());
        }

        final List<ManagedPlugin> added = plugins.on(service.name());
        final List<AgentWire.PluginHit> rows = new ArrayList<>();
        for (final Modrinth.Hit hit : hits) {
            rows.add(new AgentWire.PluginHit(
                    hit.projectId(),
                    hit.slug(),
                    hit.title(),
                    hit.description(),
                    icon(hit.iconUrl()),
                    hit.pageUrl(),
                    hit.downloads(),
                    // Both kinds of "already there": added by an admin, or given by the network.
                    added.stream().anyMatch(plugin -> plugin.artifact().equals(hit.slug())),
                    service.plugins().contains(Topology.addedArtifact(hit.slug(), service.kind()))));
        }
        ctx.json(new AgentWire.PluginSearch(service.name(), service.kind().modrinthLoader(), gameVersion, query, rows));
    }

    /** What a browser may send to {@code POST /api/services/{name}/plugins}. */
    public static final class Ask {
        public @Nullable String projectId;
        public @Nullable String slug;
        public @Nullable String title;
        public @Nullable String iconUrl;
    }

    /**
     * {@code POST /api/services/{name}/plugins}: the Install button, which writes a row the next run fulfils.
     *
     * It resolves the newest build first, so the dialog says when none fits; {@code by} comes from the session.
     */
    public void add(final Context ctx, final String by) {
        final Topology.Service service = serviceOf(ctx.pathParam("name"));
        final Ask ask = ctx.bodyAsClass(Ask.class);
        if (ask == null || ask.projectId == null || ask.projectId.isBlank() || ask.slug == null || ask.slug.isBlank()) {
            throw new BadRequestResponse("projectId and slug are which Modrinth project to install");
        }
        final String projectId = ask.projectId.strip();
        final String rawSlug = ask.slug.strip();
        final Addition addition = resolveAddition(service, rawSlug, projectId);

        final String title = blankToNull(ask.title);
        plugins.add(new ManagedPlugin(
                service.name(),
                addition.slug(),
                projectId,
                addition.prefix(),
                title == null ? addition.slug() : title,
                icon(ask.iconUrl),
                // Built here, never from the body: a request may only choose the Modrinth project.
                "https://modrinth.com/plugin/" + addition.slug(),
                clock.instant(),
                by));

        log.info(
                "{} added {} ({}) to {} - it installs with the next run as {}",
                by,
                addition.slug(),
                projectId,
                service.name(),
                addition.newest().fileName());

        ctx.status(201)
                .json(new AgentWire.PluginAdded(
                        service.name(),
                        addition.slug(),
                        addition.prefix(),
                        addition.newest().fileName(),
                        addition.newest().version()));
    }

    /** What {@link #resolveAddition} found: enough to write the row and answer the request. */
    private record Addition(String slug, String artifact, RemoteFile newest, String prefix) {}

    /** Validates the slug, refuses a plugin the network already gives, and asks Modrinth for the newest build. */
    private Addition resolveAddition(final Topology.Service service, final String slug, final String projectId) {
        if (!slug.matches("[A-Za-z0-9!@$()`.+,_\"-]+")) {
            // Modrinth's own slug alphabet, since this becomes an artefact id and a filename.
            throw new BadRequestResponse(slug + " is not a Modrinth slug");
        }

        final String artifact = Topology.addedArtifact(slug, service.kind());
        if (service.plugins().contains(artifact)) {
            throw new ConflictResponse(service.name() + " already runs " + artifact
                    + " because the network gives it. compose.yml's eu.nordtal.plugins label names it, so it"
                    + " cannot be added or removed from here.");
        }

        final RemoteFile newest;
        try {
            newest = modrinth.newest(
                    artifact, projectId, gameVersion, service.kind().modrinthLoader());
        } catch (final Modrinth.Unsupported none) {
            throw new ConflictResponse(none.getMessage());
        } catch (final IOException failed) {
            throw new BadGatewayResponse("Modrinth could not be asked for " + slug + ": " + failed.getMessage());
        }

        final String prefix = JarName.prefixOf(newest.fileName());
        if (prefix == null) {
            // Refused rather than guessed: without a prefix nothing could find this jar again to remove it.
            throw new ConflictResponse(newest.fileName() + " does not split into a name and a"
                    + " version, so this installation could never be undone. See JarName.");
        }
        return new Addition(slug, artifact, newest, prefix);
    }

    /**
     * Removes an added plugin, jar and folder, while a run holds its server stopped; the row goes last.
     *
     * @return what was deleted, empty when nothing had been installed yet
     * @throws io.javalin.http.HttpResponseException naming what is wrong; nothing past it was deleted
     */
    public List<String> remove(final String name, final String artifact) {
        final Topology.Service service = serviceOf(name);
        final ManagedPlugin plugin = plugins.on(service.name()).stream()
                .filter(one -> one.artifact().equals(artifact))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse(service.name() + " has no added plugin "
                        + artifact + ". The plugins the network gives are not in this list and"
                        + " cannot be removed."));

        final List<String> deleted = new ArrayList<>();
        final Installation installed = scan(service.name());
        String folder = null;
        for (final Installation.Jar jar : installed.plugins()) {
            if (!plugin.filePrefix().equals(jar.prefix())) {
                continue;
            }
            if (folder == null) {
                folder = PluginFolder.nameIn(jar.path());
            }
            try {
                Files.deleteIfExists(jar.path());
                deleted.add(jar.fileName());
            } catch (final IOException failed) {
                throw new InternalServerErrorResponse("could not delete " + jar.fileName() + ": "
                        + failed.getMessage() + ". The plugin is still in the list; nothing else"
                        + " was deleted.");
            }
        }

        if (folder != null) {
            final Path directory = pluginsDirectory(service.name()).resolve(folder);
            // A name from a jar somebody else wrote is checked, since "../../world" is one.
            if (!directory
                    .normalize()
                    .startsWith(pluginsDirectory(service.name()).normalize())) {
                throw new ConflictResponse(
                        folder + " is not a name a data folder may have." + " Nothing was deleted beyond the jar.");
            }
            try {
                deleteTree(directory);
                deleted.add(folder + "/");
            } catch (final IOException failed) {
                throw new InternalServerErrorResponse("the jar is gone but " + folder
                        + "/ could not be deleted: " + failed.getMessage()
                        + ". The plugin is still in the list, so this can be pressed again.");
            }
        }

        plugins.remove(service.name(), artifact);
        log.info(
                "{} was removed from {} - deleted: {}",
                artifact,
                service.name(),
                deleted.isEmpty() ? "nothing, it had not been installed yet" : String.join(", ", deleted));
        return List.copyOf(deleted);
    }

    private Topology.Service serviceOf(final String name) {
        return servers.get().stream()
                .filter(service -> service.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse(name + " is not a Minecraft service."
                        + " Only a service compose.yml labels eu.nordtal.server has a plugins folder."));
    }

    /** The filename prefix of a fixed artefact Nordtal publishes on this service, or none for another's. */
    private static @Nullable String nordtalPrefixOf(final Topology.Service service, final String artifact) {
        final String prefix = service.prefixOf(artifact);
        return Topology.isNordtal(prefix) ? prefix : null;
    }

    private Path pluginsDirectory(final String service) {
        if (volumesRoot == null) {
            throw new ConflictResponse(
                    "steward-agent has no volumes mounted, so it cannot see any service's plugins folder");
        }
        return volumesRoot.resolve(service).resolve(Installation.PLUGINS);
    }

    private Installation scan(final String service) {
        if (volumesRoot == null) {
            return Installation.absent(service, Path.of(service));
        }
        try {
            return Installation.scan(service, volumesRoot.resolve(service));
        } catch (final IOException failed) {
            // The same reading Resolver takes: an unlistable directory is a mount problem, not an empty server.
            log.warn("Could not read {}'s volume: {}", service, failed.getMessage());
            return Installation.absent(service, volumesRoot.resolve(service));
        }
    }

    /** Depth-first, because {@link Files#delete} refuses a directory with anything in it. */
    private static void deleteTree(final Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (final Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static @Nullable String icon(final @Nullable String url) {
        final String value = blankToNull(url);
        return value != null && value.startsWith(ICON_HOST) ? value : null;
    }

    private static @Nullable String blankToNull(final @Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

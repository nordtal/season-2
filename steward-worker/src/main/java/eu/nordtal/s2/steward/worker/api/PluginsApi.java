package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.plugin.ManagedPlugin;
import eu.nordtal.s2.common.plugin.PluginDirectory;
import eu.nordtal.s2.steward.worker.plan.Installation;
import eu.nordtal.s2.steward.worker.plan.JarName;
import eu.nordtal.s2.steward.worker.plan.PluginFolder;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.source.Modrinth;
import eu.nordtal.s2.steward.worker.source.RemoteFile;
import io.javalin.http.BadGatewayResponse;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The four routes behind "the plugins on this server".
 *
 * The list is read off the disk, and the table only says what may be removed: What is installed is the jars in the
 * volume - that is {@code Installation} 's rule and it is not weakened here. So {@link #list} walks {@code plugins/}
 * and then asks {@code service_plugin} which of those jars somebody added from the interface. A jar no row claims is
 * one the network gives, and it carries no remove button because there is no row to delete. That is the enforcement,
 * not a greyed-out control.
 *
 * The unclaimed jars fall into two groups by name: a season jar ( {@code smp-0.9.5.jar}) is a Nordtal plugin,
 * anything else is preinstalled. A jar somebody copied into the volume by hand lands in the second group too; the
 * place it is meant to become visible is the update plan, which lists it under {@code unclaimed}. Whether a
 * preinstalled jar came from Modrinth is told by its hash ( {@link JarIdentity}), which is what gives it a name, a
 * picture and a link.
 *
 * A row with no jar is not installed: installing is asking, not doing. The row is written and the jar arrives with
 * the next update run that can fetch it. So a row whose {@code file_prefix} matches nothing on
 * disk is drawn as not installed, and so is a plugin the network gives that is not on the disk - a list that claimed
 * the plugin was running would be describing a server that does not have it.
 *
 * Removing deletes the folder too, which is why the answer names it, even though
 * {@code plugins/<name>/} is the only hand-edited thing in the whole installation. The consequence taken on with it
 * is that the confirmation has to name the directory, so {@link #list} reads it out of each jar's own descriptor (
 * {@link PluginFolder}) and hands it over before anybody presses anything.
 */
public final class PluginsApi {

    private static final Logger log = LoggerFactory.getLogger(PluginsApi.class);

    /**
     * The only host an icon may be loaded from.
     *
     * Why the URL is checked here and not trusted from the browser: The add request carries what a search hit said, and
     * a request can say anything. An {@code icon_url} that is stored and then rendered as {@code <img src>} on an admin
     * page behind two factors is a beacon somebody else controls - it reports every visit, and it is the one field of
     * this row that a browser fetches by itself. Modrinth serves every project icon from this host, so anything else is
     * dropped and the plugin is drawn without a picture.
     */
    private static final String ICON_HOST = "https://cdn.modrinth.com/";

    private final PluginDirectory plugins;
    private final Modrinth modrinth;
    private final @Nullable Path volumesRoot;
    private final String gameVersion;
    private final JarIdentity identity;
    private final Map<String, String> fixedProjects;

    /**
     * @param volumesRoot where the services' volumes are mounted in this container, or {@code null}
     *                    in a deployment that mounts none - the list then says the mount is missing
     *                    rather than reporting an empty server
     * @param gameVersion the Minecraft version every search and every resolve is filtered to,
     *                    which is {@code Platform#MINECRAFT}
     */
    public PluginsApi(
            final PluginDirectory plugins,
            final Modrinth modrinth,
            final @Nullable Path volumesRoot,
            final String gameVersion) {
        this(plugins, modrinth, volumesRoot, gameVersion, Map.of());
    }

    /**
     * @param fixedProjects the Modrinth project id of each plugin the network gives from Modrinth,
     *                      keyed by artefact id - what names and draws one that is not on the disk
     */
    public PluginsApi(
            final PluginDirectory plugins,
            final Modrinth modrinth,
            final @Nullable Path volumesRoot,
            final String gameVersion,
            final Map<String, String> fixedProjects) {
        this.fixedProjects = Map.copyOf(fixedProjects);
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.modrinth = Objects.requireNonNull(modrinth, "modrinth");
        this.volumesRoot = volumesRoot;
        this.gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        this.identity = new JarIdentity(modrinth);
    }

    /** {@code GET /api/services/{name}/plugins} */
    public void list(final Context ctx) {
        final Topology.Service service = serviceOf(ctx.pathParam("name"));
        final List<ManagedPlugin> added = plugins.on(service.name());
        final Installation installed = scan(service.name());

        // The jars no row claims are the ones the network gives; Modrinth-published ones drawn like an added plugin.
        final List<Installation.Jar> given = installed.plugins().stream()
                .filter(jar -> ownerOf(jar, added) == null && !isNordtal(jar))
                .toList();
        final Map<String, Modrinth.Project> published = identity.identify(given);

        final List<String> claimed = new ArrayList<>();
        final List<Map<String, Object>> rows = new ArrayList<>(installedRows(installed, added, published, claimed));
        rows.addAll(absentRows(service, installed, published));
        rows.addAll(notYetInstalledRows(added, claimed));

        rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase(java.util.Locale.ROOT)));

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("service", service.name());
        answer.put("loader", service.kind().modrinthLoader());
        answer.put("gameVersion", gameVersion);
        answer.put("mounted", installed.mounted());
        answer.put("plugins", rows);
        ctx.json(answer);
    }

    /** One row per jar in the volume, name and picture filled in from a row or from Modrinth where either answers. */
    private List<Map<String, Object>> installedRows(
            final Installation installed,
            final List<ManagedPlugin> added,
            final Map<String, Modrinth.Project> published,
            final List<String> claimed) {
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final Installation.Jar jar : installed.plugins()) {
            final String prefix = jar.prefix();
            final ManagedPlugin row = ownerOf(jar, added);
            if (row != null) {
                claimed.add(row.artifact());
            }
            final Map<String, Object> described = describe(row, prefix, jar, true);
            final Modrinth.Project project = published.get(jar.fileName());
            if (project != null) {
                described.put("name", project.title());
                described.put("projectId", project.projectId());
                described.put("iconUrl", icon(project.iconUrl()));
                described.put("pageUrl", project.pageUrl());
            }
            if (row == null && isNordtal(jar)) {
                described.put("name", Topology.NORDTAL_PLUGINS.get(prefix));
                described.put("rank", rankOf(prefix));
            }
            described.put("group", row != null ? "added" : isNordtal(jar) ? "nordtal" : "preinstalled");
            rows.add(described);
        }
        return rows;
    }

    /**
     * The plugins the network gives that are absent from disk.
     *
     * Named and drawn like the ones that are, marked not running; no install action, since the next update run is
     * what puts them there, if it can.
     */
    private List<Map<String, Object>> absentRows(
            final Topology.Service service,
            final Installation installed,
            final Map<String, Modrinth.Project> published) {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<String> absent = absentFixed(service, installed.plugins(), published, fixedProjects);
        final Map<String, Modrinth.Project> titles = identity.projects(
                absent.stream().map(fixedProjects::get).filter(Objects::nonNull).toList());
        for (final String artifact : absent) {
            final Map<String, Object> described = new LinkedHashMap<>();
            final String nordtal = Topology.nordtalPrefixOf(artifact);
            described.put("name", nordtal != null ? Topology.NORDTAL_PLUGINS.get(nordtal) : artifact);
            described.put("running", false);
            described.put("removable", false);
            described.put("artifact", artifact);
            final Modrinth.Project project = titles.get(fixedProjects.getOrDefault(artifact, ""));
            if (project != null) {
                described.put("name", project.title());
                described.put("projectId", project.projectId());
                described.put("iconUrl", icon(project.iconUrl()));
                described.put("pageUrl", project.pageUrl());
            }
            if (nordtal != null) {
                described.put("rank", rankOf(nordtal));
            }
            described.put("group", nordtal != null ? "nordtal" : "preinstalled");
            rows.add(described);
        }
        return rows;
    }

    /**
     * A row nothing on disk answered for is not installed yet.
     *
     * That includes one whose jar was deleted underneath it: the row is the wish, and the wish is still there.
     */
    private List<Map<String, Object>> notYetInstalledRows(final List<ManagedPlugin> added, final List<String> claimed) {
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final ManagedPlugin plugin : added) {
            if (!claimed.contains(plugin.artifact())) {
                final Map<String, Object> described = describe(plugin, plugin.filePrefix(), null, false);
                described.put("group", "added");
                rows.add(described);
            }
        }
        return rows;
    }

    private Map<String, Object> describe(
            final @Nullable ManagedPlugin plugin,
            final @Nullable String prefix,
            final Installation.@Nullable Jar jar,
            final boolean running) {
        final Map<String, Object> row = new LinkedHashMap<>();
        // The title when there is a row, else the filename prefix - never the artefact id ("wg-bukkit" vs "WorldGuard")
        row.put("name", plugin != null ? plugin.title() : prefix == null ? jarName(jar) : prefix);
        row.put("running", running);
        // The whole point of the pair: what may be deleted is exactly what has a row.
        row.put("removable", plugin != null);
        if (prefix != null) {
            row.put("filePrefix", prefix);
        }
        if (jar != null) {
            row.put("fileName", jar.fileName());
            if (jar.version() != null) {
                row.put("version", jar.version());
            }
            // Read now, before anything is deleted, since the confirmation must say it aloud; no descriptor, no field.
            final String folder = PluginFolder.nameIn(jar.path());
            if (folder != null) {
                row.put("dataFolder", folder);
            }
        }
        if (plugin != null) {
            row.put("artifact", plugin.artifact());
            row.put("projectId", plugin.projectId());
            row.put("added", plugin.added().toString());
            row.put("addedBy", plugin.addedBy());
            row.put("iconUrl", plugin.iconUrl());
            row.put("pageUrl", plugin.pageUrl());
        }
        return row;
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
     * A Nordtal jar is there when its filename prefix is. A Modrinth one is there when a jar was identified as
     * that project, or - for when Modrinth could not be asked - when a jar's prefix starts with the artefact id
     * ( {@code CoreProtect-CE}, {@code voicechat-bukkit}). A fixed plugin that is neither, the platform itself
     * for one, is not listed at all.
     */
    static List<String> absentFixed(
            final Topology.Service service,
            final List<Installation.Jar> jars,
            final Map<String, Modrinth.Project> identified,
            final Map<String, String> fixedProjects) {
        final List<String> absent = new ArrayList<>();
        for (final String artifact : service.plugins()) {
            final String nordtal = Topology.nordtalPrefixOf(artifact);
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
        // Blank is allowed and not an error: an empty search box shows the popular plugins, not a typing prompt.
        final String query = Optional.ofNullable(ctx.queryParam("q")).orElse("");
        final List<Modrinth.Hit> hits;
        try {
            hits = modrinth.search(query, gameVersion, service.kind().modrinthLoader());
        } catch (final IOException failed) {
            // 502, not 500: what failed is somebody else's API, and the difference decides who checks this log.
            throw new BadGatewayResponse("Modrinth could not be searched: " + failed.getMessage());
        }

        final List<ManagedPlugin> added = plugins.on(service.name());
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final Modrinth.Hit hit : hits) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", hit.projectId());
            row.put("slug", hit.slug());
            row.put("title", hit.title());
            row.put("description", hit.description());
            row.put("iconUrl", icon(hit.iconUrl()));
            row.put("pageUrl", hit.pageUrl());
            row.put("downloads", hit.downloads());
            // Both kinds of "already there" told apart: one added last week, the other given by the network.
            row.put("added", added.stream().anyMatch(plugin -> plugin.artifact().equals(hit.slug())));
            row.put("fixed", service.plugins().contains(Topology.addedArtifact(hit.slug(), service.kind())));
            rows.add(row);
        }
        ctx.json(Map.of(
                "service",
                service.name(),
                "loader",
                service.kind().modrinthLoader(),
                "gameVersion",
                gameVersion,
                "query",
                query,
                "hits",
                rows));
    }

    /** What a browser may send to {@code POST /api/services/{name}/plugins}. */
    public static final class Ask {
        public @Nullable String projectId;
        public @Nullable String slug;
        public @Nullable String title;
        public @Nullable String iconUrl;
        /**
         * Who pressed it, as steward-ui knows them.
         *
         * Taken from the body rather than a header because steward-ui is the only caller and the only process with
         * a session. The worker's own token says the request is allowed; this says whose name goes on the row.
         */
        public @Nullable String by;
    }

    /**
     * {@code POST /api/services/{name}/plugins} - the button that says Install.
     *
     * It resolves before it writes, and that is the load-bearing part: a search hit is a claim about a project;
     * {@code Modrinth#newest} is an answer about a version, and only the second one can say whether there is a
     * build for this Minecraft version on this loader. Asking it here means an admin finds out while looking at
     * the dialog, instead of the row becoming a plugin that silently never installs. It also produces the
     * filename prefix, which is the one thing the removal later cannot work out for itself.
     */
    public void add(final Context ctx) {
        final Topology.Service service = serviceOf(ctx.pathParam("name"));
        final Ask ask = ctx.bodyAsClass(Ask.class);
        if (ask == null || ask.projectId == null || ask.projectId.isBlank() || ask.slug == null || ask.slug.isBlank()) {
            throw new BadRequestResponse("projectId and slug are which Modrinth project to install");
        }
        final String projectId = ask.projectId.strip();
        final String rawSlug = ask.slug.strip();
        final Addition addition = resolveAddition(service, rawSlug, projectId);

        final String title = blankToNull(ask.title);
        final String by = blankToNull(ask.by);
        plugins.add(new ManagedPlugin(
                service.name(),
                addition.slug(),
                projectId,
                addition.prefix(),
                title == null ? addition.slug() : title,
                icon(ask.iconUrl),
                // Built here, never from the body: a request may only decide which Modrinth project this points at.
                "https://modrinth.com/plugin/" + addition.slug(),
                java.time.Instant.now(),
                by));

        log.info(
                "{} added {} ({}) to {} - it installs with the next run as {}",
                by == null ? "somebody" : by,
                addition.slug(),
                projectId,
                service.name(),
                addition.newest().fileName());

        ctx.status(201)
                .json(Map.of(
                        "service",
                        service.name(),
                        "artifact",
                        addition.slug(),
                        "filePrefix",
                        addition.prefix(),
                        // What would arrive, so the interface can say it rather than "ok".
                        "fileName",
                        addition.newest().fileName(),
                        "version",
                        addition.newest().version(),
                        "running",
                        false));
    }

    /** What {@link #resolveAddition} found: enough to write the row and answer the request. */
    private record Addition(String slug, String artifact, RemoteFile newest, String prefix) {}

    /** Validates the slug, refuses a plugin the network already gives, and asks Modrinth for the newest build. */
    private Addition resolveAddition(final Topology.Service service, final String slug, final String projectId) {
        if (!slug.matches("[A-Za-z0-9!@$()`.+,_\"-]+")) {
            // Modrinth's own slug alphabet - checked because this becomes an artefact id in a report line and filename.
            throw new BadRequestResponse(slug + " is not a Modrinth slug");
        }

        final String artifact = Topology.addedArtifact(slug, service.kind());
        if (service.plugins().contains(artifact)) {
            throw new ConflictResponse(service.name() + " already runs " + artifact
                    + " because the network gives it. It is in Topology.SERVICES and cannot be"
                    + " added or removed from here.");
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
            // Refused rather than stored with a guess: without a prefix nothing can find this jar again to remove it.
            throw new ConflictResponse(newest.fileName() + " does not split into a name and a"
                    + " version, so this installation could never be undone. See JarName.");
        }
        return new Addition(slug, artifact, newest, prefix);
    }

    /**
     * {@code DELETE /api/services/{name}/plugins/{artifact}} - the jar and the folder, both.
     *
     * The files go first and the row goes last: if deleting the folder fails - a permission on the mount, a file
     * being written - the row is still there, so the plugin still appears in the list and the button can be
     * pressed again. The other order would leave a jar nothing knows about, which the next plan would report as
     * unclaimed and nobody could remove from here.
     */
    public void remove(final Context ctx) {
        final Topology.Service service = serviceOf(ctx.pathParam("name"));
        final String artifact = ctx.pathParam("artifact");
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
            // resolve() on a name from a jar somebody else wrote, checked rather than trusted: "../../world" is one.
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
        ctx.json(Map.of("service", service.name(), "artifact", artifact, "deleted", deleted));
    }

    private Topology.Service serviceOf(final String name) {
        return Topology.SERVICES.stream()
                .filter(service -> service.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse(name + " is not a Minecraft service."
                        + " Only the four in Topology.SERVICES have a plugins folder."));
    }

    private Path pluginsDirectory(final String service) {
        if (volumesRoot == null) {
            throw new ConflictResponse(
                    "this worker has no volumes mounted, so it cannot see any" + " service's plugins folder");
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

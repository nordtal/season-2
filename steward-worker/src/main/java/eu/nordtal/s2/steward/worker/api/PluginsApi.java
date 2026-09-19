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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * The four routes behind "the plugins on this server" (season-2-ops/129).
 *
 * <h2>The list is read off the disk, and the table only says what may be removed</h2>
 * What is installed is the jars in the volume - that is {@code Installation}'s rule and it is not
 * weakened here. So {@link #list} walks {@code plugins/} and then asks {@code service_plugin} which
 * of those jars somebody added from the interface. A jar no row claims is one the network gives,
 * and it carries no remove button because there is no row to delete. <b>That is the enforcement,
 * not a greyed-out control.</b>
 *
 * <p>The one thing this arrangement cannot tell apart is a Nordtal plugin from a jar somebody
 * copied into the volume by hand: both are "on disk and unclaimed". That is deliberate rather than
 * overlooked - the place a hand-placed jar is meant to become visible is the update plan, which
 * lists it under {@code unclaimed}, and duplicating that judgement here would be a second opinion
 * about the same folder.</p>
 *
 * <h2>Pre-booked is a row with no jar</h2>
 * Installing is asking, not doing (owner, 2026-09-19): the row is written and the jar arrives with
 * the next update run. So a row whose {@code file_prefix} matches nothing on disk is drawn as
 * pre-booked, and the interface must show that difference - a list that claimed the plugin was
 * running would be describing a server that does not have it.
 *
 * <h2>Removing deletes the folder too, which is why the answer names it</h2>
 * Till chose that against the objection that {@code plugins/&lt;name&gt;/} is the only hand-edited
 * thing in the whole installation. The consequence taken on with it is that the confirmation has to
 * name the directory, so {@link #list} reads it out of each jar's own descriptor
 * ({@link PluginFolder}) and hands it over before anybody presses anything.
 */
public final class PluginsApi {

    private static final Logger log = LoggerFactory.getLogger(PluginsApi.class);

    /**
     * The only host an icon may be loaded from.
     *
     * <h2>Why the URL is checked here and not trusted from the browser</h2>
     * The add request carries what a search hit said, and a request can say anything. An
     * {@code icon_url} that is stored and then rendered as {@code &lt;img src&gt;} on an admin page
     * behind two factors is a beacon somebody else controls - it reports every visit, and it is the
     * one field of this row that a browser fetches by itself. Modrinth serves every project icon
     * from this host (checked against the live API, 2026-09-19), so anything else is dropped and
     * the plugin is drawn without a picture.
     */
    private static final String ICON_HOST = "https://cdn.modrinth.com/";

    private final PluginDirectory plugins;
    private final Modrinth modrinth;
    private final @Nullable Path volumesRoot;
    private final String gameVersion;

    /**
     * @param volumesRoot where the services' volumes are mounted in this container, or {@code null}
     *                    in a deployment that mounts none - the list then says the mount is missing
     *                    rather than reporting an empty server
     * @param gameVersion the Minecraft version every search and every resolve is filtered to,
     *                    which is {@code Platform#MINECRAFT}
     */
    public PluginsApi(final @NotNull PluginDirectory plugins, final @NotNull Modrinth modrinth,
                      final @Nullable Path volumesRoot, final @NotNull String gameVersion) {
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.modrinth = Objects.requireNonNull(modrinth, "modrinth");
        this.volumesRoot = volumesRoot;
        this.gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
    }

    // ---------------------------------------------------------------- the list

    /** {@code GET /api/services/{name}/plugins} */
    public void list(final @NotNull Context ctx) {
        final Topology.Service service = serviceOf(ctx.pathParam("name"));
        final List<ManagedPlugin> added = plugins.on(service.name());

        final Installation installed = scan(service.name());
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<String> claimed = new ArrayList<>();

        for (final Installation.Jar jar : installed.plugins()) {
            final String prefix = jar.prefix();
            final ManagedPlugin row = prefix == null ? null : added.stream()
                    .filter(plugin -> plugin.filePrefix().equals(prefix))
                    .findFirst().orElse(null);
            if (row != null) {
                claimed.add(row.artifact());
            }
            rows.add(describe(row, prefix, jar, true));
        }

        // Every row nothing on disk answered for. These are the pre-booked ones - and a row whose
        // jar was deleted underneath it lands here too, which is the right reading: the next run
        // installs it again, because the row is the wish and the wish is still there.
        for (final ManagedPlugin plugin : added) {
            if (!claimed.contains(plugin.artifact())) {
                rows.add(describe(plugin, plugin.filePrefix(), null, false));
            }
        }

        rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase(java.util.Locale.ROOT)));

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("service", service.name());
        answer.put("loader", service.kind().modrinthLoader());
        answer.put("gameVersion", gameVersion);
        answer.put("mounted", installed.mounted());
        answer.put("plugins", rows);
        ctx.json(answer);
    }

    private Map<String, Object> describe(final @Nullable ManagedPlugin plugin, final @Nullable String prefix,
                                         final @Nullable Installation.Jar jar, final boolean running) {
        final Map<String, Object> row = new LinkedHashMap<>();
        // The title when there is a row, the filename prefix when there is not. Never the artefact
        // id for an added plugin: "worldedit-bukkit" is what the file is called, "WorldEdit" is
        // what it is.
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
            // Read now, while nothing is being deleted, because this is what the confirmation has
            // to be able to say out loud. A jar with no readable descriptor simply has no field -
            // the interface then says it cannot name the folder, which is the honest sentence.
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

    private static String jarName(final @Nullable Installation.Jar jar) {
        return jar == null ? "?" : jar.fileName();
    }

    // ---------------------------------------------------------------- the search

    /** {@code GET /api/services/{name}/plugins/search?q=} */
    public void search(final @NotNull Context ctx) {
        final Topology.Service service = serviceOf(ctx.pathParam("name"));
        // Blank is allowed and is not an error: an empty search box should show the popular
        // plugins for this platform, not a message about having typed nothing.
        final String query = Optional.ofNullable(ctx.queryParam("q")).orElse("");
        final List<Modrinth.Hit> hits;
        try {
            hits = modrinth.search(query, gameVersion, service.kind().modrinthLoader());
        } catch (final IOException failed) {
            // 502 and not 500: the thing that failed is somebody else's API, and the difference
            // decides whether anybody goes looking at this container's log.
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
            // Both kinds of "already there", told apart, because they are different sentences: one
            // is a plugin this admin added last week, the other is a plugin the network gives and
            // nobody may remove.
            row.put("added", added.stream().anyMatch(plugin -> plugin.artifact().equals(hit.slug())));
            row.put("fixed", service.plugins().contains(
                    Topology.addedArtifact(hit.slug(), service.kind())));
            rows.add(row);
        }
        ctx.json(Map.of("service", service.name(), "loader", service.kind().modrinthLoader(),
                "gameVersion", gameVersion, "query", query, "hits", rows));
    }

    // ---------------------------------------------------------------- adding one

    /** What a browser may send to {@code POST /api/services/{name}/plugins}. */
    public static final class Ask {
        public String projectId;
        public String slug;
        public String title;
        public String iconUrl;
        /**
         * Who pressed it, as steward-ui knows them.
         *
         * <p>Taken from the body rather than from a header because steward-ui is the only caller
         * and it is the only process that has a session. The worker's own token is what says the
         * request is allowed; this says whose name goes on the row.</p>
         */
        public String by;
    }

    /**
     * {@code POST /api/services/{name}/plugins} - the button that says Install.
     *
     * <h2>It resolves before it writes, and that is the load-bearing part</h2>
     * A search hit is a claim about a project; {@code Modrinth#newest} is an answer about a
     * version, and only the second one can say whether there is a build for this Minecraft version
     * on this loader. Asking it here means an admin finds out while looking at the dialog, instead
     * of the row becoming a plugin that silently never installs. It also produces the filename
     * prefix, which is the one thing the removal later cannot work out for itself.
     */
    public void add(final @NotNull Context ctx) {
        final Topology.Service service = serviceOf(ctx.pathParam("name"));
        final Ask ask = ctx.bodyAsClass(Ask.class);
        if (ask == null || ask.projectId == null || ask.projectId.isBlank()
                || ask.slug == null || ask.slug.isBlank()) {
            throw new BadRequestResponse("projectId and slug are which Modrinth project to install");
        }
        final String slug = ask.slug.strip();
        if (!slug.matches("[A-Za-z0-9!@$()`.+,_\"-]+")) {
            // Modrinth's own slug alphabet. Checked because this string becomes an artefact id,
            // and an artefact id ends up in a report line and in a filename comparison.
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
            newest = modrinth.newest(artifact, ask.projectId.strip(), gameVersion,
                    service.kind().modrinthLoader());
        } catch (final Modrinth.Unsupported none) {
            throw new ConflictResponse(none.getMessage());
        } catch (final IOException failed) {
            throw new BadGatewayResponse("Modrinth could not be asked for " + slug + ": "
                    + failed.getMessage());
        }

        final String prefix = JarName.prefixOf(newest.fileName());
        if (prefix == null) {
            // Refused rather than stored with a guess. Without a prefix nothing can find this jar
            // again, so removing the plugin later would delete nothing while saying it had.
            throw new ConflictResponse(newest.fileName() + " does not split into a name and a"
                    + " version, so this installation could never be undone. See JarName.");
        }

        plugins.add(new ManagedPlugin(service.name(), slug, ask.projectId.strip(), prefix,
                blankToNull(ask.title) == null ? slug : ask.title.strip(),
                icon(ask.iconUrl),
                // Built here, never taken from the body: this string becomes a link on an admin
                // page, and the only thing a request may decide is which Modrinth project it
                // points at.
                "https://modrinth.com/plugin/" + slug,
                java.time.Instant.now(), blankToNull(ask.by)));

        log.info("{} added {} ({}) to {} - it installs with the next run as {}",
                blankToNull(ask.by) == null ? "somebody" : ask.by, slug, ask.projectId,
                service.name(), newest.fileName());

        ctx.status(201).json(Map.of(
                "service", service.name(),
                "artifact", slug,
                "filePrefix", prefix,
                // What would arrive, so the interface can say it rather than "ok".
                "fileName", newest.fileName(),
                "version", newest.version(),
                "running", false));
    }

    // ---------------------------------------------------------------- removing one

    /**
     * {@code DELETE /api/services/{name}/plugins/{artifact}} - the jar and the folder, both.
     *
     * <h2>The files go first and the row goes last</h2>
     * If deleting the folder fails - a permission on the mount, a file being written - the row is
     * still there, so the plugin still appears in the list and the button can be pressed again.
     * The other order would leave a jar nothing knows about, which the next plan would report as
     * unclaimed and nobody could remove from here.
     */
    public void remove(final @NotNull Context ctx) {
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
            // resolve() on a name read out of a jar somebody else wrote, so the result is checked
            // to still be inside the plugins folder rather than trusted. `name: ../../world` is a
            // descriptor anybody can ship.
            if (!directory.normalize().startsWith(pluginsDirectory(service.name()).normalize())) {
                throw new ConflictResponse(folder + " is not a name a data folder may have."
                        + " Nothing was deleted beyond the jar.");
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
        log.info("{} was removed from {} - deleted: {}", artifact, service.name(),
                deleted.isEmpty() ? "nothing, it had not been installed yet" : String.join(", ", deleted));
        ctx.json(Map.of("service", service.name(), "artifact", artifact, "deleted", deleted));
    }

    // ---------------------------------------------------------------- helpers

    private Topology.Service serviceOf(final String name) {
        return Topology.SERVICES.stream()
                .filter(service -> service.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse(name + " is not a Minecraft service."
                        + " Only the four in Topology.SERVICES have a plugins folder."));
    }

    private Path pluginsDirectory(final String service) {
        if (volumesRoot == null) {
            throw new ConflictResponse("this worker has no volumes mounted, so it cannot see any"
                    + " service's plugins folder");
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
            // The same reading Resolver takes: a directory that exists but cannot be listed is a
            // mount problem and must not read as an empty server.
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

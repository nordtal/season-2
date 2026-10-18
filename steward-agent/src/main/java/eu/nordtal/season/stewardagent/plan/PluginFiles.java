package eu.nordtal.season.stewardagent.plan;

import eu.nordtal.season.common.Sha256;
import eu.nordtal.season.internalapi.InternalServer;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.Topology;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists, reads and saves the text config files of the plugins on one server that are not Nordtal's.
 *
 * Only a listed file is reached; nothing joins a path from the request, so {@code ../} and a link stay a 404.
 */
public final class PluginFiles {

    private static final Logger log = LoggerFactory.getLogger(PluginFiles.class);

    /** A larger file is no config anyone edits in a browser. */
    static final long MAX_SIZE = 1024 * 1024;

    /** How many bytes are looked at for a NUL before a file counts as text. */
    private static final int SNIFF_LENGTH = 8000;

    private static final Set<String> EXTENSIONS = Set.of("yml", "yaml", "json", "properties", "toml", "conf", "txt");

    /** A schema that the settings of an earlier season wrote beside a file. */
    private static final String SCHEMA_SUFFIX = ".schema.json";

    /** Directories whose files are scratch or a log, matched as a whole segment. */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("tmp", "logs", "log");

    /**
     * Names that mark name tags, colours, translations and message bundles, which Nordtal keeps itself.
     *
     * A file or folder inside a plugin's folder whose name holds one is never listed, whichever plugin it is in.
     */
    private static final List<String> NORDTAL_KINDS =
            List.of("nametag", "name-tag", "name_tag", "colour", "color", "translation", "messages", "lang", "locale");

    /** The folder of Nordtal's name tags, which no jar claims. */
    private static final String NAME_TAGS_FOLDER = "DisplayTags";

    private final Function<String, AgentWire.Plugins> plugins;
    private final @Nullable Path volumesRoot;

    /**
     * @param plugins the plugin list of a server, which throws a 404 for a name that is not one
     * @param volumesRoot the mounted volumes, {@code null} when none are
     */
    public PluginFiles(final Function<String, AgentWire.Plugins> plugins, final @Nullable Path volumesRoot) {
        this.plugins = plugins;
        this.volumesRoot = volumesRoot;
    }

    public void list(final Context ctx) {
        final String service = ctx.pathParam("name");
        final AgentWire.Plugins listed = plugins.apply(service);
        final List<AgentWire.PluginFile> files =
                found(service, listed).stream().map(Found::file).toList();
        ctx.json(new AgentWire.PluginFiles(service, listed.mounted(), files));
    }

    public void read(final Context ctx) {
        final Found found = find(ctx);
        if (!found.file().readable()) {
            throw new ForbiddenResponse(
                    found.file().plugin() + "/" + found.file().path() + " cannot be read.");
        }
        final String text = text(found);
        ctx.json(new AgentWire.PluginFileDocument(found.file(), text, Sha256.hex(text)));
    }

    /** Writes the whole file, unless it changed since the revision the save names. The server is not touched. */
    public void save(final Context ctx) {
        final AgentWire.PluginFileSave save = InternalServer.body(ctx, AgentWire.PluginFileSave.class);
        // Gson leaves a missing field null whatever the record declares.
        if (save == null
                || save.by() == null
                || save.text() == null
                || save.revision() == null
                || save.revision().isBlank()) {
            throw new BadRequestResponse("A save carries the text, the revision it was edited from and who saved it.");
        }
        if (save.text().getBytes(StandardCharsets.UTF_8).length > MAX_SIZE) {
            throw new BadRequestResponse("A plugin file may hold at most 1 MiB.");
        }
        final Found found = find(ctx);
        final String name = found.file().plugin() + "/" + found.file().path();
        if (!found.file().writable()) {
            throw new ForbiddenResponse(name + " cannot be written.");
        }
        // One save at a time, so two admins cannot both pass the revision check.
        synchronized (this) {
            if (!Sha256.hex(text(found)).equals(save.revision())) {
                throw new ConflictResponse(name + " changed on disk since it was opened. Open it again.");
            }
            try {
                writeAtomically(found.path(), save.text());
            } catch (final IOException failed) {
                throw new UncheckedIOException("could not write " + name, failed);
            }
        }
        log.info("{} saved {} on {}", save.by(), name, found.file().service());
        final AgentWire.PluginFile file = found.file();
        ctx.json(new AgentWire.PluginFileSaved(
                new AgentWire.PluginFile(
                        file.service(),
                        file.plugin(),
                        file.name(),
                        file.iconUrl(),
                        file.path(),
                        save.text().getBytes(StandardCharsets.UTF_8).length,
                        file.readable(),
                        file.writable()),
                Sha256.hex(save.text()),
                true));
    }

    private Found find(final Context ctx) {
        final String service = ctx.pathParam("name");
        final String plugin = ctx.queryParam("plugin");
        final String path = ctx.queryParam("path");
        return found(service, plugins.apply(service)).stream()
                .filter(one ->
                        one.file().plugin().equals(plugin) && one.file().path().equals(path))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse(service + " has no plugin file " + plugin + "/" + path + "."));
    }

    private List<Found> found(final String service, final AgentWire.Plugins listed) {
        if (volumesRoot == null || !listed.mounted()) {
            return List.of();
        }
        return discover(service, volumesRoot.resolve(service).resolve(Installation.PLUGINS), listed.plugins());
    }

    /** A listed file and where it is. */
    record Found(AgentWire.PluginFile file, Path path) {}

    /**
     * Lists the text config files under {@code directory}, one folder per plugin, by plugin then path.
     *
     * A folder that a Nordtal jar claims is left out; a folder no jar claims is listed under its own name.
     */
    static List<Found> discover(final String service, final Path directory, final List<AgentWire.Plugin> plugins) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        final Map<String, AgentWire.Plugin> byFolder = new HashMap<>();
        for (final AgentWire.Plugin plugin : plugins) {
            if (plugin.dataFolder() != null) {
                byFolder.putIfAbsent(plugin.dataFolder(), plugin);
            }
        }
        final List<Found> found = new ArrayList<>();
        try (Stream<Path> folders = Files.list(directory)) {
            for (final Path folder : folders.toList()) {
                final String name = folder.getFileName().toString();
                final AgentWire.Plugin plugin = byFolder.get(name);
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                        || Topology.NORDTAL_DATA_FOLDERS.containsKey(name)
                        || name.equals(NAME_TAGS_FOLDER)
                        || (plugin != null && plugin.group() == AgentWire.PluginGroup.NORDTAL)) {
                    continue;
                }
                found.addAll(filesIn(service, folder, plugin));
            }
        } catch (final IOException failed) {
            throw new UncheckedIOException("Cannot list " + directory, failed);
        }
        found.sort(Comparator.comparing((Found one) -> one.file().plugin().toLowerCase(Locale.ROOT))
                .thenComparing(one -> one.file().plugin())
                .thenComparing(one -> one.file().path()));
        return found;
    }

    private static List<Found> filesIn(final String service, final Path folder, final AgentWire.@Nullable Plugin plugin)
            throws IOException {
        final List<Found> found = new ArrayList<>();
        // Files.walk does not follow a link, and a link itself is no regular file without following it.
        try (Stream<Path> walk = Files.walk(folder)) {
            for (final Path file : walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .toList()) {
                final Path relative = folder.relativize(file);
                if (isListed(relative, file)) {
                    found.add(new Found(location(service, folder, plugin, relative, file), file));
                }
            }
        }
        return found;
    }

    private static boolean isListed(final Path relative, final Path file) throws IOException {
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            final String directory = relative.getName(i).toString();
            if (SKIPPED_DIRECTORIES.contains(directory.toLowerCase(Locale.ROOT)) || isNordtalKind(directory)) {
                return false;
            }
        }
        final String name = relative.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        return dot > 0
                && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))
                && !name.toLowerCase(Locale.ROOT).endsWith(SCHEMA_SUFFIX)
                && !isNordtalKind(name)
                && Files.size(file) <= MAX_SIZE
                && isText(file);
    }

    private static boolean isNordtalKind(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return NORDTAL_KINDS.stream().anyMatch(lower::contains);
    }

    /** Whether no NUL is in the first bytes. An unreadable file counts as text, so it is listed as unreadable. */
    private static boolean isText(final Path file) {
        if (!Files.isReadable(file)) {
            return true;
        }
        try (InputStream in = Files.newInputStream(file)) {
            for (final byte b : in.readNBytes(SNIFF_LENGTH)) {
                if (b == 0) {
                    return false;
                }
            }
            return true;
        } catch (final IOException unreadable) {
            return true;
        }
    }

    private static AgentWire.PluginFile location(
            final String service,
            final Path folder,
            final AgentWire.@Nullable Plugin plugin,
            final Path relative,
            final Path file)
            throws IOException {
        final List<String> segments = new ArrayList<>();
        relative.forEach(segment -> segments.add(segment.toString()));
        final Path parent = file.toAbsolutePath().getParent();
        // A writable file in a read-only folder still cannot be replaced.
        final boolean writable = Files.isWritable(file) && parent != null && Files.isWritable(parent);
        return new AgentWire.PluginFile(
                service,
                folder.getFileName().toString(),
                plugin == null ? null : plugin.name(),
                plugin == null ? null : plugin.iconUrl(),
                String.join("/", segments),
                Files.size(file),
                Files.isReadable(file),
                writable);
    }

    private static String text(final Found found) {
        try {
            final byte[] bytes = Files.readAllBytes(found.path());
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (final CharacterCodingException notUtf8) {
            throw new ConflictResponse(found.file().plugin() + "/"
                    + found.file().path() + " is not UTF-8 text, so it cannot be edited here.");
        } catch (final IOException failed) {
            throw new UncheckedIOException("could not read " + found.path(), failed);
        }
    }

    /**
     * Replaces the file through a temporary file in the same folder that carries the target's owner and mode.
     *
     * The rename means a reader sees the old text or the new one, never half of it.
     */
    static void writeAtomically(final Path file, final String content) throws IOException {
        final Path directory = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, ".", ".tmp");
            keepTheOwnerAndMode(file, temp);
            try (FileChannel channel =
                    FileChannel.open(temp, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                // Without force() a power loss can leave an empty file where the config was.
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                // Some filesystems refuse it; a plain replace still beats writing in place.
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (final IOException ignored) {
                    // A leftover temporary file is not worth hiding the real failure behind.
                }
            }
        }
    }

    /**
     * Gives {@code temp} the owner, group and permissions of {@code file}, failing the save if it cannot.
     *
     * The agent writes as root, and a root-owned config would be unreadable to the server it belongs to.
     */
    private static void keepTheOwnerAndMode(final Path file, final Path temp) throws IOException {
        final PosixFileAttributeView destination = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (destination == null) {
            return;
        }
        final PosixFileAttributes attributes;
        try {
            attributes = destination.readAttributes();
        } catch (final NoSuchFileException e) {
            return;
        }
        Files.setPosixFilePermissions(temp, attributes.permissions());
        final PosixFileAttributeView temporary = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
        temporary.setGroup(attributes.group());
        temporary.setOwner(attributes.owner());
    }
}

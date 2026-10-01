package eu.nordtal.s2.steward.configfile;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import eu.nordtal.s2.steward.configfile.ConfigEntry.Kind;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;

/**
 * Reads and writes the YAML jcore writes, without knowing the {@code @ConfigSpec} it came from.
 *
 * A write edits lines bottom up, never re-dumps, and moves an atomic temp file in only once it reads back right.
 */
public final class ConfigFiles {

    private ConfigFiles() {}

    /**
     * Reads a config file as a form.
     *
     * @param file the file
     * @return its header, and every key in file order
     * @throws IOException if the file cannot be read, is not YAML, or has no mapping at its root
     */
    public static ConfigDocument read(final Path file) throws IOException {
        return ConfigFileReader.parse(file).document();
    }

    /** Returns what a file said as a short content hash, the revision a save has to still be about. */
    public static String revisionOf(final String content) {
        final MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM has no SHA-256", e);
        }
        final byte[] digest = sha256.digest(content.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder(16);
        for (int index = 0; index < 8; index++) {
            hex.append("%02x".formatted(digest[index]));
        }
        return hex.toString();
    }

    /**
     * Applies changes in place and rewrites the file, leaving every other byte alone.
     *
     * @param file the file to edit
     * @param changes dotted path to what that key should now say; an empty map rewrites nothing
     * @return the file as it now reads
     * @throws IllegalArgumentException if a path is missing, not rewritable, sent the wrong shape or the wrong type
     * @throws IOException if the file cannot be read or written
     */
    static ConfigDocument write(final Path file, final Map<String, ConfigChange> changes) throws IOException {
        return write(file, changes, null);
    }

    /**
     * Writes the same way, but only if the file still says what the caller last read.
     *
     * @param expectedRevision the {@link ConfigDocument#revision()} the caller was last shown, or {@code null} not to
     *     check
     * @throws StaleConfigException if the file has been written since
     */
    public static ConfigDocument write(
            final Path file, final Map<String, ConfigChange> changes, final @Nullable String expectedRevision)
            throws IOException {
        final Parsed parsed = ConfigFileReader.parse(file);
        if (expectedRevision != null
                && !expectedRevision.equals(parsed.document().revision())) {
            throw new StaleConfigException(
                    file, expectedRevision, parsed.document().revision());
        }
        if (changes.isEmpty()) {
            return parsed.document();
        }

        final List<String> lines = new ArrayList<>(parsed.lines());
        final Map<String, Object> expected = new LinkedHashMap<>();

        final List<Map.Entry<String, ConfigChange>> ordered = new ArrayList<>(changes.entrySet());
        ordered.sort(Comparator.comparingInt((final Map.Entry<String, ConfigChange> change) ->
                        entryOf(parsed, file, change.getKey()).line())
                .reversed());

        for (final Map.Entry<String, ConfigChange> change : ordered) {
            final ConfigEntry entry = entryOf(parsed, file, change.getKey());
            final Span span = Objects.requireNonNull(
                    parsed.spans().get(change.getKey()), "collect() puts a span for every entry it adds");
            expected.put(change.getKey(), apply(parsed, lines, entry, span, change.getValue()));
        }

        final String content = String.join("", lines);
        verify(file, content, expected);
        writeAtomically(file, content);
        return read(file);
    }

    /**
     * Writes the bytes an operator typed into the raw editor, verbatim, behind the same revision guard.
     *
     * @param file the file to overwrite
     * @param content the exact text to write
     * @param expectedRevision the revision the caller last read the file as, or {@code null} not to check at all
     * @return the new revision, {@link #revisionOf(String)} of {@code content}
     * @throws StaleConfigException if the file has been written since {@code expectedRevision}
     * @throws IOException if the file cannot be read or written
     */
    public static String writeRaw(final Path file, final String content, final String expectedRevision)
            throws IOException {
        final String current = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        final String currentRevision = revisionOf(current);
        if (expectedRevision != null && !expectedRevision.equals(currentRevision)) {
            throw new StaleConfigException(file, expectedRevision, currentRevision);
        }
        writeAtomically(file, content);
        return revisionOf(content);
    }

    private static ConfigEntry entryOf(final Parsed parsed, final Path file, final String path) {
        return parsed.document()
                .find(path)
                .orElseThrow(() -> new IllegalArgumentException("There is no setting called " + path + " in " + file));
    }

    /** Rewrites one key and returns what it should read back as: a string, a list, or a list of maps. */
    private static Object apply(
            final Parsed parsed,
            final List<String> lines,
            final ConfigEntry entry,
            final Span span,
            final ConfigChange change) {
        if (entry.kind() == Kind.MAP) {
            throw new IllegalArgumentException(entry.path() + " is a nested section (line " + entry.line()
                    + ") and has no value of its own - change the keys under it");
        }
        if (!entry.editable()) {
            // A uniform sequence is always editable, so this one mixes scalars and mappings.
            throw new IllegalArgumentException(entry.path() + " is a list whose entries are not all"
                    + " the same shape (line " + entry.line() + "): this editor cannot describe it"
                    + " field by field, so it leaves it alone - edit it by hand");
        }
        return switch (change) {
            case ConfigChange.Text text -> {
                if (entry.kind() == Kind.SCALAR) {
                    yield ScalarWriter.scalar(lines, entry, span, text.text());
                }
                if (entry.kind() == Kind.SECTIONS) {
                    throw new IllegalArgumentException(entry.path() + " is a list of sections (line " + entry.line()
                            + "): send one record per entry, not one value");
                }
                throw new IllegalArgumentException(
                        entry.path() + " is a list (line " + entry.line() + "): send its entries, not one value");
            }
            case ConfigChange.Items items -> {
                if (entry.kind() == Kind.LIST) {
                    yield ScalarWriter.sequence(lines, entry, span, items.items());
                }
                if (entry.kind() == Kind.SECTIONS && items.items().isEmpty()) {
                    // `[]` is ambiguous; this is a list of sections with its last entry removed.
                    yield SectionWriter.sections(parsed, lines, entry, span, List.of());
                }
                if (entry.kind() == Kind.SECTIONS) {
                    throw new IllegalArgumentException(entry.path() + " is a list of sections (line "
                            + entry.line() + "): send one record per entry, not a list of plain"
                            + " values");
                }
                throw new IllegalArgumentException(
                        entry.path() + " is a single value (line " + entry.line() + "): send one value, not a list");
            }
            case ConfigChange.Sections sectionsChange -> {
                if (entry.kind() == Kind.SECTIONS) {
                    yield SectionWriter.sections(parsed, lines, entry, span, sectionsChange.sections());
                }
                throw new IllegalArgumentException(entry.path() + " is not a list of sections (line " + entry.line()
                        + "): send a single value or a list of values instead");
            }
        };
    }

    /** Reads the content back before it is written, and fails if a changed key does not read as asked. */
    private static void verify(final Path file, final String content, final Map<String, Object> expected) {
        final Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(content));
        } catch (final YAMLException e) {
            throw new IllegalStateException(
                    "Refusing to write " + file + ": the edited content is not valid YAML. This is a bug.", e);
        }
        final List<ConfigEntry> entries = new ArrayList<>();
        try {
            ConfigFileReader.collect(
                    file,
                    (MappingNode) root,
                    "",
                    LineEdits.splitKeepingLineEndings(content),
                    entries,
                    new HashMap<>(),
                    Optional.empty(),
                    Optional.empty());
        } catch (final IOException | ClassCastException e) {
            throw new IllegalStateException(
                    "Refusing to write " + file + ": the edited content cannot be read back. This is a bug.", e);
        }
        final ConfigDocument document = new ConfigDocument(file, revisionOf(content), List.of(), entries);
        expected.forEach((path, value) -> {
            final ConfigEntry entry = document.find(path)
                    .orElseThrow(() -> new IllegalStateException(
                            "Refusing to write " + file + ": " + path + " disappeared from the edited content."));
            final Object actual = switch (entry.kind()) {
                case LIST -> entry.items();
                case SECTIONS -> SectionWriter.sectionValuesOf(entry);
                case SCALAR, MAP -> entry.value();
            };
            if (!actual.equals(value)) {
                throw new IllegalStateException("Refusing to write " + file + ": " + path + " would read back as \""
                        + actual + "\" instead of \"" + value + "\". This is a bug.");
            }
        });
    }

    private static void writeAtomically(final Path file, final String content) throws IOException {
        final Path directory = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, ".", ".tmp");
            keepTheOwnerAndMode(file, temp);
            try (FileChannel channel = FileChannel.open(temp, CREATE, TRUNCATE_EXISTING, WRITE)) {
                channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                // Without force() a power loss can leave an empty file where the config was.
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                // Some network filesystems refuse it; a plain replace still beats writing in place.
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (final IOException ignored) {
                    // A leftover temp file is not worth hiding the real failure behind.
                }
            }
        }
    }

    /**
     * Gives {@code temp} the owner, group and permissions of {@code file}, failing the save if it cannot.
     *
     * Steward writes as root, and a root-owned config would be unreadable to the service it belongs to.
     */
    private static void keepTheOwnerAndMode(final Path file, final Path temp) throws IOException {
        final PosixFileAttributeView destination = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (destination == null) {
            // Not a POSIX filesystem, so there is nothing to carry across.
            return;
        }
        final PosixFileAttributes attributes;
        try {
            attributes = destination.readAttributes();
        } catch (final NoSuchFileException e) {
            // A file that is not there yet has no owner or permissions to keep.
            return;
        }
        Files.setPosixFilePermissions(temp, attributes.permissions());
        final PosixFileAttributeView temporary = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
        temporary.setGroup(attributes.group());
        temporary.setOwner(attributes.owner());
    }

    /**
     * Returns every config file under the mount, one directory per service.
     *
     * @return every text file beneath it, by service then name; empty if the root does not exist
     * @throws UncheckedIOException if the root exists but cannot be walked
     */
    public static List<ConfigLocation> discover(final Path root) {
        return ConfigFileDiscovery.discover(root);
    }
}

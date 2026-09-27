package eu.nordtal.s2.steward.worker.configfile;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import eu.nordtal.s2.steward.worker.configfile.ConfigEntry.Kind;
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
 * The file is the model. Steward shows every configuration in the stack, and those specs live in modules
 * steward-ui must not depend on - a reader of the file, and of the schema beside it ({@link Schemas}), can draw
 * the same form a reader of the class could, without a class to read.
 *
 * Writing is a line edit, never a re-dump. Handing the parsed tree back to SnakeYAML's dumper would produce a
 * valid file with every comment gone, blank lines moved and keys in some other order - which is to say it would
 * throw away the only documentation an operator has. So {@link #write} replaces the characters of one scalar on
 * one line and leaves every other byte of the file alone.
 *
 * Edits are applied from the bottom of the file upwards. A block rewrite changes how many lines there are, and
 * every position below it would otherwise be pointing one key too far up - which is the kind of corruption that
 * writes a greeting into a port number.
 *
 * The write is atomic: a temp file in the same directory, flushed, then moved over the destination, so a crash, a
 * kill or a full disk leaves the old config, never half of a new one. And nothing is moved into place until the
 * new content has been read back and found to say what it was asked to say.
 */
public final class ConfigFiles {

    private ConfigFiles() {}

    /**
     * Reads a config file as a form.
     *
     * @param file the file
     * @return its header, and every key in file order
     * @throws IOException if the file cannot be read, or is not YAML, or is not a mapping at its
     *                     root. The message names the file and the line
     */
    public static ConfigDocument read(final Path file) throws IOException {
        return ConfigFileReader.parse(file).document();
    }

    /**
     * What a file said, as a short string: the revision a save has to still be about.
     *
     * The content is hashed rather than the modification time or the size, because {@code mtime} on a container
     * filesystem has a resolution a second write can land inside, and two edits of the same key are very often the
     * same length. SHA-256, truncated to 16 hex characters, is not a security boundary - it is a way of noticing
     * that two people had the same form open.
     */
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
     * Applies changes in place and rewrites the file.
     *
     * Only what was named changes. Comments, blank lines, key order, indentation, the header, the line endings and
     * any trailing comment on the same line are all still there afterwards, byte for byte - with one exception: a
     * comment standing between the entries of a list does not survive that list being rewritten. jcore never
     * writes one.
     *
     * @param file the file to edit
     * @param changes dotted path to what that key should now say; an empty map rewrites nothing
     * @return the file as it now reads
     * @throws IllegalArgumentException if a path is not in the file, is of a kind that cannot be rewritten, is
     *     sent the wrong shape of change, or is given a value that is not of the type that key already has
     * @throws IOException if the file cannot be read or written
     */
    static ConfigDocument write(final Path file, final Map<String, ConfigChange> changes) throws IOException {
        return write(file, changes, null);
    }

    /**
     * The same write, but only if the file still says what the caller last read.
     *
     * This is the one the API uses, and the plain {@link #write(Path, Map)} beside it is package-private so it can
     * only be reached from the tests in here. A config form stands open in a browser for as long as somebody is
     * reading the comments in it, and two admins on the same file is not an exotic case. Without this, the second
     * save reads the file, applies its own list of changes to what it finds and writes the result: the first
     * admin's change is not conflicting, it is simply gone, and nothing anywhere says so.
     *
     * @param expectedRevision the {@link ConfigDocument#revision()} the caller was last shown, or {@code null} not
     *     to check at all
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
     * The raw-save half of writing: the bytes an operator typed into the raw editor, written verbatim.
     *
     * There is no shape to check a change against here, and nothing ever refuses on content grounds; {@link
     * RawSyntax} is what looks at the text for a warning, and it runs before this method is ever called. The same
     * revision guard as the parsed save applies, comparing against whatever is on disk right now rather than a
     * cached revision, so a file that started in one form and gets fixed and saved in the other still conflicts
     * correctly.
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

    /**
     * Rewrites one key, and answers with what that key should now read back as.
     *
     * A {@link String} for a scalar, a {@link List} for a sequence of scalars, a {@link List} of {@link Map}s for a
     * {@link Kind#SECTIONS} entry.
     */
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
            // The one remaining shape here is a sequence mixing scalars and mappings; a uniform one always editable.
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
                    // `[]` is ambiguous; this one is a list of sections with its last entry removed.
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

    /**
     * Reads the content back before it is written, and fails if a changed key does not read as it was asked to.
     *
     * This is the guard that makes a quoting bug a refused save instead of a config an operator has to repair by
     * hand, and a service that will not start.
     */
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
                // Some network filesystems refuse it; a plain replace is still better than writing in place.
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
     * Gives {@code temp} the owner, group and permissions {@code file} already has.
     *
     * {@link Files#createTempFile} makes an owner-only file owned by whichever process is writing, which is the
     * right default for a temporary file but not for the file it is about to become: the worker edits every other
     * service's config through a shared mount, as root, while every one of those services runs as its own uid.
     * Carrying the mode across but leaving the owner at root is a save that looks fine until the service it
     * belongs to is next re-created and finds its own configuration unreadable, silently, because the container
     * that already has the file open never notices.
     *
     * A failure here is not swallowed: reporting a saved file whose owner, group or permissions changed is exactly
     * the failure this method exists to prevent, so it fails the save instead.
     */
    private static void keepTheOwnerAndMode(final Path file, final Path temp) throws IOException {
        final PosixFileAttributeView destination = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (destination == null) {
            // Not a POSIX filesystem. There is nothing to carry across and nothing to report.
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
     * Every config file under the mount, one directory per service.
     *
     * @return every text file beneath it, by service then name. Empty if the root does not exist
     * @throws UncheckedIOException if the root exists but cannot be walked
     */
    public static List<ConfigLocation> discover(final Path root) {
        return ConfigFileDiscovery.discover(root);
    }
}

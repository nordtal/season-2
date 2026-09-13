package eu.nordtal.s2.steward.ui.configfile;

import eu.nordtal.s2.steward.ui.configfile.ConfigEntry.Kind;
import eu.nordtal.s2.steward.ui.configfile.ConfigEntry.Type;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Reads and writes the commented YAML jcore writes, without knowing the {@code @ConfigSpec} it
 * came from.
 *
 * <p><b>The file is the model.</b> Steward shows every configuration in the stack - the worker's
 * {@code steward.yml}, the bot's, the four Paper plugins' - and those specs live in modules
 * steward-ui must not depend on (one of them would drag a Paper API onto a web server's
 * classpath). jcore writes its {@code @Comment}s into the YAML, so a file it wrote documents
 * itself, and a reader of the file can draw the same form a reader of the class could.</p>
 *
 * <p><b>Writing is a line edit, never a re-dump.</b> Handing the parsed tree back to SnakeYAML's
 * dumper would produce a valid file with every comment gone, blank lines moved and keys in some
 * other order - which is to say it would throw away the only documentation an operator has. So
 * {@link #write} replaces the characters of one scalar on one line and leaves every other byte of
 * the file alone.</p>
 */
public final class ConfigFiles {

    private ConfigFiles() {
    }

    // -----------------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------------

    /**
     * Reads a config file as a form.
     *
     * @param file the file
     * @return its header, and every key in file order
     * @throws IOException if the file cannot be read, or is not YAML, or is not a mapping at its
     *                     root. The message names the file and the line
     */
    public static @NotNull ConfigDocument read(final @NotNull Path file) throws IOException {
        return parse(file).document();
    }

    /**
     * The document, plus what only the writer needs: the lines as they stand, and where each
     * scalar's characters are.
     */
    private record Parsed(ConfigDocument document, List<String> lines, Map<String, Span> spans) {
    }

    /**
     * Where a value's characters sit, 0-based. {@code start == end} is the empty span of a key
     * with no value ({@code token:}), and it sits directly after the colon.
     */
    private record Span(int line, int start, int end, int endLine) {

        boolean multiLine() {
            return endLine > line;
        }
    }

    private static Parsed parse(final Path file) throws IOException {
        final String content = Files.readString(file, StandardCharsets.UTF_8);
        final List<String> lines = splitKeepingLineEndings(content);

        final Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(content));
        } catch (final MarkedYAMLException e) {
            throw new IOException(file + " is not valid YAML: line " + lineOf(e) + ": "
                    + e.getProblem(), e);
        } catch (final YAMLException e) {
            throw new IOException(file + " is not valid YAML: " + e.getMessage(), e);
        }

        if (root == null) {
            // An empty file, or one that is nothing but comments. Everything in it is the header.
            return new Parsed(new ConfigDocument(file, headerOf(lines, Integer.MAX_VALUE), List.of()),
                    lines, Map.of());
        }
        if (!(root instanceof MappingNode mapping)) {
            throw new IOException(file + " is not a config file: line "
                    + (root.getStartMark().getLine() + 1)
                    + ": the top of the file must be a set of keys, found a "
                    + root.getNodeId() + " instead");
        }

        final List<ConfigEntry> entries = new ArrayList<>();
        final Map<String, Span> spans = new HashMap<>();
        collect(file, mapping, "", lines, entries, spans);

        final int firstKeyLine = entries.isEmpty() ? Integer.MAX_VALUE : entries.getFirst().line() - 1;
        return new Parsed(new ConfigDocument(file, headerOf(lines, firstKeyLine), entries), lines, spans);
    }

    private static void collect(final Path file,
                                final MappingNode mapping,
                                final String prefix,
                                final List<String> lines,
                                final List<ConfigEntry> entries,
                                final Map<String, Span> spans) throws IOException {
        for (final NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode keyNode)) {
                throw new IOException(file + ": line " + (tuple.getKeyNode().getStartMark().getLine() + 1)
                        + ": only plain keys are supported, found a "
                        + tuple.getKeyNode().getNodeId());
            }
            final String key = keyNode.getValue();
            final String path = prefix.isEmpty() ? key : prefix + "." + key;
            final Node valueNode = tuple.getValueNode();
            final int keyLine = keyNode.getStartMark().getLine();

            final Kind kind;
            final Type type;
            final String value;
            final Span span = new Span(
                    valueNode.getStartMark().getLine(),
                    valueNode.getStartMark().getColumn(),
                    valueNode.getEndMark().getColumn(),
                    valueNode.getEndMark().getLine());

            if (valueNode instanceof MappingNode) {
                kind = Kind.MAP;
                type = Type.STRING;
                value = "";
            } else if (valueNode instanceof SequenceNode) {
                kind = Kind.LIST;
                type = Type.STRING;
                value = "";
            } else {
                final ScalarNode scalar = (ScalarNode) valueNode;
                kind = Kind.SCALAR;
                type = Scalars.typeOf(scalar);
                value = scalar.getValue();
            }

            // A scalar spanning several lines is a block scalar (`|-`), and replacing the rest of
            // its first line would leave its continuation lines behind as a broken document. It is
            // read and shown; it is not editable here.
            final boolean editable = kind == Kind.SCALAR && !span.multiLine();

            entries.add(new ConfigEntry(
                    path,
                    key,
                    Labels.of(key),
                    commentsAbove(lines, keyLine),
                    value,
                    kind,
                    type,
                    keyLine + 1,
                    editable,
                    ConfigEntry.isSecretKey(key)));
            spans.put(path, span);

            if (valueNode instanceof MappingNode nested) {
                collect(file, nested, path, lines, entries, spans);
            }
        }
    }

    /**
     * The comment block directly above a key.
     *
     * <p>Indentation is no part of this: the line is stripped before it is looked at. jcore happens
     * to indent a nested key's comment to that key's column and a hand-edited file may not, and
     * neither should change what the form shows. A blank line ends the block - what is above it
     * belongs to whatever came before.</p>
     */
    private static List<String> commentsAbove(final List<String> lines, final int keyLine) {
        final Deque<String> block = new ArrayDeque<>();
        for (int i = keyLine - 1; i >= 0; i--) {
            final String text = withoutLineEnding(lines.get(i)).strip();
            if (text.isEmpty() || !text.startsWith("#")) {
                break;
            }
            block.addFirst(stripCommentMarker(text));
        }
        return List.copyOf(block);
    }

    /**
     * The header block: the comments at the very top of the file, if a blank line separates them
     * from the first key.
     *
     * <p>Without that blank line they are the first key's comment and not a header - which is
     * exactly how jcore writes a spec with no {@code header}.</p>
     */
    private static List<String> headerOf(final List<String> lines, final int firstKeyLine) {
        int i = 0;
        final List<String> header = new ArrayList<>();
        while (i < lines.size()) {
            final String text = withoutLineEnding(lines.get(i)).strip();
            if (!text.startsWith("#")) {
                break;
            }
            header.add(stripCommentMarker(text));
            i++;
        }
        if (header.isEmpty()) {
            return List.of();
        }
        // "The line under the block is the first key", not merely "is not blank": a file that
        // starts with comments and then a `---` still has a header, and that line is not a key.
        final boolean attachedToAKey = i < lines.size()
                && !withoutLineEnding(lines.get(i)).isBlank()
                && i >= firstKeyLine;
        return attachedToAKey ? List.of() : List.copyOf(header);
    }

    /** {@code "# text"} becomes {@code "text"}; jcore writes a blank comment line as {@code "# "}. */
    private static String stripCommentMarker(final String commentLine) {
        final String withoutHash = commentLine.substring(1);
        return withoutHash.startsWith(" ") ? withoutHash.substring(1) : withoutHash;
    }

    // -----------------------------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------------------------

    /**
     * Applies scalar changes in place and rewrites the file.
     *
     * <p>Only the characters of the named values change. Comments, blank lines, key order,
     * indentation, the header, the line endings and any trailing comment on the same line are all
     * still there, byte for byte, afterwards.</p>
     *
     * <p>The write is atomic - a temp file in the same directory, flushed, then moved over the
     * destination - so a crash, a kill or a full disk leaves the old config, never half of a new
     * one. jcore's own {@code AtomicConfigWriter} does the same thing and is {@code internal}, so
     * this matches it rather than calling it. And nothing is moved into place until the new content
     * has been read back and found to say what it was asked to say.</p>
     *
     * @param file    the file to edit
     * @param changes dotted path to new value; an empty map rewrites nothing
     * @return the file as it now reads
     * @throws IllegalArgumentException if a path is not in the file, is not a single-line scalar,
     *                                  or if a value is not of the type that key already has
     * @throws IOException              if the file cannot be read or written
     */
    public static @NotNull ConfigDocument write(final @NotNull Path file,
                                                final @NotNull Map<String, String> changes)
            throws IOException {
        final Parsed parsed = parse(file);
        if (changes.isEmpty()) {
            return parsed.document();
        }

        final List<String> lines = new ArrayList<>(parsed.lines());
        final Map<String, String> expected = new HashMap<>();

        for (final Map.Entry<String, String> change : changes.entrySet()) {
            final String path = change.getKey();
            final ConfigEntry entry = parsed.document().find(path).orElseThrow(
                    () -> new IllegalArgumentException(
                            "There is no setting called " + path + " in " + file));

            if (entry.kind() != Kind.SCALAR) {
                throw new IllegalArgumentException(path + " is a "
                        + (entry.kind() == Kind.LIST ? "list" : "nested section")
                        + " (line " + entry.line() + "): lists and nested sections are not editable"
                        + " in this alpha - edit the file by hand");
            }
            final Span span = parsed.spans().get(path);
            if (span.multiLine()) {
                // Deliberately worded differently from the refusal above: the two are different
                // refusals, and a test that cannot tell them apart is a test that passes while the
                // check it was written for is gone. This one did, once.
                throw new IllegalArgumentException(path + " is written across several lines (line "
                        + entry.line() + "): only a value that sits on one line can be edited here");
            }

            final String rendered = Scalars.render(entry.type(), change.getValue(), path);
            lines.set(span.line(), splice(lines.get(span.line()), span, rendered));
            expected.put(path, entry.type() == Type.STRING ? change.getValue() : rendered);
        }

        final String content = String.join("", lines);
        verify(file, content, expected);
        writeAtomically(file, content);
        return read(file);
    }

    /** Replaces the value's characters on its line, leaving the indentation and any trailing comment. */
    private static String splice(final String line, final Span span, final String rendered) {
        final String body = withoutLineEnding(line);
        final String ending = line.substring(body.length());
        final int start = Math.min(span.start(), body.length());
        final int end = Math.min(Math.max(span.end(), start), body.length());

        if (start == end) {
            // `token:` with nothing after it. The span sits right after the colon, so a space has
            // to be put in - and anything after it, such as a trailing comment, is kept.
            final String before = body.substring(0, start);
            final String after = body.substring(start);
            return before + (before.endsWith(" ") ? "" : " ") + rendered + after + ending;
        }
        return body.substring(0, start) + rendered + body.substring(end) + ending;
    }

    /**
     * Reads the content back before it is written, and fails if any changed key does not now say
     * what it was asked to say.
     *
     * <p>This is the guard that makes a quoting bug a refused save instead of a config an operator
     * has to repair by hand - and a service that will not start.</p>
     */
    private static void verify(final Path file, final String content, final Map<String, String> expected) {
        final Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(content));
        } catch (final YAMLException e) {
            throw new IllegalStateException("Refusing to write " + file
                    + ": the edited content is not valid YAML. This is a bug in ConfigFiles.", e);
        }
        final List<ConfigEntry> entries = new ArrayList<>();
        try {
            collect(file, (MappingNode) root, "", splitKeepingLineEndings(content), entries, new HashMap<>());
        } catch (final IOException e) {
            throw new IllegalStateException("Refusing to write " + file
                    + ": the edited content cannot be read back. This is a bug in ConfigFiles.", e);
        }
        final ConfigDocument document = new ConfigDocument(file, List.of(), entries);
        expected.forEach((path, value) -> {
            final String actual = document.find(path)
                    .map(ConfigEntry::value)
                    .orElseThrow(() -> new IllegalStateException("Refusing to write " + file
                            + ": " + path + " disappeared from the edited content."
                            + " This is a bug in ConfigFiles."));
            if (!actual.equals(value)) {
                throw new IllegalStateException("Refusing to write " + file + ": " + path
                        + " would read back as \"" + actual + "\" instead of \"" + value + "\"."
                        + " This is a bug in ConfigFiles.");
            }
        });
    }

    private static void writeAtomically(final Path file, final String content) throws IOException {
        final Path directory = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, ".", ".tmp");
            try (FileChannel channel = FileChannel.open(temp, CREATE, TRUNCATE_EXISTING, WRITE)) {
                channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                // Without the force() the bytes can still be in the page cache when the move
                // completes, and a power loss then leaves an empty file where the config was.
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                // Some network filesystems refuse it. A plain replace is still better than writing
                // into the destination.
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

    // -----------------------------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------------------------

    /**
     * Every config file under the mount, one directory per service.
     *
     * <p>{@code /configs/steward-worker/steward.yml} is service {@code steward-worker}, name
     * {@code steward.yml}; {@code /configs/smp/nordtal-smp/config.yml} is service {@code smp}, name
     * {@code nordtal-smp/config.yml}. A {@code .yml} lying directly in the root has no service
     * directory above it and is reported with an empty service rather than dropped - a file the
     * page does not list is a file nobody will go looking for.</p>
     *
     * @param root the mount point
     * @return every {@code *.yml} beneath it, by service then name. <b>Empty if the root does not
     *         exist</b>: an unmounted volume is a normal state the page has to be able to report,
     *         not a failure
     * @throws UncheckedIOException if the root exists but cannot be walked
     */
    public static @NotNull List<ConfigLocation> discover(final @NotNull Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .map(path -> locationOf(root, path))
                    .sorted(Comparator.comparing(ConfigLocation::service)
                            .thenComparing(ConfigLocation::name))
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot list the config files under " + root, e);
        }
    }

    private static ConfigLocation locationOf(final Path root, final Path file) {
        final Path relative = root.relativize(file);
        final String service = relative.getNameCount() > 1 ? relative.getName(0).toString() : "";
        final Path rest = relative.getNameCount() > 1
                ? relative.subpath(1, relative.getNameCount())
                : relative;
        final StringBuilder name = new StringBuilder();
        for (final Path segment : rest) {
            if (!name.isEmpty()) {
                name.append('/');
            }
            name.append(segment);
        }
        // The move needs the directory, not only the file: this writes a temp file next to the
        // config and renames it over the top. A writable file in a read-only mount cannot be saved.
        final Path directory = file.toAbsolutePath().getParent();
        final boolean writable = Files.isWritable(file) && directory != null && Files.isWritable(directory);
        return new ConfigLocation(service, name.toString(), file, writable);
    }

    // -----------------------------------------------------------------------------------------
    // Lines
    // -----------------------------------------------------------------------------------------

    /**
     * Splits into lines that still carry their own {@code \n} or {@code \r\n}.
     *
     * <p>{@code String.lines()} throws the endings away, and joining with one of them afterwards is
     * how a CRLF file silently becomes an LF file and a whole config shows up in a diff.</p>
     */
    private static List<String> splitKeepingLineEndings(final String content) {
        final List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            final char c = content.charAt(i);
            if (c == '\n') {
                lines.add(content.substring(start, i + 1));
                start = i + 1;
            } else if (c == '\r') {
                final boolean crlf = i + 1 < content.length() && content.charAt(i + 1) == '\n';
                lines.add(content.substring(start, i + (crlf ? 2 : 1)));
                if (crlf) {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    private static String withoutLineEnding(final String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }

    private static int lineOf(final MarkedYAMLException e) {
        final Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
        return mark == null ? 1 : mark.getLine() + 1;
    }
}

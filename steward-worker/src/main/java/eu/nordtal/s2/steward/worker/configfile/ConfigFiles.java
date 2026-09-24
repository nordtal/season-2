package eu.nordtal.s2.steward.worker.configfile;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SettingKind;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import eu.nordtal.s2.steward.worker.configfile.ConfigEntry.Kind;
import eu.nordtal.s2.steward.worker.configfile.ConfigEntry.Type;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Reads and writes the YAML jcore writes, without knowing the {@code @ConfigSpec} it came from.
 *
 * <p><b>The file is the model.</b> Steward shows every configuration in the stack - the worker's
 * {@code steward.yml}, the bot's, the four Paper plugins' - and those specs live in modules
 * steward-ui must not depend on (one of them would drag a Paper API onto a web server's
 * classpath). A reader of the file, and now of the {@code <name>.schema.json} beside it
 * ({@link Schemas}, steward/55), can draw the same form a reader of the class could - without a
 * class to read.</p>
 *
 * <p><b>The schema is the first choice, never the only one.</b> A file with no schema - one jcore
 * has not written under 4.0.0 yet, or one nothing ever described - reads exactly as it always has:
 * a mechanical {@link Labels#of(String)} label and whatever comment block sits above the key. And
 * whichever source wins, <b>the file is still the truth about what keys exist</b> (steward/50): a
 * key the schema does not mention is delivered anyway, never hidden, and a schema entry with
 * nothing behind it in the file is silently ignored rather than invented as an entry.</p>
 *
 * <p><b>Writing is a line edit, never a re-dump.</b> Handing the parsed tree back to SnakeYAML's
 * dumper would produce a valid file with every comment gone, blank lines moved and keys in some
 * other order - which is to say it would throw away the only documentation an operator has. So
 * {@link #write} replaces the characters of one scalar on one line and leaves every other byte of
 * the file alone.</p>
 */
public final class ConfigFiles {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigFiles.class);

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
     * What a file said, as a short string - the revision a save has to still be about.
     *
     * <h2>Why the whole content and not the timestamp or the size</h2>
     * {@code mtime} on a container filesystem has a resolution a second write can land inside, and
     * two edits of the same key are very often the same length. The content is the only thing that
     * is certainly different when the file is different, and these files are kilobytes.
     *
     * <p>SHA-256, truncated to 16 hex characters. It is not a security boundary - anybody who can
     * call the save route can read the file through the route above it - it is a way of noticing
     * that two people had the same form open.</p>
     */
    public static @NotNull String revisionOf(final @NotNull String content) {
        final MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            // Every JDK has it; the checked exception is older than that being true.
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
     * The document, plus what only the writer needs: the lines as they stand, and where each
     * scalar's characters are.
     */
    private record Parsed(ConfigDocument document, List<String> lines, Map<String, Span> spans) {
    }

    /**
     * Where a key and its value sit, 0-based.
     *
     * <p>The key's own position is carried as well as the value's, because rewriting a block - a
     * sequence, a block scalar - starts at the key's indentation and not at the value's.
     * {@code start == end} is the empty span of a key with no value ({@code token:}), and it sits
     * directly after the colon.</p>
     *
     * @param keyLine      the line the key is on
     * @param keyColumn    the column the key starts at, which is the indentation everything
     *                     belonging to it has to be deeper than
     * @param keyEndColumn one past the key's last character, so the colon can be found without
     *                     guessing at what the key itself contains
     * @param line         the line the value starts on: the key's line for a scalar and for a flow
     *                     sequence, the line of the first {@code -} for a block sequence
     * @param start        the column the value starts at - the {@code |} of a block scalar, the
     *                     {@code [} of a flow sequence, the {@code -} of a block one
     * @param end          the column one past the value, on {@link #endLine}
     * @param endLine      where SnakeYAML's end mark sits, which for anything written as a block
     *                     is the line AFTER it. The writer therefore measures a block by
     *                     indentation rather than trusting this
     * @param flow         whether a sequence is written {@code [a, b]} rather than as a block
     * @param block        whether a scalar is written {@code |} or {@code >} rather than inline
     */
    private record Span(int keyLine, int keyColumn, int keyEndColumn,
                        int line, int start, int end, int endLine,
                        boolean flow, boolean block) {

        boolean multiLine() {
            return endLine > line;
        }

        /** Whether the value's characters start on the key's own line. */
        boolean onKeyLine() {
            return line == keyLine;
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
            return new Parsed(new ConfigDocument(file, revisionOf(content),
                    headerOf(lines, Integer.MAX_VALUE), List.of()), lines, Map.of());
        }
        if (!(root instanceof MappingNode mapping)) {
            throw new IOException(file + " is not a config file: line "
                    + (root.getStartMark().getLine() + 1)
                    + ": the top of the file must be a set of keys, found a "
                    + root.getNodeId() + " instead");
        }

        final List<ConfigEntry> entries = new ArrayList<>();
        final Map<String, Span> spans = new HashMap<>();
        final Optional<Map<String, SchemaNode>> schema = Schemas.read(file).map(SchemaNode::children);
        // Flat, unlike schema: an overridden path is already fully dotted ("roles.access",
        // "languages"), so the same Set is compared against at every nesting level rather than
        // narrowed level by level the way schemaLevel is.
        final Optional<Set<String>> overridden = EnvOverrides.read(file);
        collect(file, mapping, "", lines, entries, spans, schema, overridden);

        final int firstKeyLine = entries.isEmpty() ? Integer.MAX_VALUE : entries.getFirst().line() - 1;
        return new Parsed(new ConfigDocument(file, revisionOf(content),
                headerOf(lines, firstKeyLine), entries), lines, spans);
    }

    /**
     * Walks one mapping level, matching each key against {@code schemaLevel} when there is one.
     *
     * @param schemaLevel the schema's children at this level, keyed the same way the file is - or
     *                    empty when there is no schema to compare against here at all, which is the
     *                    file-has-no-schema case (steward/55) as well as the case where an ancestor's
     *                    schema node did not itself describe a nested mapping. Either way, every key
     *                    at this level is then vacuously {@link ConfigEntry#inSchema()}: there is
     *                    nothing here for it to be missing from
     */
    private static void collect(final Path file,
                                final MappingNode mapping,
                                final String prefix,
                                final List<String> lines,
                                final List<ConfigEntry> entries,
                                final Map<String, Span> spans,
                                final Optional<Map<String, SchemaNode>> schemaLevel,
                                final Optional<Set<String>> overridden) throws IOException {
        final Set<String> matchedSchemaKeys = new HashSet<>();
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

            final SchemaNode schemaChild = schemaLevel.map(level -> level.get(key)).orElse(null);
            final boolean inSchema = schemaLevel.isEmpty() || schemaChild != null;
            if (schemaChild != null) {
                matchedSchemaKeys.add(key);
            }

            final Kind kind;
            final Type type;
            final String value;
            final List<String> items;
            final List<ConfigEntry> template;
            final List<List<ConfigEntry>> sections;
            final boolean editable;

            if (valueNode instanceof MappingNode) {
                kind = Kind.MAP;
                type = Type.STRING;
                value = "";
                items = List.of();
                template = List.of();
                sections = List.of();
                // A section is a heading, not a value. There is nothing here to change that is not
                // one of the keys underneath it.
                editable = false;
            } else if (valueNode instanceof SequenceNode sequence
                    && !sequence.getValue().isEmpty()
                    && sequence.getValue().stream().allMatch(item -> item instanceof MappingNode)) {
                // A sequence of mappings - `languages` and `tiers` in the bot's access.yml are the
                // cases this was built for (steward/68). Reading and writing are split on purpose:
                // this class only ever replaces the characters of one scalar already on a line, so
                // an existing entry's own field can be changed - see #sections(Parsed, List, Entry,
                // List) below - but inserting or deleting a whole entry would have to place a new
                // block of lines (or remove one) with the right indentation and, for an insert, no
                // comment to invent - a harder problem this class does not solve yet.
                kind = Kind.SECTIONS;
                value = "";
                items = List.of();
                type = Type.STRING;
                editable = true;

                final Optional<Map<String, SchemaNode>> elementSchema =
                        (schemaChild != null && schemaChild.kind() == SettingKind.LIST)
                                ? Optional.of(schemaChild.children())
                                : Optional.empty();
                final List<List<ConfigEntry>> collected = new ArrayList<>();
                for (int index = 0; index < sequence.getValue().size(); index++) {
                    final MappingNode element = (MappingNode) sequence.getValue().get(index);
                    final List<ConfigEntry> fields = new ArrayList<>();
                    collect(file, element, path + "[" + index + "]", lines, fields, spans, elementSchema,
                            overridden);
                    collected.add(List.copyOf(fields));
                }
                sections = List.copyOf(collected);
                // No template at all when there is nothing to build one from, or when the schema
                // covers this list but describes an element with a map or a list of its own inside
                // it - a shape steward/68 leaves as "no card fits" rather than guessing at how deep
                // to go.
                template = elementSchema.filter(ConfigFiles::everyFieldIsAScalar)
                        .map(ConfigFiles::templateOf)
                        .orElse(List.of());
            } else if (valueNode instanceof SequenceNode sequence) {
                kind = Kind.LIST;
                value = "";
                final List<ScalarNode> scalars = new ArrayList<>();
                boolean plain = true;
                for (final Node item : sequence.getValue()) {
                    if (item instanceof ScalarNode scalar) {
                        scalars.add(scalar);
                    } else {
                        plain = false;
                    }
                }
                items = scalars.stream().map(ScalarNode::getValue).toList();
                type = plain ? sharedType(scalars) : Type.STRING;
                template = List.of();
                sections = List.of();
                // A sequence that mixes scalars and mappings is nothing this class can describe as
                // either shape, so it is left exactly as before: raw and not editable.
                editable = plain;
            } else {
                final ScalarNode scalar = (ScalarNode) valueNode;
                kind = Kind.SCALAR;
                type = Scalars.typeOf(scalar);
                value = scalar.getValue();
                items = List.of();
                template = List.of();
                sections = List.of();
                editable = true;
            }

            final Span span = new Span(
                    keyLine,
                    keyNode.getStartMark().getColumn(),
                    keyNode.getEndMark().getColumn(),
                    valueNode.getStartMark().getLine(),
                    valueNode.getStartMark().getColumn(),
                    valueNode.getEndMark().getColumn(),
                    valueNode.getEndMark().getLine(),
                    valueNode instanceof SequenceNode sequence
                            && sequence.getFlowStyle() == DumperOptions.FlowStyle.FLOW,
                    valueNode instanceof ScalarNode scalar
                            && (scalar.getScalarStyle() == DumperOptions.ScalarStyle.LITERAL
                            || scalar.getScalarStyle() == DumperOptions.ScalarStyle.FOLDED));

            entries.add(new ConfigEntry(
                    path,
                    key,
                    schemaChild != null ? schemaChild.label() : Labels.of(key),
                    commentsAbove(lines, keyLine),
                    schemaChild != null ? schemaChild.explanation() : "",
                    schemaChild != null && schemaChild.noExplanationNeeded(),
                    value,
                    items,
                    template,
                    sections,
                    kind,
                    type,
                    keyLine + 1,
                    editable,
                    // The heuristic is a net that stays under the schema (steward/50): a schema
                    // saying secret=true always wins, but secret=false or no schema entry at all
                    // never turns the heuristic off, only the schema turning it ON is authoritative.
                    ConfigEntry.isSecretKey(key) || (schemaChild != null && schemaChild.secret()),
                    inSchema,
                    overridden.map(paths -> paths.contains(path)).orElse(null),
                    choicesOf(schemaChild),
                    protectedEntryOf(schemaChild)));
            spans.put(path, span);

            if (valueNode instanceof MappingNode nested) {
                final Optional<Map<String, SchemaNode>> nestedSchema =
                        (schemaChild != null && schemaChild.kind() == SettingKind.MAP)
                                ? Optional.of(schemaChild.children())
                                : Optional.empty();
                collect(file, nested, path, lines, entries, spans, nestedSchema, overridden);
            }
        }

        // The file is the truth (steward/50): a schema entry with nothing in the file behind it is
        // not an error, but it is worth a line in the log - it is exactly what a setting the
        // software has since dropped from its spec looks like from here.
        if (schemaLevel.isPresent()) {
            final Set<String> extra = new java.util.TreeSet<>(schemaLevel.get().keySet());
            extra.removeAll(matchedSchemaKeys);
            if (!extra.isEmpty()) {
                LOG.warn("{}: the schema names {} setting(s) the file does not have: {}. The file"
                                + " wins; they are ignored.",
                        file, extra.size(), String.join(", ", extra));
            }
        }
    }

    /** {@link ConfigEntry.Choices}, from a schema entry's own {@link SchemaNode.Choices} - or {@code null}. */
    private static ConfigEntry.Choices choicesOf(final SchemaNode schemaChild) {
        if (schemaChild == null || schemaChild.choices() == null) {
            return null;
        }
        return new ConfigEntry.Choices(schemaChild.choices().values(), schemaChild.choices().strict());
    }

    /**
     * {@link ConfigEntry.Protected}, from a schema entry's own
     * {@link SchemaNode.ProtectedEntry} - or {@code null} when there is no schema for this key or
     * its property carries no {@code @Protected} (steward/74).
     */
    private static ConfigEntry.Protected protectedEntryOf(final SchemaNode schemaChild) {
        if (schemaChild == null || schemaChild.protectedEntry() == null) {
            return null;
        }
        return new ConfigEntry.Protected(
                schemaChild.protectedEntry().field(), schemaChild.protectedEntry().value());
    }

    /**
     * Whether a {@link Kind#SECTIONS} element's schema is flat enough to build a
     * {@link ConfigEntry#template()} from - every one of its own fields a plain scalar, none of them
     * a nested map or another list.
     *
     * <p>Nothing in {@code access.yml} needs more than that today, and a field two levels deep would
     * need a card that draws a card inside a card - a shape steward-ui's {@code RepeatableCards} was
     * never asked to draw. Refusing a template here is what sends that case down the raw-text
     * fallback instead of a card silently dropping the nested part.</p>
     */
    private static boolean everyFieldIsAScalar(final Map<String, SchemaNode> elementSchema) {
        return elementSchema.values().stream().allMatch(field -> field.kind() == SettingKind.SCALAR);
    }

    /**
     * The blank card a "Add entry" starts from: one {@link ConfigEntry} per field the schema
     * describes for one element, in schema order, every one of them an empty, editable scalar.
     *
     * @param elementSchema the schema's own shape of one element - see
     *                      {@link SchemaNode#children()}'s doc for a {@link SettingKind#LIST}
     */
    private static List<ConfigEntry> templateOf(final Map<String, SchemaNode> elementSchema) {
        final List<ConfigEntry> fields = new ArrayList<>();
        for (final Map.Entry<String, SchemaNode> field : elementSchema.entrySet()) {
            final String key = field.getKey();
            final SchemaNode schema = field.getValue();
            fields.add(new ConfigEntry(
                    key,
                    key,
                    schema.label(),
                    List.of(),
                    schema.explanation(),
                    schema.noExplanationNeeded(),
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    Kind.SCALAR,
                    schema.type() != null ? Type.valueOf(schema.type().name()) : Type.STRING,
                    0,
                    true,
                    ConfigEntry.isSecretKey(key) || schema.secret(),
                    true,
                    // A template field is the blank shape of one section, not a value that could
                    // ever be read from a live file - there is no path here for an environment
                    // variable to have overridden.
                    null,
                    choicesOf(schema),
                    // A template field is the blank shape of one section, not the list itself -
                    // @Protected names a whole section by one of its field VALUES, which a blank
                    // template field never has, so this is always null here.
                    null));
        }
        return List.copyOf(fields);
    }

    /**
     * The type every entry of a list shares, or {@link Type#STRING} when they differ.
     *
     * <p>It decides how a new entry is written back. Without it a list of ports would come back
     * from a form as a list of quoted strings - a file that still parses, and a config that no
     * longer loads.</p>
     */
    private static Type sharedType(final List<ScalarNode> items) {
        Type shared = null;
        for (final ScalarNode item : items) {
            final Type type = Scalars.typeOf(item);
            if (shared == null) {
                shared = type;
            } else if (shared != type) {
                return Type.STRING;
            }
        }
        return shared == null ? Type.STRING : shared;
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
        final List<String> block = new ArrayList<>();
        for (int i = commentBlockStartLine(lines, keyLine); i < keyLine; i++) {
            block.add(stripCommentMarker(withoutLineEnding(lines.get(i)).strip()));
        }
        return List.copyOf(block);
    }

    /**
     * The first line of the contiguous {@code #} comment block directly above {@code keyLine}, or
     * {@code keyLine} itself when there is none directly above it (a blank line, or a line that is
     * not a comment, stops the walk immediately - the same rule {@link #commentsAbove} has always
     * used for reading).
     *
     * <p>{@link #removeSection} (steward/71) is the other caller: it needs the line number rather
     * than the text, because deleting an entry has to delete the comment lines that belong to it
     * along with it - and, just as importantly, stop before the comment that belongs to the entry
     * above.</p>
     */
    private static int commentBlockStartLine(final List<String> lines, final int keyLine) {
        int start = keyLine;
        for (int i = keyLine - 1; i >= 0; i--) {
            final String text = withoutLineEnding(lines.get(i)).strip();
            if (text.isEmpty() || !text.startsWith("#")) {
                break;
            }
            start = i;
        }
        return start;
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
     * Applies changes in place and rewrites the file.
     *
     * <p>Only what was named changes. Comments, blank lines, key order, indentation, the header,
     * the line endings and any trailing comment on the same line are all still there, byte for
     * byte, afterwards - with one exception, written down rather than discovered: a comment
     * standing <em>between</em> the entries of a list does not survive that list being rewritten.
     * jcore never writes one.</p>
     *
     * <p>Edits are applied from the bottom of the file upwards. A block rewrite changes how many
     * lines there are, and every position below it would otherwise be pointing one key too far
     * up - which is the kind of corruption that writes a greeting into a port number.</p>
     *
     * <p>The write is atomic - a temp file in the same directory, flushed, then moved over the
     * destination - so a crash, a kill or a full disk leaves the old config, never half of a new
     * one. jcore's own {@code AtomicConfigWriter} does the same thing and is {@code internal}, so
     * this matches it rather than calling it. And nothing is moved into place until the new content
     * has been read back and found to say what it was asked to say.</p>
     *
     * @param file    the file to edit
     * @param changes dotted path to what that key should now say; an empty map rewrites nothing
     * @return the file as it now reads
     * @throws IllegalArgumentException if a path is not in the file, is of a kind that cannot be
     *                                  rewritten, is sent the wrong shape of change, or is given a
     *                                  value that is not of the type that key already has
     * @throws IOException              if the file cannot be read or written
     */
    static @NotNull ConfigDocument write(final @NotNull Path file,
                                         final @NotNull Map<String, ConfigChange> changes)
            throws IOException {
        return write(file, changes, null);
    }

    /**
     * The same write, but only if the file still says what the caller last read.
     *
     * <p><b>This is the one the API uses, and the plain {@link #write(Path, Map)} beside it is
     * package-private so that it can only be reached from the tests in here.</b> A config form
     * stands open in a browser for as long as somebody is reading the comments in it, and two
     * admins on the same file is not an exotic case - it is a Sunday evening. Without this, the
     * second save reads the file, applies its own list of changes to what it finds and writes the
     * result: the first admin's change is not conflicting, it is simply gone, and nothing anywhere
     * says so.</p>
     *
     * <p>The revision is checked against the read that this very write then edits, so the window
     * between the two is a few microseconds of one thread rather than the minutes a form is open.
     * It is not a lock: two saves that truly arrive at the same instant can still interleave, and a
     * file edited over SSH while a form is open is not seen at all until the save. What it removes
     * is the case that actually happens.</p>
     *
     * @param expectedRevision the {@link ConfigDocument#revision()} the caller was last shown, or
     *                          {@code null} not to check at all
     * @throws StaleConfigException if the file has been written since
     */
    public static @NotNull ConfigDocument write(final @NotNull Path file,
                                                final @NotNull Map<String, ConfigChange> changes,
                                                final String expectedRevision)
            throws IOException {
        final Parsed parsed = parse(file);
        if (expectedRevision != null && !expectedRevision.equals(parsed.document().revision())) {
            throw new StaleConfigException(file, expectedRevision, parsed.document().revision());
        }
        if (changes.isEmpty()) {
            return parsed.document();
        }

        final List<String> lines = new ArrayList<>(parsed.lines());
        final Map<String, Object> expected = new LinkedHashMap<>();

        final List<Map.Entry<String, ConfigChange>> ordered = new ArrayList<>(changes.entrySet());
        ordered.sort(Comparator.comparingInt(
                        (final Map.Entry<String, ConfigChange> change) ->
                                entryOf(parsed, file, change.getKey()).line())
                .reversed());

        for (final Map.Entry<String, ConfigChange> change : ordered) {
            final ConfigEntry entry = entryOf(parsed, file, change.getKey());
            final Span span = parsed.spans().get(change.getKey());
            expected.put(change.getKey(), apply(parsed, lines, entry, span, change.getValue()));
        }

        final String content = String.join("", lines);
        verify(file, content, expected);
        writeAtomically(file, content);
        return read(file);
    }

    /**
     * The raw-save half of writing (steward/60): the bytes an operator typed into the raw editor,
     * written verbatim - no parse, no re-serialise, no reformat. This is the whole point of the raw
     * path, so unlike {@link #write(Path, Map, String)} there is no shape to check a change against
     * and nothing here ever refuses on content grounds; {@link RawSyntax} is what looks at the text
     * for a warning, and it runs before this method is ever called.
     *
     * <p>The same revision guard as the parsed save, for the same reason: a raw file left open in
     * one tab while another writes it is not a rarer evening than a parsed one. Comparing against
     * whatever is on disk right now rather than a cached {@link ConfigDocument#revision()} keeps the
     * two paths using the exact same {@link #revisionOf(String)}, so a file that started in one form
     * and gets fixed and saved in the other still conflicts correctly.</p>
     *
     * @param file             the file to overwrite
     * @param content          the exact text to write
     * @param expectedRevision the revision the caller last read the file as, or {@code null} not to
     *                         check at all
     * @return the new revision, {@link #revisionOf(String)} of {@code content}
     * @throws StaleConfigException if the file has been written since {@code expectedRevision}
     * @throws IOException          if the file cannot be read or written
     */
    public static @NotNull String writeRaw(final @NotNull Path file, final @NotNull String content,
                                           final String expectedRevision) throws IOException {
        final String current = Files.exists(file)
                ? Files.readString(file, StandardCharsets.UTF_8) : "";
        final String currentRevision = revisionOf(current);
        if (expectedRevision != null && !expectedRevision.equals(currentRevision)) {
            throw new StaleConfigException(file, expectedRevision, currentRevision);
        }
        writeAtomically(file, content);
        return revisionOf(content);
    }

    private static ConfigEntry entryOf(final Parsed parsed, final Path file, final String path) {
        return parsed.document().find(path).orElseThrow(() -> new IllegalArgumentException(
                "There is no setting called " + path + " in " + file));
    }

    /**
     * Rewrites one key, and answers with what that key should now read back as - a {@link String}
     * for a scalar, a {@link List} for a sequence of scalars, a {@link List} of {@link Map}s for a
     * {@link Kind#SECTIONS} entry.
     */
    private static Object apply(final Parsed parsed,
                                final List<String> lines,
                                final ConfigEntry entry,
                                final Span span,
                                final ConfigChange change) {
        if (entry.kind() == Kind.MAP) {
            throw new IllegalArgumentException(entry.path() + " is a nested section (line "
                    + entry.line() + ") and has no value of its own - change the keys under it");
        }
        if (!entry.editable()) {
            // The one remaining shape that reaches here is a sequence that mixes scalars and
            // mappings - a uniform sequence of mappings is Kind.SECTIONS now and editable (steward/68),
            // and a uniform sequence of scalars always was.
            throw new IllegalArgumentException(entry.path() + " is a list whose entries are not all"
                    + " the same shape (line " + entry.line() + "): this editor cannot describe it"
                    + " field by field, so it leaves it alone - edit it by hand");
        }
        return switch (change) {
            case ConfigChange.Text text -> {
                if (entry.kind() == Kind.SCALAR) {
                    yield scalar(lines, entry, span, text.text());
                }
                if (entry.kind() == Kind.SECTIONS) {
                    throw new IllegalArgumentException(entry.path() + " is a list of sections (line "
                            + entry.line() + "): send one flat record per entry, not one value");
                }
                throw new IllegalArgumentException(entry.path() + " is a list (line " + entry.line()
                        + "): send its entries, not one value");
            }
            case ConfigChange.Items items -> {
                if (entry.kind() == Kind.LIST) {
                    yield sequence(lines, entry, span, items.items());
                }
                if (entry.kind() == Kind.SECTIONS) {
                    throw new IllegalArgumentException(entry.path() + " is a list of sections (line "
                            + entry.line() + "): send one flat record per entry, not a list of plain"
                            + " values");
                }
                throw new IllegalArgumentException(entry.path() + " is a single value (line "
                        + entry.line() + "): send one value, not a list");
            }
            case ConfigChange.Sections sectionsChange -> {
                if (entry.kind() == Kind.SECTIONS) {
                    yield sections(parsed, lines, entry, span, sectionsChange.sections());
                }
                throw new IllegalArgumentException(entry.path() + " is not a list of sections (line "
                        + entry.line() + "): send a single value or a list of values instead");
            }
        };
    }

    // -----------------------------------------------------------------------------------------
    // Writing - scalars
    // -----------------------------------------------------------------------------------------

    private static String scalar(final List<String> lines,
                                 final ConfigEntry entry,
                                 final Span span,
                                 final String value) {
        final boolean multiLine = value.indexOf('\n') >= 0;
        if (multiLine && entry.type() != Type.STRING) {
            throw new IllegalArgumentException(entry.path() + " is a "
                    + entry.type().name().toLowerCase(java.util.Locale.ROOT)
                    + " in this file and cannot hold more than one line");
        }

        // The cheap case, and the common one: a single-line value staying on its single line. Only
        // the value's own characters move, so a trailing comment and the spacing around it stay
        // exactly as the operator left them.
        if (!multiLine && !span.multiLine() && span.onKeyLine()) {
            final String rendered = Scalars.render(entry.type(), value, entry.path());
            lines.set(span.line(), splice(lines.get(span.line()), span, rendered));
            return entry.type() == Type.STRING ? value : rendered;
        }

        final String keyBody = withoutLineEnding(lines.get(span.keyLine()));
        final int colon = colonOf(keyBody, span, entry);
        final String prefix = keyBody.substring(0, colon + 1);
        final String comment = trailingComment(keyBody, span, colon);
        final int last = blockExtent(lines, span.keyLine(), span.keyColumn());
        final String inner = dominantEnding(lines);

        final List<String> replacement = new ArrayList<>();
        final String rendered;
        final Optional<Scalars.Block> block = multiLine ? Scalars.block(value) : Optional.empty();
        if (block.isPresent()) {
            rendered = value;
            replacement.add(prefix + " " + block.get().header() + comment + inner);
            final String indent = " ".repeat(span.keyColumn());
            for (final String line : block.get().lines()) {
                replacement.add(line.isEmpty() ? inner : indent + line + inner);
            }
        } else {
            // Either a value with no newlines that is collapsing a block back onto one line, or a
            // multi-line value no block could carry. Both come out double-quoted at worst, which
            // reads badly and is never wrong.
            final String written = Scalars.render(entry.type(), value, entry.path());
            rendered = entry.type() == Type.STRING ? value : written;
            replacement.add(prefix + " " + written + comment + inner);
        }
        keepEndingOf(replacement, lines.get(last));
        replaceLines(lines, span.keyLine(), last, replacement);
        return rendered;
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

    // -----------------------------------------------------------------------------------------
    // Writing - sequences
    // -----------------------------------------------------------------------------------------

    private static List<String> sequence(final List<String> lines,
                                         final ConfigEntry entry,
                                         final Span span,
                                         final List<String> items) {
        final List<String> rendered = new ArrayList<>(items.size());
        for (final String item : items) {
            rendered.add(Scalars.renderItem(entry.type(), item, entry.path(), span.flow()));
        }

        // A list written `[a, b]` stays written `[a, b]`. Turning it into a block would be a
        // correct file and a diff of five lines where the operator changed one word.
        if (span.flow()) {
            lines.set(span.line(),
                    splice(lines.get(span.line()), span, "[" + String.join(", ", rendered) + "]"));
            return List.copyOf(items);
        }

        final String keyBody = withoutLineEnding(lines.get(span.keyLine()));
        final int colon = colonOf(keyBody, span, entry);
        final String comment = trailingComment(keyBody, span, colon);
        final int last = sequenceExtent(lines, span.keyLine(), span.start());
        final String inner = dominantEnding(lines);

        final List<String> replacement = new ArrayList<>();
        if (rendered.isEmpty()) {
            // The block is gone and there is nothing to put in its place. `[]` says "empty on
            // purpose" where a bare `key:` says "null", and those are two different configs.
            replacement.add(keyBody.substring(0, colon + 1) + " []" + comment + inner);
        } else {
            replacement.add(keyBody.substring(0, colon + 1) + comment + inner);
            final String indent = " ".repeat(span.start());
            for (final String item : rendered) {
                replacement.add(indent + "- " + item + inner);
            }
        }
        keepEndingOf(replacement, lines.get(last));
        replaceLines(lines, span.keyLine(), last, replacement);
        return List.copyOf(items);
    }

    // -----------------------------------------------------------------------------------------
    // Writing - sections (steward/68)
    // -----------------------------------------------------------------------------------------

    /**
     * Rewrites the fields of a {@link Kind#SECTIONS} entry that actually changed, and leaves every
     * other field - and with it every comment and the key order around it - untouched.
     *
     * <p><b>This is the whole of what steward/68 proves.</b> A field already on its own line is
     * rewritten the same way {@link #scalar} rewrites a top-level one: only the characters of that
     * value move. A field whose sent value equals what {@code entry.sections()} already read is not
     * touched at all - not re-read, not re-written, not even considered a candidate line - which is
     * what makes the surrounding comments and the key order of an entry nobody asked to change
     * provably still there afterwards, byte for byte.</p>
     *
     * <p><b>Adding or removing more than one entry in the same save is refused</b> (steward/71):
     * {@code incoming} may name exactly as many entries as {@code entry.sections()} already has -
     * an ordinary field edit, dispatched below - or exactly one more ({@link #appendSection}) or
     * one fewer ({@link #removeSection}). Anything else is a diff this class does not try to read,
     * on the same reasoning steward/68 gave for refusing every add or remove outright: guessing
     * which of several changed entries was added, removed or edited is how a config editor becomes
     * untrustworthy.</p>
     */
    private static List<Map<String, String>> sections(final Parsed parsed,
                                                       final List<String> lines,
                                                       final ConfigEntry entry,
                                                       final Span span,
                                                       final List<Map<String, String>> incoming) {
        final List<List<ConfigEntry>> existing = entry.sections();
        if (incoming.size() == existing.size() + 1) {
            return appendSection(parsed, lines, entry, span, incoming);
        }
        if (incoming.size() == existing.size() - 1) {
            return removeSection(parsed, lines, entry, incoming);
        }
        if (incoming.size() != existing.size()) {
            throw new IllegalArgumentException(entry.path() + " has " + existing.size()
                    + " entr" + (existing.size() == 1 ? "y" : "ies") + " in the file right now, but "
                    + incoming.size() + " " + (incoming.size() == 1 ? "was" : "were")
                    + " sent - this editor adds or removes exactly one entry per save (steward/71),"
                    + " not " + Math.abs(incoming.size() - existing.size()) + " at once; save one"
                    + " change at a time");
        }

        record PendingEdit(ConfigEntry field, String value) {
        }
        final List<PendingEdit> edits = new ArrayList<>();
        for (int index = 0; index < existing.size(); index++) {
            final List<ConfigEntry> fields = existing.get(index);
            final Map<String, String> wanted = incoming.get(index);
            for (final ConfigEntry field : fields) {
                final String wantedValue = wanted.get(field.key());
                if (wantedValue == null) {
                    throw new IllegalArgumentException(field.path() + " is missing from entry "
                            + index + " of " + entry.path() + " that was sent to be saved");
                }
                if (field.kind() != Kind.SCALAR) {
                    // Neither real case (tiers, languages) nests a map or a list inside one entry;
                    // refusing rather than guessing is the same choice #everyFieldIsAScalar makes
                    // for the template on the reading side.
                    throw new IllegalArgumentException(field.path() + " (line " + field.line()
                            + ") is not a plain value and cannot be changed through " + entry.path());
                }
                if (!wantedValue.equals(field.value())) {
                    edits.add(new PendingEdit(field, wantedValue));
                }
            }
        }

        // Bottom of the file upwards, exactly like the top-level write() loop and for the same
        // reason: a field rewritten as a block would change how many lines follow it, and every
        // span below it would then point at the wrong line.
        edits.sort(Comparator.comparingInt(
                (final PendingEdit edit) -> parsed.spans().get(edit.field().path()).line()).reversed());
        final Map<String, String> rendered = new HashMap<>();
        for (final PendingEdit edit : edits) {
            final Span fieldSpan = parsed.spans().get(edit.field().path());
            rendered.put(edit.field().path(), scalar(lines, edit.field(), fieldSpan, edit.value()));
        }

        final List<Map<String, String>> written = new ArrayList<>(existing.size());
        for (final List<ConfigEntry> fields : existing) {
            final Map<String, String> row = new LinkedHashMap<>();
            for (final ConfigEntry field : fields) {
                row.put(field.key(), rendered.getOrDefault(field.path(), field.value()));
            }
            written.add(Map.copyOf(row));
        }
        return List.copyOf(written);
    }

    // -----------------------------------------------------------------------------------------
    // Writing - sections: adding and removing an entry (steward/71)
    // -----------------------------------------------------------------------------------------

    /**
     * Appends one new entry to a {@link Kind#SECTIONS} list.
     *
     * <p><b>Only a pure append is accepted.</b> {@code incoming} has to carry every existing entry
     * completely unchanged, in order, plus exactly one more entry at the end. A save that both
     * edits a field and adds an entry is refused rather than guessed at - two saves, not one that
     * has to be unpicked into which value belonged to which operation.</p>
     *
     * <p><b>The new entry's shape is copied from the existing last entry, never invented</b>: the
     * same field keys, in the same order, at the same two columns (the {@code - } and the
     * continuation lines beneath it), preceded by a blank line only if the last entry already was
     * - exactly the "indentation and list style" the ticket asks not to guess at. A list with no
     * entries at all has no shape to copy and this refuses rather than inventing one; jcore's own
     * {@code ArrayCommentStyle#COMMENT_FIRST_ELEMENT} is why the last entry, rather than the first,
     * is the safe one to copy - the first is the one entry that might carry a comment the new one
     * must not inherit.</p>
     */
    private static List<Map<String, String>> appendSection(final Parsed parsed,
                                                            final List<String> lines,
                                                            final ConfigEntry entry,
                                                            final Span span,
                                                            final List<Map<String, String>> incoming) {
        final List<List<ConfigEntry>> existing = entry.sections();
        if (existing.isEmpty()) {
            throw new IllegalArgumentException(entry.path() + " (line " + entry.line() + ") has no"
                    + " entry yet to copy the shape of a new one from - add the first entry by hand");
        }
        if (span.flow()) {
            throw new IllegalArgumentException(entry.path() + " (line " + entry.line() + ") is"
                    + " written as a flow sequence: adding an entry is not supported for that style -"
                    + " write it as a block sequence by hand first");
        }
        if (!allFieldsAreScalar(existing)) {
            throw new IllegalArgumentException(entry.path() + " (line " + entry.line() + ") has a"
                    + " field that is not a plain value: adding an entry only works when every field"
                    + " of every entry is a simple value");
        }

        for (int index = 0; index < existing.size(); index++) {
            final Map<String, String> wanted = incoming.get(index);
            for (final ConfigEntry field : existing.get(index)) {
                final String wantedValue = wanted.get(field.key());
                if (wantedValue == null) {
                    throw new IllegalArgumentException(field.path() + " is missing from entry "
                            + index + " of " + entry.path() + " that was sent to be saved");
                }
                if (!wantedValue.equals(field.value())) {
                    throw new IllegalArgumentException(entry.path() + ": adding an entry cannot also"
                            + " change " + field.path() + " in the same save - save that change"
                            + " first, then add the entry");
                }
            }
        }

        final List<ConfigEntry> template = existing.getLast();
        final Map<String, String> newFields = incoming.getLast();
        // Two maps on purpose, and never one: `renderedText` is what has to appear after the colon
        // in the file - quoted, if the value needs it - and `newRow` is the logical value a re-read
        // of that same line comes back as, unquoted, which is what ConfigEntry#value() and therefore
        // #verify() below both deal in. Handing the quoted text to #verify() as if it were the value
        // is exactly the "'' instead of an empty string" bug the file wrote before this comment did.
        final Map<String, String> renderedText = new LinkedHashMap<>();
        final Map<String, String> newRow = new LinkedHashMap<>();
        for (final ConfigEntry field : template) {
            final String value = newFields.get(field.key());
            if (value == null) {
                throw new IllegalArgumentException(field.key() + " is missing from the new entry of "
                        + entry.path() + " that was sent to be saved");
            }
            if (value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(entry.path() + ": a new entry cannot hold a"
                        + " multi-line value (" + field.key() + ")");
            }
            renderedText.put(field.key(),
                    Scalars.render(field.type(), value, entry.path() + "." + field.key()));
            newRow.put(field.key(), value);
        }

        final Span firstFieldSpan = parsed.spans().get(template.getFirst().path());
        final Span secondFieldSpan = template.size() > 1
                ? parsed.spans().get(template.get(1).path()) : null;
        final int dashColumn = span.start();
        final int fieldColumn = secondFieldSpan != null ? secondFieldSpan.keyColumn() : dashColumn + 2;
        final int lastEntryStartLine = firstFieldSpan.keyLine();
        final boolean blankLineBefore = lastEntryStartLine > 0
                && withoutLineEnding(lines.get(lastEntryStartLine - 1)).isBlank();
        final String inner = dominantEnding(lines);

        final List<String> newLines = new ArrayList<>();
        if (blankLineBefore) {
            newLines.add(inner);
        }
        boolean first = true;
        for (final ConfigEntry field : template) {
            final String prefix = (first ? " ".repeat(dashColumn) + "- " : " ".repeat(fieldColumn))
                    + field.key() + ":";
            newLines.add(prefix + " " + renderedText.get(field.key()) + inner);
            first = false;
        }
        // The end of THIS list's own block, not of the file - `donation-cents` and everything else
        // AccessSpec declares after `tiers` still has to end up after the new entry, not before it.
        final int sequenceEndLine = sequenceExtent(lines, span.keyLine(), span.start());
        insertLinesAfter(lines, sequenceEndLine, newLines);

        final List<Map<String, String>> written = new ArrayList<>(existing.size() + 1);
        for (final List<ConfigEntry> fields : existing) {
            final Map<String, String> row = new LinkedHashMap<>();
            for (final ConfigEntry field : fields) {
                row.put(field.key(), field.value());
            }
            written.add(Map.copyOf(row));
        }
        written.add(Map.copyOf(newRow));
        return List.copyOf(written);
    }

    /**
     * Inserts {@code newLines} right after {@code lines.get(index)}, preserving the file's trailing
     * newline convention if {@code index} happens to be its very last line - the insert counterpart
     * of {@link #keepEndingOf}, which does the same job for a block rewritten in place.
     */
    private static void insertLinesAfter(final List<String> lines, final int index,
                                         final List<String> newLines) {
        if (index == lines.size() - 1) {
            final String last = lines.get(index);
            final String withoutEnding = withoutLineEnding(last);
            if (withoutEnding.length() == last.length() && !withoutEnding.isEmpty()) {
                // The old last line of the file had no trailing newline. It is no longer the last
                // line, so it needs one now, and the newly inserted last line inherits the "no
                // ending" state instead.
                lines.set(index, withoutEnding + dominantEnding(lines));
                final String newLast = newLines.get(newLines.size() - 1);
                newLines.set(newLines.size() - 1, withoutLineEnding(newLast));
            }
        }
        lines.addAll(index + 1, newLines);
    }

    /**
     * Removes exactly one entry from a {@link Kind#SECTIONS} list, together with the comment lines
     * that belong to it and none that belong to the next one.
     *
     * <p><b>Only a pure removal is accepted</b>, for the same reason {@link #appendSection} only
     * accepts a pure append: {@code incoming} has to be {@code existing} with exactly one entry
     * missing and every remaining entry's fields byte-for-byte unchanged, or this refuses rather
     * than guessing which entry was meant and which value was also edited.</p>
     *
     * <p><b>This is the hard half of steward/71.</b> A comment sitting directly above the removed
     * entry's own {@code - } line belongs to it and is deleted with it ({@link
     * #commentBlockStartLine}, the same walk {@link #commentsAbove} does for reading); a comment
     * directly above the <em>next</em> entry's {@code - } line belongs to that one, and is never
     * reached by this walk because it starts counting from the removed entry's own line, not the
     * removed entry's end.</p>
     */
    private static List<Map<String, String>> removeSection(final Parsed parsed,
                                                            final List<String> lines,
                                                            final ConfigEntry entry,
                                                            final List<Map<String, String>> incoming) {
        final List<List<ConfigEntry>> existing = entry.sections();
        if (!allFieldsAreScalar(existing)) {
            throw new IllegalArgumentException(entry.path() + " (line " + entry.line() + ") has a"
                    + " field that is not a plain value: removing an entry only works when every"
                    + " field of every entry is a simple value");
        }

        int removedIndex = -1;
        for (int index = 0; index < existing.size(); index++) {
            final Map<String, String> candidate = index < incoming.size() ? incoming.get(index) : null;
            if (!matchesSection(existing.get(index), candidate)) {
                removedIndex = index;
                break;
            }
        }
        if (removedIndex < 0) {
            // Every entry that incoming names at all matched exactly; the missing one is the extra
            // entry at the very end of `existing`.
            removedIndex = existing.size() - 1;
        }
        boolean ok = true;
        for (int index = removedIndex; index < incoming.size(); index++) {
            if (!matchesSection(existing.get(index + 1), incoming.get(index))) {
                ok = false;
                break;
            }
        }
        if (!ok) {
            throw new IllegalArgumentException(entry.path() + " has " + existing.size() + " entries"
                    + " in the file right now, but what was sent does not read as exactly one of"
                    + " them removed and nothing else changed - remove an entry and edit a field in"
                    + " separate saves");
        }

        final List<ConfigEntry> removed = existing.get(removedIndex);
        refuseIfProtected(entry, removed);
        final Span firstFieldSpan = parsed.spans().get(removed.getFirst().path());
        final Span lastFieldSpan = parsed.spans().get(removed.getLast().path());
        final int entryStartLine = firstFieldSpan.keyLine();
        final int entryEndLine = blockExtent(lines, lastFieldSpan.keyLine(), lastFieldSpan.keyColumn());
        final int deleteFrom = commentBlockStartLine(lines, entryStartLine);
        lines.subList(deleteFrom, entryEndLine + 1).clear();

        final List<Map<String, String>> written = new ArrayList<>(existing.size() - 1);
        for (int index = 0; index < existing.size(); index++) {
            if (index == removedIndex) {
                continue;
            }
            final Map<String, String> row = new LinkedHashMap<>();
            for (final ConfigEntry field : existing.get(index)) {
                row.put(field.key(), field.value());
            }
            written.add(Map.copyOf(row));
        }
        return List.copyOf(written);
    }

    /**
     * Refuses to remove the one section {@code entry}'s schema names as protected (steward/74),
     * before a single line of the file is touched.
     *
     * <p><b>This is the net {@code SchemaNode} could not express before steward/74.</b> A schema
     * described only the shape of a list's elements, never a rule about one specific value among
     * them, so `en` could always be removed from {@code languages} through this same method - the
     * bot's own startup validator was the only thing that ever noticed, and only on the next
     * restart. {@link ConfigEntry#protectedEntry()} is what {@code @Protected} on the
     * {@code @ConfigSpec} now puts here instead.</p>
     *
     * <p>Nothing here assumes which list this is or what the protected value means - it only reads
     * {@code entry.protectedEntry()} and one field of {@code removed}, the same way every other rule
     * in this class reads the schema rather than hardcoding a list's name.</p>
     */
    private static void refuseIfProtected(final ConfigEntry entry, final List<ConfigEntry> removed) {
        final ConfigEntry.Protected protectedEntry = entry.protectedEntry();
        if (protectedEntry == null) {
            return;
        }
        for (final ConfigEntry field : removed) {
            if (field.key().equals(protectedEntry.field())
                    && field.value().equals(protectedEntry.value())) {
                // The schema's own explanation is deliberately NOT pasted in here. Measured
                // against the running worker on 2026-09-17, `languages` answered this refusal with
                // fifteen lines of schema prose, ending mid-sentence in the browser's alert - and
                // the page showing that alert is already displaying the same explanation under the
                // list. An error says what happened; the explanation is the page's job, and Till
                // has asked for less text everywhere in this interface, not more.
                throw new IllegalArgumentException(entry.path() + ": the entry whose "
                        + protectedEntry.field() + " is '" + protectedEntry.value() + "' cannot be"
                        + " removed - the schema marks it as required.");
            }
        }
    }

    /** Whether every field of {@code fields} already reads exactly as {@code candidate} says. */
    private static boolean matchesSection(final List<ConfigEntry> fields, final Map<String, String> candidate) {
        if (candidate == null) {
            return false;
        }
        for (final ConfigEntry field : fields) {
            if (!field.value().equals(candidate.get(field.key()))) {
                return false;
            }
        }
        return true;
    }

    /** Whether every field of every entry is a plain value - {@link #appendSection} and {@link
     * #removeSection} both stop rather than guess at a shape with a nested map or list inside it,
     * the same restriction {@link #everyFieldIsAScalar} places on building a {@code template}. */
    private static boolean allFieldsAreScalar(final List<List<ConfigEntry>> sections) {
        return sections.stream().flatMap(List::stream).allMatch(field -> field.kind() == Kind.SCALAR);
    }

    /**
     * The same shape {@link #sections} writes, read back out of an already-parsed
     * {@link Kind#SECTIONS} entry - what {@link #verify} compares the write above against.
     */
    private static List<Map<String, String>> sectionValuesOf(final ConfigEntry entry) {
        final List<Map<String, String>> result = new ArrayList<>(entry.sections().size());
        for (final List<ConfigEntry> section : entry.sections()) {
            final Map<String, String> row = new LinkedHashMap<>();
            for (final ConfigEntry field : section) {
                row.put(field.key(), field.value());
            }
            result.add(Map.copyOf(row));
        }
        return result;
    }

    // -----------------------------------------------------------------------------------------
    // Writing - the lines themselves
    // -----------------------------------------------------------------------------------------

    /**
     * The colon that ends the key.
     *
     * <p>Found by searching from the end of the key rather than from the start of the line,
     * because a key may contain one: {@code 12:00: something} is a key and a value.</p>
     */
    private static int colonOf(final String keyBody, final Span span, final ConfigEntry entry) {
        final int colon = keyBody.indexOf(':', Math.min(span.keyEndColumn(), keyBody.length()));
        if (colon < 0) {
            throw new IllegalStateException("Refusing to write " + entry.path()
                    + ": line " + entry.line() + " has no colon after the key."
                    + " This is a bug in ConfigFiles.");
        }
        return colon;
    }

    /**
     * Whatever comment sits on the key's line after the value begins, so a rewrite does not eat
     * it.
     *
     * <p>Where "after the value begins" is depends on the shape: past the block header for
     * {@code motd: |- # why}, past the value for {@code port: 8080 # why}, and past the colon when
     * the value is on a later line altogether.</p>
     */
    private static String trailingComment(final String keyBody, final Span span, final int colon) {
        final int from;
        if (!span.onKeyLine()) {
            from = colon + 1;
        } else if (span.block()) {
            from = endOfToken(keyBody, span.start());
        } else {
            from = span.end();
        }
        final String rest = keyBody.substring(Math.min(Math.max(from, 0), keyBody.length()));
        return rest.strip().startsWith("#") ? rest : "";
    }

    private static int endOfToken(final String body, final int from) {
        int i = Math.min(Math.max(from, 0), body.length());
        while (i < body.length() && !Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * The last line belonging to a key, measured by indentation.
     *
     * <p>SnakeYAML's end mark for a block sits on the line <em>after</em> it, and how far after
     * depends on what follows, so it cannot be used to decide which lines to replace. Indentation
     * can: everything under a key is indented past it, a blank line belongs to the block only when
     * something deeper follows it, and the first line back at the key's own column ends it.</p>
     */
    private static int blockExtent(final List<String> lines, final int keyLine, final int keyColumn) {
        int last = keyLine;
        for (int i = keyLine + 1; i < lines.size(); i++) {
            final String text = withoutLineEnding(lines.get(i));
            if (text.isBlank()) {
                continue;
            }
            if (indentOf(text) <= keyColumn) {
                break;
            }
            last = i;
        }
        return last;
    }

    /**
     * The last line belonging to a block sequence.
     *
     * <p>{@link #blockExtent} cannot answer this one. A block sequence is allowed to sit at its
     * key's own column - SnakeYAML's dumper writes it that way, so every list jcore has ever
     * written looks like this:</p>
     *
     * <pre>stop-services:
     *- smp
     *- discord-bot</pre>
     *
     * <p>Measuring by "deeper than the key" therefore finds nothing, and a rewrite that trusted it
     * would insert the new entries and leave the old ones standing underneath. It did, once.</p>
     *
     * @param itemIndent the column the first {@code -} sits at
     */
    private static int sequenceExtent(final List<String> lines, final int keyLine, final int itemIndent) {
        int last = keyLine;
        for (int i = keyLine + 1; i < lines.size(); i++) {
            final String text = withoutLineEnding(lines.get(i));
            if (text.isBlank()) {
                continue;
            }
            final int indent = indentOf(text);
            if (indent > itemIndent) {
                // A continuation line of an entry, or a mapping inside one.
                last = i;
            } else if (indent == itemIndent && isEntry(text, indent)) {
                last = i;
            } else {
                break;
            }
        }
        return last;
    }

    /** Whether a line at this indentation is a {@code - entry} rather than the next key. */
    private static boolean isEntry(final String text, final int indent) {
        return text.charAt(indent) == '-'
                && (text.length() == indent + 1 || Character.isWhitespace(text.charAt(indent + 1)));
    }

    private static int indentOf(final String text) {
        int i = 0;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** The line ending this file uses, taken from the first line that has one. */
    private static String dominantEnding(final List<String> lines) {
        for (final String line : lines) {
            final String ending = line.substring(withoutLineEnding(line).length());
            if (!ending.isEmpty()) {
                return ending;
            }
        }
        return "\n";
    }

    /**
     * Gives the last replacement line the ending the last replaced line had, so a file that ends
     * without a newline still ends without one.
     */
    private static void keepEndingOf(final List<String> replacement, final String replaced) {
        final String ending = replaced.substring(withoutLineEnding(replaced).length());
        final String lastLine = replacement.getLast();
        replacement.set(replacement.size() - 1, withoutLineEnding(lastLine) + ending);
    }

    private static void replaceLines(final List<String> lines, final int from, final int to,
                                     final List<String> replacement) {
        lines.subList(from, to + 1).clear();
        lines.addAll(from, replacement);
    }

    /**
     * Reads the content back before it is written, and fails if any changed key does not now say
     * what it was asked to say.
     *
     * <p>This is the guard that makes a quoting bug a refused save instead of a config an operator
     * has to repair by hand - and a service that will not start.</p>
     */
    private static void verify(final Path file, final String content, final Map<String, Object> expected) {
        final Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(content));
        } catch (final YAMLException e) {
            throw new IllegalStateException("Refusing to write " + file
                    + ": the edited content is not valid YAML. This is a bug in ConfigFiles.", e);
        }
        final List<ConfigEntry> entries = new ArrayList<>();
        try {
            collect(file, (MappingNode) root, "", splitKeepingLineEndings(content), entries, new HashMap<>(),
                    Optional.empty(), Optional.empty());
        } catch (final IOException | ClassCastException e) {
            throw new IllegalStateException("Refusing to write " + file
                    + ": the edited content cannot be read back. This is a bug in ConfigFiles.", e);
        }
        final ConfigDocument document = new ConfigDocument(file, revisionOf(content), List.of(), entries);
        expected.forEach((path, value) -> {
            final ConfigEntry entry = document.find(path)
                    .orElseThrow(() -> new IllegalStateException("Refusing to write " + file
                            + ": " + path + " disappeared from the edited content."
                            + " This is a bug in ConfigFiles."));
            final Object actual = switch (entry.kind()) {
                case LIST -> entry.items();
                case SECTIONS -> sectionValuesOf(entry);
                case SCALAR, MAP -> entry.value();
            };
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
            keepTheOwnerAndMode(file, temp);
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

    /**
     * Gives {@code temp} the owner, group and permissions {@code file} already has, so the move
     * changes none of them.
     *
     * <p>{@link Files#createTempFile} makes an owner-only file owned by whichever process is
     * writing, and that is the right default for a temporary file - but this one is about to
     * <em>become</em> the destination. A {@code config.yml} that was {@code rw-r--r--} would come
     * back {@code rw-------} from one click in the browser, and nothing would say so; this stack
     * does now run as a second user, which is the whole reason this class exists - the worker
     * edits every other service's config through a mount under {@code /configs/}, as root, while
     * every one of those services runs as its own uid. Carrying the mode across and leaving the
     * owner at whoever is writing (steward/104: {@code steward-ui.yml} came back owned by root,
     * mode untouched) is a save that looks fine until the service it belongs to is next re-created
     * and finds its own configuration unreadable - silently, because the container that already
     * has the file open never notices.</p>
     *
     * <p>Owner and group are a POSIX file's own attributes, not the permission bits, so they need
     * their own carry-across - {@link PosixFileAttributeView#setOwner} and {@link
     * PosixFileAttributeView#setGroup}, each a {@code chown}, which only the process that already
     * owns the file or one allowed to give ownership away may call. The worker runs as root here,
     * so it may.</p>
     *
     * <p>A failure here is not swallowed. Reporting a saved file whose owner, group or permissions
     * are not the ones it had is the failure this method exists to prevent, so it fails the save
     * instead.</p>
     */
    private static void keepTheOwnerAndMode(final Path file, final Path temp) throws IOException {
        final PosixFileAttributeView destination =
                Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (destination == null) {
            // Not a POSIX filesystem. There is nothing to carry across and nothing to report.
            return;
        }
        final PosixFileAttributes attributes;
        try {
            attributes = destination.readAttributes();
        } catch (final NoSuchFileException e) {
            // A file that is not there yet has no owner or permissions to keep; the restrictive
            // default of the temporary file, owned by whichever process is writing it, is then the
            // better of the two answers.
            return;
        }
        Files.setPosixFilePermissions(temp, attributes.permissions());
        final PosixFileAttributeView temporary =
                Files.getFileAttributeView(temp, PosixFileAttributeView.class);
        temporary.setGroup(attributes.group());
        temporary.setOwner(attributes.owner());
    }

    // -----------------------------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------------------------

    /** The suffix {@link eu.nordtal.jcore.config.schema.SchemaWriter#schemaFileFor} always writes. */
    private static final String SCHEMA_SUFFIX = ".schema.json";

    /**
     * jcore's own copy of the file as it was before the last write. Excluded by name, decided by
     * Till on 2026-09-16 when the broadening below was measured against the running mount and
     * turned up six of them.
     *
     * <p>It is the one new entry that would be actively wrong rather than merely noisy: it is
     * YAML, it parses, it draws a perfectly ordinary form, and every edit made in that form is
     * written to a file nothing reads. A page that offers a control which does nothing is worse
     * than a page missing a file.</p>
     */
    private static final String BACKUP_SUFFIX = ".bak";

    /**
     * A directory whose contents are scratch, not configuration - {@code spark/tmp} holds profiler
     * dumps and an {@code about.txt}, and the same name is the convention everywhere else. Matched
     * as a whole path segment, so a file honestly called {@code tmp.yml} is still listed.
     */
    private static final String SCRATCH_DIRECTORY = "tmp";

    /** How many bytes of a file {@link #isProbablyText} looks at before deciding. */
    private static final int SNIFF_LENGTH = 8000;

    /**
     * Every config file under the mount, one directory per service.
     *
     * <p>{@code /configs/steward-worker/steward.yml} is service {@code steward-worker}, name
     * {@code steward.yml}; {@code /configs/smp/nordtal-smp/config.yml} is service {@code smp}, name
     * {@code nordtal-smp/config.yml}. A file lying directly in the root has no service directory
     * above it and is reported with an empty service rather than dropped - a file the page does not
     * list is a file nobody will go looking for.</p>
     *
     * <p><b>Not only {@code .yml} any more (steward/55).</b> A directory under this mount holds a
     * plugin's {@code README.txt}, a {@code spark/config.json}, a {@code voicechat-server.properties}
     * - real files this stack already has, that used to be invisible to this page purely because of
     * their extension. So this no longer filters by name at all; it filters by content, the same way
     * {@code git} and {@code grep} decide a file is worth treating as text: {@link #isProbablyText}
     * sniffs the first few kilobytes for a NUL byte, which no text encoding this stack writes ever
     * contains and every binary format eventually does. A file this process cannot read is not
     * sniffed and not excluded either - hiding a config nobody can open yet is a worse answer than
     * showing it and letting {@link ConfigLocation#readable()} say why it is dead.</p>
     *
     * <p>Three things are excluded by name rather than by content, and each for its own reason.
     * Every {@code <name>.schema.json} jcore writes beside a config file is the description of
     * another file in this listing, never a file of its own. Every {@code *.bak} is jcore's copy of
     * a file as it was before the last write - it is YAML, it parses, and it would draw a form
     * whose every control writes to something nothing reads. And anything under a {@code tmp}
     * directory is scratch: {@code spark/tmp} holds profiler dumps and a stray {@code about.txt}.
     * Measured against the running mount on 2026-09-16, those three rules are the difference
     * between 49 files and 39, and the six {@code .bak} among them are the reason the rule exists
     * at all.</p>
     *
     * <p>Anything under a {@code messages} directory is left out as well: those are the saved
     * translations {@link MessageBundles} already shows as a bundle, not configuration.</p>
     *
     * @param root the mount point
     * @return every text file beneath it, by service then name. <b>Empty if the root does not
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
                    // A LINK IS NOT A CONFIG FILE. `isRegularFile` follows one, so a link dropped
                    // into a shared config volume would be listed, read and written through -
                    // wherever it points. That is the one way out of this mount, and this class's
                    // whole claim is that there is none: the browser's string is matched against
                    // this list and never joined onto a path.
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !path.getFileName().toString().endsWith(SCHEMA_SUFFIX))
                    .filter(path -> !path.getFileName().toString().endsWith(BACKUP_SUFFIX))
                    // A MARKER IS NOT A CONFIG FILE either (steward/76). Services write
                    // `<name>.env-overrides.txt` beside their own config, and without this line
                    // every one of them would appear on the service page as a configuration in its
                    // own right - listed, opened, and editable, which is worse than merely
                    // confusing: editing it would change what the warning says without changing a
                    // single thing about what the environment actually overrides. The `.tmp` is
                    // matched too because `write` moves one into place, and `discover` can walk
                    // the directory inside that window.
                    .filter(path -> !path.getFileName().toString().endsWith(EnvOverrideFile.SUFFIX))
                    .filter(path -> !path.getFileName().toString().endsWith(EnvOverrideFile.SUFFIX + ".tmp"))
                    .filter(path -> isUnderNoScratchDirectory(root, path))
                    .filter(ConfigFiles::isProbablyText)
                    .map(path -> locationOf(root, path))
                    .sorted(Comparator.comparing(ConfigLocation::service)
                            .thenComparing(ConfigLocation::name))
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot list the config files under " + root, e);
        }
    }

    /**
     * Whether no directory between {@code root} and {@code path} is a scratch directory, or holds
     * saved translations - those are a {@link MessageBundles} bundle, not a config file.
     *
     * <p>Compared segment by segment rather than with {@code contains}, so {@code smp/tmp/about.txt}
     * is excluded and {@code smp/tmpl/config.yml} is not.</p>
     *
     * <p><b>Relative to the root, and that is not a detail.</b> Walking the absolute path would
     * mean every segment above the mount counts too - and this project's own test fixtures live
     * under {@code /tmp}, so the first version of this rule matched the mount itself and hid every
     * file in it. The rule is about the layout inside the volume; nothing above it is ours to
     * read.</p>
     */
    private static boolean isUnderNoScratchDirectory(final Path root, final Path path) {
        for (final Path segment : root.relativize(path)) {
            if (segment.toString().equals(SCRATCH_DIRECTORY)
                    || segment.toString().equals(MessageBundles.DIRECTORY)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a file looks like text rather than a binary format, by the same heuristic {@code git}
     * and {@code grep} use: a NUL byte in the first few kilobytes means binary, because no text
     * encoding this stack ever writes contains one.
     *
     * <p>A file this process cannot read is treated as text rather than excluded - there is nothing
     * to sniff, and hiding a config nobody can open (yet) is a worse answer than listing it dead. An
     * empty file is text; there is nothing in it to say otherwise.</p>
     */
    private static boolean isProbablyText(final Path path) {
        if (!Files.isReadable(path)) {
            return true;
        }
        try (var in = Files.newInputStream(path)) {
            final byte[] buffer = new byte[SNIFF_LENGTH];
            final int read = in.read(buffer);
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) {
                    return false;
                }
            }
            return true;
        } catch (final IOException e) {
            // Unreadable in a way `Files.isReadable` did not catch - a permission race, a link
            // whose target vanished after the check above. Same answer as above and for the same
            // reason.
            return true;
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
        return new ConfigLocation(service, name.toString(), file, Files.isReadable(file), writable);
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

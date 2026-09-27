package eu.nordtal.s2.steward.worker.configfile;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SettingKind;
import eu.nordtal.s2.steward.worker.configfile.ConfigEntry.Kind;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Reads a config file as a form, matching each key against the schema beside it when there is one.
 *
 * The schema is the first choice, never the only one. A file with no schema, or one nothing ever described, reads
 * exactly as it always has: a mechanical {@link Labels#of(String)} label and whatever comment block sits above the
 * key. And whichever source wins, the file is still the truth about what keys exist: a key the schema does not
 * mention is delivered anyway, never hidden, and a schema entry with nothing behind it in the file is silently
 * ignored rather than invented as an entry.
 */
final class ConfigFileReader {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigFileReader.class);

    private ConfigFileReader() {}

    static Parsed parse(final Path file) throws IOException {
        final String content = Files.readString(file, StandardCharsets.UTF_8);
        final List<String> lines = LineEdits.splitKeepingLineEndings(content);

        final Node root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(content));
        } catch (final MarkedYAMLException e) {
            throw new IOException(file + " is not valid YAML: line " + LineEdits.lineOf(e) + ": " + e.getProblem(), e);
        } catch (final YAMLException e) {
            throw new IOException(file + " is not valid YAML: " + e.getMessage(), e);
        }

        if (root == null) {
            // An empty file, or one that is nothing but comments: everything in it is the header.
            return new Parsed(
                    new ConfigDocument(
                            file, ConfigFiles.revisionOf(content), headerOf(lines, Integer.MAX_VALUE), List.of()),
                    lines,
                    Map.of());
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
        // Flat, unlike schema: an overridden path is already dotted, so the same Set works at every nesting level.
        final Optional<Set<String>> overridden = EnvOverrides.read(file);
        collect(file, mapping, "", lines, entries, spans, schema, overridden);

        final int firstKeyLine =
                entries.isEmpty() ? Integer.MAX_VALUE : entries.getFirst().line() - 1;
        return new Parsed(
                new ConfigDocument(file, ConfigFiles.revisionOf(content), headerOf(lines, firstKeyLine), entries),
                lines,
                spans);
    }

    /**
     * Walks one mapping level, matching each key against {@code schemaLevel} when there is one.
     *
     * @param schemaLevel the schema's children at this level, keyed the same way the file is, or empty when there
     *     is nothing here to compare against - every key at this level is then vacuously
     *     {@link ConfigEntry#inSchema()}, since there is nothing for it to be missing from
     */
    static void collect(
            final Path file,
            final MappingNode mapping,
            final String prefix,
            final List<String> lines,
            final List<ConfigEntry> entries,
            final Map<String, Span> spans,
            final Optional<Map<String, SchemaNode>> schemaLevel,
            final Optional<Set<String>> overridden)
            throws IOException {
        final Set<String> matchedSchemaKeys = new HashSet<>();
        for (final NodeTuple tuple : mapping.getValue()) {
            collectOne(file, tuple, prefix, lines, entries, spans, schemaLevel, overridden, matchedSchemaKeys);
        }
        warnAboutUnmatchedSchema(file, schemaLevel, matchedSchemaKeys);
    }

    private static void collectOne(
            final Path file,
            final NodeTuple tuple,
            final String prefix,
            final List<String> lines,
            final List<ConfigEntry> entries,
            final Map<String, Span> spans,
            final Optional<Map<String, SchemaNode>> schemaLevel,
            final Optional<Set<String>> overridden,
            final Set<String> matchedSchemaKeys)
            throws IOException {
        final ScalarNode keyNode = keyNodeOf(file, tuple);
        final String key = keyNode.getValue();
        final String path = prefix.isEmpty() ? key : prefix + "." + key;
        final Node valueNode = tuple.getValueNode();
        final int keyLine = keyNode.getStartMark().getLine();

        final SchemaNode schemaChild = schemaLevel.map(level -> level.get(key)).orElse(null);
        final boolean inSchema = schemaLevel.isEmpty() || schemaChild != null;
        if (schemaChild != null) {
            matchedSchemaKeys.add(key);
        }

        final Classified classified = classify(file, valueNode, schemaChild, path, lines, spans, overridden);
        spans.put(path, spanOf(keyNode, valueNode));
        entries.add(entryOf(path, key, schemaChild, lines, keyLine, classified, inSchema, overridden));

        if (valueNode instanceof MappingNode nested) {
            final Optional<Map<String, SchemaNode>> nestedSchema =
                    (schemaChild != null && schemaChild.kind() == SettingKind.MAP)
                            ? Optional.of(schemaChild.children())
                            : Optional.empty();
            collect(file, nested, path, lines, entries, spans, nestedSchema, overridden);
        }
    }

    private static ScalarNode keyNodeOf(final Path file, final NodeTuple tuple) throws IOException {
        if (!(tuple.getKeyNode() instanceof ScalarNode keyNode)) {
            throw new IOException(file + ": line "
                    + (tuple.getKeyNode().getStartMark().getLine() + 1) + ": only plain keys are supported, found a "
                    + tuple.getKeyNode().getNodeId());
        }
        return keyNode;
    }

    private static void warnAboutUnmatchedSchema(
            final Path file, final Optional<Map<String, SchemaNode>> schemaLevel, final Set<String> matchedKeys) {
        if (schemaLevel.isEmpty()) {
            return;
        }
        final Set<String> extra = new TreeSet<>(schemaLevel.get().keySet());
        extra.removeAll(matchedKeys);
        if (!extra.isEmpty()) {
            // The file is the truth: a schema entry with nothing behind it is a dropped setting, not an error.
            LOG.warn(
                    "{}: the schema names {} setting(s) the file does not have: {}. The file wins; they are"
                            + " ignored.",
                    file,
                    extra.size(),
                    String.join(", ", extra));
        }
    }

    /** What one key's value classifies as, before it becomes a {@link ConfigEntry}. */
    private record Classified(
            Kind kind,
            ConfigEntry.Type type,
            String value,
            List<String> items,
            List<ConfigEntry> template,
            List<List<ConfigEntry>> sections,
            boolean editable) {}

    private static Classified classify(
            final Path file,
            final Node valueNode,
            final @Nullable SchemaNode schemaChild,
            final String path,
            final List<String> lines,
            final Map<String, Span> spans,
            final Optional<Set<String>> overridden)
            throws IOException {
        if (valueNode instanceof MappingNode) {
            // A section is a heading, not a value: there is nothing here to change that is not one of its keys.
            return new Classified(Kind.MAP, ConfigEntry.Type.STRING, "", List.of(), List.of(), List.of(), false);
        }
        if (valueNode instanceof SequenceNode sequence
                && sequence.getValue().stream().allMatch(item -> item instanceof MappingNode)
                && (!sequence.getValue().isEmpty() || describesSections(schemaChild))) {
            return classifySections(file, sequence, schemaChild, path, lines, spans, overridden);
        }
        if (valueNode instanceof SequenceNode sequence) {
            return classifyList(sequence);
        }
        final ScalarNode scalar = (ScalarNode) valueNode;
        return new Classified(
                Kind.SCALAR, Scalars.typeOf(scalar), scalar.getValue(), List.of(), List.of(), List.of(), true);
    }

    /**
     * A sequence of mappings, the shape {@code languages} and {@code tiers} are written as.
     *
     * Reading and writing are split on purpose: writing only ever replaces a scalar already on a line, so an
     * existing entry's own field can change, but inserting or deleting a whole entry needs its own logic, in
     * {@code SectionWriter}.
     */
    private static Classified classifySections(
            final Path file,
            final SequenceNode sequence,
            final @Nullable SchemaNode schemaChild,
            final String path,
            final List<String> lines,
            final Map<String, Span> spans,
            final Optional<Set<String>> overridden)
            throws IOException {
        final Optional<Map<String, SchemaNode>> elementSchema = schemaChild != null && describesSections(schemaChild)
                ? Optional.of(schemaChild.children())
                : Optional.empty();
        final List<List<ConfigEntry>> collected = new ArrayList<>();
        for (int index = 0; index < sequence.getValue().size(); index++) {
            final MappingNode element = (MappingNode) sequence.getValue().get(index);
            final List<ConfigEntry> fields = new ArrayList<>();
            collect(file, element, path + "[" + index + "]", lines, fields, spans, elementSchema, overridden);
            collected.add(List.copyOf(fields));
        }
        // The card's shape comes from the schema: a nested map is the one field a card still has no place for.
        final List<ConfigEntry> template = elementSchema
                .filter(ConfigFileReader::isCardShaped)
                .map(ConfigFileReader::templateOf)
                .orElse(List.of());
        return new Classified(
                Kind.SECTIONS, ConfigEntry.Type.STRING, "", List.of(), template, List.copyOf(collected), true);
    }

    private static Classified classifyList(final SequenceNode sequence) {
        final List<ScalarNode> scalars = new ArrayList<>();
        boolean plain = true;
        for (final Node item : sequence.getValue()) {
            if (item instanceof ScalarNode scalar) {
                scalars.add(scalar);
            } else {
                plain = false;
            }
        }
        final List<String> items = scalars.stream().map(ScalarNode::getValue).toList();
        final ConfigEntry.Type type = plain ? sharedType(scalars) : ConfigEntry.Type.STRING;
        // A sequence mixing scalars and mappings is left exactly as before: raw and not editable.
        return new Classified(Kind.LIST, type, "", items, List.of(), List.of(), plain);
    }

    private static Span spanOf(final ScalarNode keyNode, final Node valueNode) {
        return new Span(
                keyNode.getStartMark().getLine(),
                keyNode.getStartMark().getColumn(),
                keyNode.getEndMark().getColumn(),
                valueNode.getStartMark().getLine(),
                valueNode.getStartMark().getColumn(),
                valueNode.getEndMark().getColumn(),
                valueNode.getEndMark().getLine(),
                valueNode instanceof SequenceNode sequence && sequence.getFlowStyle() == DumperOptions.FlowStyle.FLOW,
                valueNode instanceof ScalarNode scalar
                        && (scalar.getScalarStyle() == DumperOptions.ScalarStyle.LITERAL
                                || scalar.getScalarStyle() == DumperOptions.ScalarStyle.FOLDED));
    }

    private static ConfigEntry entryOf(
            final String path,
            final String key,
            final @Nullable SchemaNode schemaChild,
            final List<String> lines,
            final int keyLine,
            final Classified classified,
            final boolean inSchema,
            final Optional<Set<String>> overridden) {
        return new ConfigEntry(
                path,
                key,
                schemaChild != null ? schemaChild.label() : Labels.of(key),
                commentsAbove(lines, keyLine),
                schemaChild != null ? schemaChild.explanation() : "",
                schemaChild != null && schemaChild.noExplanationNeeded(),
                classified.value(),
                classified.items(),
                classified.template(),
                classified.sections(),
                classified.kind(),
                classified.type(),
                keyLine + 1,
                classified.editable(),
                // A schema saying secret=true always wins; secret=false or no schema entry never turns it off.
                ConfigEntry.isSecretKey(key) || (schemaChild != null && schemaChild.secret()),
                inSchema,
                overridden.map(paths -> paths.contains(path)).orElse(null),
                choicesOf(schemaChild),
                protectedEntryOf(schemaChild));
    }

    /** {@link ConfigEntry.Choices}, from a schema entry's own {@link SchemaNode.Choices}, or {@code null}. */
    static ConfigEntry.@Nullable Choices choicesOf(final @Nullable SchemaNode schemaChild) {
        if (schemaChild == null || schemaChild.choices() == null) {
            return null;
        }
        return new ConfigEntry.Choices(
                schemaChild.choices().values(), schemaChild.choices().strict());
    }

    /**
     * {@link ConfigEntry.Protected}, from a schema entry's own {@link SchemaNode.ProtectedEntry}, or {@code null}
     * when there is no schema for this key or its property carries no {@code @Protected}.
     */
    static ConfigEntry.@Nullable Protected protectedEntryOf(final @Nullable SchemaNode schemaChild) {
        if (schemaChild == null || schemaChild.protectedEntry() == null) {
            return null;
        }
        return new ConfigEntry.Protected(
                schemaChild.protectedEntry().field(),
                schemaChild.protectedEntry().value());
    }

    /** Whether a schema entry is a list whose elements are sections: it describes their fields. */
    static boolean describesSections(final @Nullable SchemaNode schema) {
        return schema != null
                && schema.kind() == SettingKind.LIST
                && !schema.children().isEmpty();
    }

    /**
     * Whether a {@link Kind#SECTIONS} element's schema can be drawn as a card.
     *
     * Every field must be a value, a list of values, or a list of sections that is itself card-shaped. A nested map
     * inside an element is the one shape left out: neither the card nor the writer has a place for a heading inside
     * an entry.
     */
    private static boolean isCardShaped(final Map<String, SchemaNode> elementSchema) {
        return elementSchema.values().stream().allMatch(field -> switch (field.kind()) {
            case SCALAR -> true;
            case LIST -> field.children().isEmpty() || isCardShaped(field.children());
            case MAP -> false;
        });
    }

    /**
     * The blank card an "Add entry" starts from.
     *
     * One {@link ConfigEntry} per field the schema describes for one element, in schema order.
     *
     * @param elementSchema the schema's own shape of one element; see {@link SchemaNode#children()}'s doc for a
     *     {@link SettingKind#LIST}
     */
    static List<ConfigEntry> templateOf(final Map<String, SchemaNode> elementSchema) {
        final List<ConfigEntry> fields = new ArrayList<>();
        for (final Map.Entry<String, SchemaNode> field : elementSchema.entrySet()) {
            final String key = field.getKey();
            final SchemaNode schema = field.getValue();
            final boolean sections = describesSections(schema);
            final Kind kind = schema.kind() == SettingKind.SCALAR ? Kind.SCALAR : sections ? Kind.SECTIONS : Kind.LIST;
            fields.add(new ConfigEntry(
                    key,
                    key,
                    schema.label(),
                    List.of(),
                    schema.explanation(),
                    schema.noExplanationNeeded(),
                    "",
                    List.of(),
                    sections ? templateOf(schema.children()) : List.of(),
                    List.of(),
                    kind,
                    schema.type() != null && !sections
                            ? ConfigEntry.Type.valueOf(schema.type().name())
                            : ConfigEntry.Type.STRING,
                    0,
                    true,
                    ConfigEntry.isSecretKey(key) || schema.secret(),
                    true,
                    // A template field is a blank shape, never a value an environment variable could have overridden.
                    null,
                    choicesOf(schema),
                    // @Protected names a section by one field's value; only a nested list of sections has entries.
                    sections ? protectedEntryOf(schema) : null));
        }
        return List.copyOf(fields);
    }

    /**
     * The type every entry of a list shares, or {@link ConfigEntry.Type#STRING} when they differ.
     *
     * It decides how a new entry is written back: without it a list of ports would come back from a form as a list
     * of quoted strings, a file that still parses but no longer loads as a config.
     */
    private static ConfigEntry.Type sharedType(final List<ScalarNode> items) {
        ConfigEntry.Type shared = null;
        for (final ScalarNode item : items) {
            final ConfigEntry.Type type = Scalars.typeOf(item);
            if (shared == null) {
                shared = type;
            } else if (shared != type) {
                return ConfigEntry.Type.STRING;
            }
        }
        return shared == null ? ConfigEntry.Type.STRING : shared;
    }

    /**
     * The comment block directly above a key.
     *
     * Indentation is no part of this: the line is stripped before it is looked at, since a hand-edited file may not
     * indent a nested key's comment to that key's column. A blank line ends the block.
     */
    static List<String> commentsAbove(final List<String> lines, final int keyLine) {
        final List<String> block = new ArrayList<>();
        for (int i = commentBlockStartLine(lines, keyLine); i < keyLine; i++) {
            block.add(
                    stripCommentMarker(LineEdits.withoutLineEnding(lines.get(i)).strip()));
        }
        return List.copyOf(block);
    }

    /**
     * The first line of the contiguous {@code #} comment block directly above {@code keyLine}, or {@code keyLine}
     * itself when there is none directly above it.
     *
     * The section-removal writer is the other caller: deleting an entry has to delete the comment lines that
     * belong to it, and stop before the comment that belongs to the entry above.
     */
    static int commentBlockStartLine(final List<String> lines, final int keyLine) {
        int start = keyLine;
        for (int i = keyLine - 1; i >= 0; i--) {
            final String text = LineEdits.withoutLineEnding(lines.get(i)).strip();
            if (text.isEmpty() || !text.startsWith("#")) {
                break;
            }
            start = i;
        }
        return start;
    }

    /**
     * The header block: the comments at the very top of the file, if a blank line separates them from the first key.
     *
     * Without that blank line they are the first key's comment and not a header.
     */
    private static List<String> headerOf(final List<String> lines, final int firstKeyLine) {
        int i = 0;
        final List<String> header = new ArrayList<>();
        while (i < lines.size()) {
            final String text = LineEdits.withoutLineEnding(lines.get(i)).strip();
            if (!text.startsWith("#")) {
                break;
            }
            header.add(stripCommentMarker(text));
            i++;
        }
        if (header.isEmpty()) {
            return List.of();
        }
        // Not merely "is not blank": a file starting with comments then `---` still has a header and no key there.
        final boolean attachedToAKey =
                i < lines.size() && !LineEdits.withoutLineEnding(lines.get(i)).isBlank() && i >= firstKeyLine;
        return attachedToAKey ? List.of() : List.copyOf(header);
    }

    /** {@code "# text"} becomes {@code "text"}; jcore writes a blank comment line as {@code "# "}. */
    private static String stripCommentMarker(final String commentLine) {
        final String withoutHash = commentLine.substring(1);
        return withoutHash.startsWith(" ") ? withoutHash.substring(1) : withoutHash;
    }
}

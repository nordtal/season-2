package eu.nordtal.season.steward.stack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.spec.schema.SettingType;
import eu.nordtal.season.steward.settings.SettingsDocument;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Derives a form from a plugin's YAML or JSON file and writes a form's changes back into its text.
 *
 * A save replaces only the characters of the changed values, so every other byte of the file stays as it was. A file
 * where that cannot hold for every value (block scalars, anchors, lists of maps, a comment inside a list) has no form
 * and opens as text.
 */
final class PluginYaml {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?");
    private static final Set<String> TRUE = Set.of("true", "yes", "on");

    private PluginYaml() {}

    /** Returns the form of the file, or null when it opens as text. */
    static @Nullable List<SettingsDocument.Entry> entries(final String path, final String text) {
        final Read read = read(path, text);
        return read == null ? null : read.entries();
    }

    /**
     * Returns {@code text} with the values in {@code changes} written in, each a string or a list of strings by path.
     *
     * @throws RequestRefused when a path is no value of the file or a value is not of its type
     * @throws BadRequestResponse when the file has no form or a value has the wrong shape
     */
    static String apply(final String path, final String text, final JsonObject changes) {
        final Read read = read(path, text);
        if (read == null) {
            throw new BadRequestResponse(path + " has no form; send its whole text");
        }
        final Map<String, String> expected = new LinkedHashMap<>();
        read.slots().values().forEach(slot -> expected.put(slot.entry().path(), valueOf(slot.entry())));
        final List<Edit> edits = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> change : changes.entrySet()) {
            final Slot slot = read.slots().get(change.getKey());
            if (slot == null) {
                throw new RequestRefused(400, ANSWER.noSetting(path, change.getKey()));
            }
            final Edit edit = slot.edit(change.getValue(), read.json());
            edits.add(edit);
            expected.put(slot.entry().path(), edit.value());
        }
        final StringBuilder out = new StringBuilder(text);
        edits.stream()
                .sorted(Comparator.comparingInt(Edit::start).reversed())
                .forEach(edit -> out.replace(edit.start(), edit.end(), edit.replacement()));
        final String written = out.toString();
        // A bug here must not corrupt a config: the result is read back and every value compared.
        final Read back = read(path, written);
        final Map<String, String> actual = back == null
                ? Map.of()
                : back.slots().values().stream()
                        .collect(Collectors.toMap(slot -> slot.entry().path(), slot -> valueOf(slot.entry())));
        if (!expected.equals(actual)) {
            throw new BadRequestResponse(path + " cannot hold these values as written; edit its text instead");
        }
        return written;
    }

    private record Read(List<SettingsDocument.Entry> entries, Map<String, Slot> slots, boolean json) {}

    /** One value of the file and the characters a save may replace for it. */
    private record Slot(
            SettingsDocument.Entry entry,
            int start,
            int end,
            boolean block,
            boolean flowContext,
            boolean padded,
            String indent,
            String ending) {

        Edit edit(final JsonElement sent, final boolean json) {
            final String key = entry.path();
            if (entry.kind() == SettingsDocument.Shape.LIST) {
                if (!(sent instanceof final JsonArray array)) {
                    throw new BadRequestResponse(key + " is a list");
                }
                final List<String> items = new ArrayList<>();
                final List<String> written = new ArrayList<>();
                for (final JsonElement item : array) {
                    final String value = scalar(key, item);
                    items.add(normal(key, entry.type(), value));
                    written.add(render(key, entry.type(), value, json, block ? Where.BLOCK_ITEM : Where.FLOW_ITEM));
                }
                final String replacement;
                if (!block) {
                    replacement = "[" + String.join(", ", written) + "]";
                } else if (written.isEmpty()) {
                    // `[]` is an empty list where a bare `key:` reads as null.
                    replacement = " []";
                } else {
                    replacement = written.stream()
                            .map(item -> ending + indent + "- " + item)
                            .collect(Collectors.joining());
                }
                return new Edit(start, end, replacement, String.join("\n", items));
            }
            final String value = scalar(key, sent);
            final String rendered = render(key, entry.type(), value, json, flowContext ? Where.FLOW_ITEM : Where.VALUE);
            return new Edit(start, end, (padded ? " " : "") + rendered, normal(key, entry.type(), value));
        }
    }

    private record Edit(int start, int end, String replacement, String value) {}

    private static String scalar(final String key, final JsonElement sent) {
        if (!sent.isJsonPrimitive()) {
            throw new BadRequestResponse(key + " is a single value");
        }
        return sent.getAsString();
    }

    private static String valueOf(final SettingsDocument.Entry entry) {
        return entry.kind() == SettingsDocument.Shape.LIST
                ? String.join("\n", Objects.requireNonNull(entry.items()))
                : Objects.requireNonNullElse(entry.value(), "");
    }

    private static @Nullable Read read(final String path, final String text) {
        final String lower = path.toLowerCase(Locale.ROOT);
        final boolean json = lower.endsWith(".json");
        if (!json && !lower.endsWith(".yml") && !lower.endsWith(".yaml")) {
            return null;
        }
        final LoaderOptions options = new LoaderOptions();
        options.setProcessComments(!json);
        final Node root;
        try {
            root = new Yaml(options).compose(new StringReader(text));
        } catch (final RuntimeException unreadable) {
            return null;
        }
        if (!(root instanceof final MappingNode mapping)) {
            return null;
        }
        final Reader reader = new Reader(text);
        try {
            reader.mapping(mapping, "", false);
        } catch (final Unsupported unsupported) {
            return null;
        }
        return new Read(List.copyOf(reader.entries), reader.slots, json);
    }

    /** A value whose characters a save cannot replace alone, which sends the whole file to the text editor. */
    private static final class Unsupported extends RuntimeException {
        Unsupported() {
            super(null, null, false, false);
        }
    }

    private static final class Reader {

        private final String text;
        private final int[] offsets;
        private final List<SettingsDocument.Entry> entries = new ArrayList<>();
        private final Map<String, Slot> slots = new LinkedHashMap<>();
        private final Set<String> paths = new HashSet<>();

        Reader(final String text) {
            this.text = text;
            // A mark counts code points; a splice counts chars.
            offsets = new int[text.codePointCount(0, text.length()) + 1];
            int at = 0;
            for (int point = 0; point < offsets.length - 1; point++) {
                offsets[point] = at;
                at += Character.charCount(text.codePointAt(at));
            }
            offsets[offsets.length - 1] = text.length();
        }

        private int offset(final Mark mark) {
            return offsets[mark.getIndex()];
        }

        void mapping(final MappingNode mapping, final String prefix, final boolean flow) {
            if (mapping.isMerged() || mapping.getAnchor() != null) {
                throw new Unsupported();
            }
            final boolean inFlow = flow || mapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW;
            for (final NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof final ScalarNode keyNode)) {
                    throw new Unsupported();
                }
                final String key = keyNode.getValue();
                if (key.isEmpty() || key.contains(".") || key.contains("[") || key.equals("<<")) {
                    throw new Unsupported();
                }
                final String path = prefix.isEmpty() ? key : prefix + "." + key;
                if (!paths.add(path)) {
                    throw new Unsupported();
                }
                final String explanation = explanationOf(keyNode);
                final Node value = tuple.getValueNode();
                if (value.getAnchor() != null) {
                    throw new Unsupported();
                }
                final int colon = text.indexOf(':', offset(keyNode.getEndMark()));
                switch (value) {
                    case final MappingNode inner -> {
                        entries.add(entry(
                                path, key, explanation, SettingsDocument.Shape.MAP, SettingType.STRING, null, null));
                        mapping(inner, path, inFlow);
                    }
                    case final SequenceNode sequence -> sequence(sequence, path, key, explanation, colon, inFlow);
                    case final ScalarNode scalar -> scalar(scalar, path, key, explanation, colon, inFlow);
                    default -> throw new Unsupported();
                }
            }
        }

        private void scalar(
                final ScalarNode scalar,
                final String path,
                final String key,
                final String explanation,
                final int colon,
                final boolean flow) {
            final int start = offset(scalar.getStartMark());
            final int end = offset(scalar.getEndMark());
            final boolean empty =
                    scalar.getValue().isEmpty() && scalar.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN;
            if (!empty && !oneLine(scalar)) {
                throw new Unsupported();
            }
            final SettingType type = typeOf(scalar);
            final String value = Tag.NULL.equals(scalar.getTag()) ? "" : shown(type, scalar.getValue());
            final SettingsDocument.Entry entry =
                    entry(path, key, explanation, SettingsDocument.Shape.SCALAR, type, value, null);
            entries.add(entry);
            // `key:` with nothing after it: the value goes right after the colon, one space apart.
            slots.put(
                    path,
                    empty
                            ? new Slot(entry, colon + 1, colon + 1, false, flow, true, "", "")
                            : new Slot(entry, start, end, false, flow, false, "", ""));
        }

        private void sequence(
                final SequenceNode sequence,
                final String path,
                final String key,
                final String explanation,
                final int colon,
                final boolean flow) {
            final List<String> items = new ArrayList<>();
            SettingType type = null;
            final StringBuilder between = new StringBuilder();
            final boolean block = sequence.getFlowStyle() != DumperOptions.FlowStyle.FLOW;
            final int start = block ? colon + 1 : offset(sequence.getStartMark());
            int at = start;
            for (final Node item : sequence.getValue()) {
                if (!(item instanceof final ScalarNode scalar)
                        || scalar.getValue().isEmpty()
                        || !oneLine(scalar)
                        || scalar.getAnchor() != null) {
                    throw new Unsupported();
                }
                final SettingType itemType = typeOf(scalar);
                if (type != null && type != itemType) {
                    throw new Unsupported();
                }
                type = itemType;
                items.add(shown(itemType, scalar.getValue()));
                between.append(text, at, offset(scalar.getStartMark()));
                at = offset(scalar.getEndMark());
            }
            final int end = block ? at : offset(sequence.getEndMark());
            between.append(text, at, end);
            // A comment between the items would be lost when the list is written anew.
            if (!between.chars().allMatch(c -> " \t\r\n-,[]".indexOf(c) >= 0)) {
                throw new Unsupported();
            }
            String indent = "";
            if (block) {
                if (items.isEmpty()) {
                    throw new Unsupported();
                }
                final int dash = text.lastIndexOf(
                        '-', offset(sequence.getValue().getFirst().getStartMark()));
                indent = text.substring(text.lastIndexOf('\n', dash) + 1, dash);
                if (!indent.isBlank()) {
                    throw new Unsupported();
                }
            }
            final SettingsDocument.Entry entry = entry(
                    path,
                    key,
                    explanation,
                    SettingsDocument.Shape.LIST,
                    type == null ? SettingType.STRING : type,
                    null,
                    items);
            entries.add(entry);
            final String ending = text.substring(start, end).contains("\r\n") ? "\r\n" : "\n";
            slots.put(path, new Slot(entry, start, end, block, flow, false, indent, ending));
        }

        private static boolean oneLine(final ScalarNode scalar) {
            final DumperOptions.ScalarStyle style = scalar.getScalarStyle();
            return style != DumperOptions.ScalarStyle.LITERAL
                    && style != DumperOptions.ScalarStyle.FOLDED
                    && scalar.getStartMark().getLine() == scalar.getEndMark().getLine();
        }

        private static String explanationOf(final ScalarNode keyNode) {
            final List<CommentLine> comments = keyNode.getBlockComments();
            if (comments == null) {
                return "";
            }
            // A blank line parts the file's own header from the comment on its first key.
            int from = 0;
            for (int line = 0; line < comments.size(); line++) {
                if (comments.get(line).getCommentType() == CommentType.BLANK_LINE) {
                    from = line + 1;
                }
            }
            return comments.subList(from, comments.size()).stream()
                    .map(CommentLine::getValue)
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.joining("\n"));
        }
    }

    private static SettingsDocument.Entry entry(
            final String path,
            final String key,
            final String explanation,
            final SettingsDocument.Shape kind,
            final SettingType type,
            final @Nullable String value,
            final @Nullable List<String> items) {
        return new SettingsDocument.Entry(
                path,
                key,
                key,
                explanation,
                true,
                kind,
                type,
                kind != SettingsDocument.Shape.MAP,
                false,
                false,
                value != null ? !value.isEmpty() : items != null && !items.isEmpty(),
                value,
                items,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** The type of a scalar from the tag the resolver gave it; a quoted scalar is always a string. */
    private static SettingType typeOf(final ScalarNode node) {
        final Tag tag = node.getTag();
        if (Tag.INT.equals(tag)) {
            return SettingType.INTEGER;
        }
        if (Tag.FLOAT.equals(tag)) {
            return SettingType.DECIMAL;
        }
        if (Tag.BOOL.equals(tag)) {
            return SettingType.BOOLEAN;
        }
        return SettingType.STRING;
    }

    /** A boolean shows as true or false, whichever of YAML's spellings the file uses. */
    private static String shown(final SettingType type, final String value) {
        return type == SettingType.BOOLEAN ? String.valueOf(TRUE.contains(value.toLowerCase(Locale.ROOT))) : value;
    }

    /** The value as the form shows it once written. */
    private static String normal(final String key, final SettingType type, final String value) {
        if (type == SettingType.STRING) {
            return value;
        }
        final String trimmed = value.strip().toLowerCase(Locale.ROOT);
        final boolean valid = switch (type) {
            case INTEGER -> INTEGER.matcher(trimmed).matches();
            case DECIMAL -> DECIMAL.matcher(trimmed).matches();
            case BOOLEAN -> trimmed.equals("true") || trimmed.equals("false");
            case STRING -> true;
        };
        if (!valid) {
            throw new RequestRefused(400, ANSWER.notOfType(key, StewardTexts.Expected.valueOf(type.name()), value));
        }
        return trimmed;
    }

    private static String render(
            final String key, final SettingType type, final String value, final boolean json, final Where where) {
        if (type != SettingType.STRING) {
            return normal(key, type, value);
        }
        return json ? Json.gson().toJson(value) : quote(key, value, where);
    }

    /** Quotes {@code value} only when leaving it bare would change it: plain first, then single, then double quotes. */
    private static String quote(final String key, final String value, final Where where) {
        // A tab or newline in a plain scalar reads back correctly but is a trap, so it is never written that way.
        final boolean control = value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
        if (!control && readsBackAs(value, value, where)) {
            return value;
        }
        final String single = "'" + value.replace("'", "''") + "'";
        if (!control && readsBackAs(single, value, where)) {
            return single;
        }
        final String doubled = Json.gson().toJson(value);
        if (readsBackAs(doubled, value, where)) {
            return doubled;
        }
        throw new BadRequestResponse(key + " cannot be written to a YAML file as sent");
    }

    /** Whether {@code rendered}, put where it is going, reads back as exactly {@code expected}. */
    private static boolean readsBackAs(final String rendered, final String expected, final Where where) {
        final Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(where.document(rendered));
        } catch (final RuntimeException notYaml) {
            return false;
        }
        if (!(root instanceof final Map<?, ?> map) || map.size() != 1) {
            return false;
        }
        Object value = map.get("k");
        if (where != Where.VALUE) {
            // A list entry has to stay one entry: `a,b` is one string after a colon but two inside brackets.
            if (!(value instanceof final List<?> list) || list.size() != 1) {
                return false;
            }
            value = list.getFirst();
        }
        return expected.equals(value);
    }

    /** Where a rendered scalar is going, which decides what reads back correctly. */
    private enum Where {
        VALUE,
        BLOCK_ITEM,
        FLOW_ITEM;

        String document(final String rendered) {
            return switch (this) {
                case VALUE -> "k: " + rendered;
                case BLOCK_ITEM -> "k:\n- " + rendered;
                case FLOW_ITEM -> "k: [" + rendered + "]";
            };
        }
    }
}

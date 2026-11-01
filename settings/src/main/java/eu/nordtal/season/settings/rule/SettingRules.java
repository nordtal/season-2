package eu.nordtal.season.settings.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.settings.SettingRefused;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Checks a group's values against the rules its schema declares, one way for the server and for Steward. */
public final class SettingRules {

    private SettingRules() {}

    /**
     * A setting that broke a declared rule.
     *
     * @param setting the dotted path, which for an entry of a list is the list and the field
     * @param entries the index of the entry in each list on the way to the setting, outermost first
     * @param value the value the rule names: a limit, the sibling or the value held twice
     */
    public record Breach(
            String setting,
            List<Integer> entries,
            SettingRule rule,
            @Nullable String value) {

        public Breach {
            Objects.requireNonNull(setting, "setting");
            entries = List.copyOf(entries);
            Objects.requireNonNull(rule, "rule");
        }
    }

    /** A breach with the English sentence for the log, which names the entry by its index. */
    private record Found(Breach breach, String sentence) {}

    /**
     * Returns every breach of the rules {@code schema} declares, in the order of the schema.
     *
     * @param skipped the paths whose rules are not checked, such as the ones the environment holds
     */
    public static List<Breach> breaches(final JsonObject schema, final JsonObject values, final Set<String> skipped) {
        final List<Found> found = new ArrayList<>();
        walk(schema, values, new Place("", "", List.of()), skipped, found);
        return found.stream().map(Found::breach).toList();
    }

    /** Refuses the first breach of the rules {@code schema} declares. */
    public static void check(final JsonObject schema, final JsonObject values) {
        final List<Found> found = new ArrayList<>();
        walk(schema, values, new Place("", "", List.of()), Set.of(), found);
        if (!found.isEmpty()) {
            final Breach first = found.getFirst().breach();
            throw new SettingRefused(
                    first.setting(),
                    first.rule(),
                    first.value(),
                    found.getFirst().sentence());
        }
    }

    /** Where a node sits: its path without and with indexes, and the indexes. */
    private record Place(String setting, String shown, List<Integer> entries) {

        Place child(final String key) {
            return new Place(join(setting, key), join(shown, key), entries);
        }

        Place entry(final int index) {
            final List<Integer> more = new ArrayList<>(entries);
            more.add(index);
            return new Place(setting, shown + "[" + index + "]", more);
        }

        Place field(final String key) {
            return key.isEmpty() ? this : new Place(join(setting, key), join(shown, key), entries);
        }

        private static String join(final String prefix, final String key) {
            return prefix.isEmpty() ? key : prefix + "." + key;
        }
    }

    private static void walk(
            final JsonObject node,
            final @Nullable JsonObject values,
            final Place place,
            final Set<String> skipped,
            final List<Found> into) {
        if (!(node.get("children") instanceof final JsonObject children)) {
            return;
        }
        for (final String key : keysOf(node, children)) {
            if (!(children.get(key) instanceof final JsonObject child)) {
                continue;
            }
            final Place at = place.child(key);
            if (skipped.contains(at.setting()) || !applies(child, values)) {
                continue;
            }
            final @Nullable JsonElement value = values == null ? null : values.get(key);
            for (final DeclaredRule rule : DeclaredRule.on(child)) {
                apply(rule, value, values, at, into);
            }
            if (value instanceof final JsonObject nested && "MAP".equals(text(child.get("kind")))) {
                walk(child, nested, at, skipped, into);
            } else if (value instanceof final JsonArray list && "LIST".equals(text(child.get("kind")))) {
                for (int index = 0; index < list.size(); index++) {
                    if (list.get(index) instanceof final JsonObject entry) {
                        walk(child, entry, at.entry(index), skipped, into);
                    }
                }
            }
        }
    }

    private static List<String> keysOf(final JsonObject node, final JsonObject children) {
        if (node.get("order") instanceof final JsonArray order) {
            final List<String> keys = new ArrayList<>();
            order.forEach(key -> keys.add(key.getAsString()));
            return keys;
        }
        return List.copyOf(children.keySet());
    }

    /** Whether the sibling an {@code appliesWhen} names holds one of its values, as Steward's form decides it. */
    private static boolean applies(final JsonObject node, final @Nullable JsonObject siblings) {
        if (!(node.get("appliesWhen") instanceof final JsonObject condition)) {
            return true;
        }
        final String held =
                text(siblings == null ? null : siblings.get(condition.get("key").getAsString()));
        for (final JsonElement value : condition.getAsJsonArray("values")) {
            if (value.getAsString().equals(held)) {
                return true;
            }
        }
        return false;
    }

    private static void apply(
            final DeclaredRule declared,
            final @Nullable JsonElement value,
            final @Nullable JsonObject siblings,
            final Place at,
            final List<Found> into) {
        switch (declared.rule()) {
            case REQUIRED -> {
                if (isEmpty(value)) {
                    into.add(found(at, declared.rule(), null, at.shown() + " must not be empty"));
                }
            }
            case ENTRIES, MOST_ENTRIES -> entries(declared, value, at, into);
            case ABOVE -> above(declared, value, siblings, at, into);
            case UNIQUE -> unique(declared, value, at, into);
            case ASCENDING -> ascending(declared, value, at, into);
            default -> {
                if (value instanceof final JsonArray list) {
                    for (int index = 0; index < list.size(); index++) {
                        scalar(declared, list.get(index), at.entry(index), into);
                    }
                } else {
                    scalar(declared, value, at, into);
                }
            }
        }
    }

    /** Applies a rule on one number or one text: a sign, a bound or a length. */
    private static void scalar(
            final DeclaredRule declared, final @Nullable JsonElement value, final Place at, final List<Found> into) {
        final SettingRule rule = declared.rule();
        final long limit = declared.limit() == null ? 0 : declared.limit();
        if (rule == SettingRule.LONGEST) {
            final @Nullable String held =
                    value instanceof final JsonPrimitive text && text.isString() ? text.getAsString() : null;
            if (held != null && held.codePointCount(0, held.length()) > limit) {
                into.add(found(
                        at, rule, String.valueOf(limit), at.shown() + " is longer than " + limit + " characters"));
            }
            return;
        }
        final @Nullable Double number = number(value);
        if (number == null) {
            return;
        }
        final String was = ", was " + text(value);
        switch (rule) {
            case POSITIVE -> {
                if (number <= 0) {
                    into.add(found(at, rule, null, at.shown() + " must be greater than zero" + was));
                }
            }
            case NOT_NEGATIVE -> {
                if (number < 0) {
                    into.add(found(at, rule, null, at.shown() + " must not be negative" + was));
                }
            }
            case AT_LEAST -> {
                if (number < limit) {
                    into.add(found(at, rule, String.valueOf(limit), at.shown() + " must be at least " + limit + was));
                }
            }
            case AT_MOST -> {
                if (number > limit) {
                    into.add(found(at, rule, String.valueOf(limit), at.shown() + " must be at most " + limit + was));
                }
            }
            default -> throw new IllegalStateException(rule + " is not a rule on one value");
        }
    }

    private static void entries(
            final DeclaredRule declared, final @Nullable JsonElement value, final Place at, final List<Found> into) {
        final int count = value instanceof final JsonArray list ? list.size() : 0;
        final long limit = Objects.requireNonNull(declared.limit(), "limit");
        if (declared.rule() == SettingRule.ENTRIES && count != limit) {
            into.add(found(
                    at,
                    declared.rule(),
                    String.valueOf(limit),
                    at.shown() + " must have exactly " + limit + " entries, had " + count));
        } else if (declared.rule() == SettingRule.MOST_ENTRIES && count > limit) {
            into.add(found(
                    at,
                    declared.rule(),
                    String.valueOf(limit),
                    at.shown() + " has " + count + " entries, more than " + limit));
        }
    }

    private static void above(
            final DeclaredRule declared,
            final @Nullable JsonElement value,
            final @Nullable JsonObject siblings,
            final Place at,
            final List<Found> into) {
        final String sibling = Objects.requireNonNull(declared.field(), "field");
        final @Nullable Double number = number(value);
        final @Nullable Double other = number(siblings == null ? null : siblings.get(sibling));
        if (number != null && other != null && number <= other) {
            into.add(found(at, declared.rule(), sibling, at.shown() + " must be greater than " + sibling));
        }
    }

    private static void unique(
            final DeclaredRule declared, final @Nullable JsonElement value, final Place at, final List<Found> into) {
        if (!(value instanceof final JsonArray list)) {
            return;
        }
        final String field = Objects.requireNonNullElse(declared.field(), "");
        final Set<JsonElement> seen = new HashSet<>();
        for (int index = 0; index < list.size(); index++) {
            final @Nullable JsonElement held = fieldOf(list.get(index), field);
            if (held != null && !held.isJsonNull() && !seen.add(held)) {
                final Place entry = at.entry(index).field(field);
                into.add(found(entry, declared.rule(), text(held), entry.shown() + " holds " + text(held) + " twice"));
                return;
            }
        }
    }

    private static void ascending(
            final DeclaredRule declared, final @Nullable JsonElement value, final Place at, final List<Found> into) {
        if (!(value instanceof final JsonArray list)) {
            return;
        }
        final String field = Objects.requireNonNullElse(declared.field(), "");
        @Nullable Double previous = null;
        for (int index = 0; index < list.size(); index++) {
            final @Nullable Double held = number(fieldOf(list.get(index), field));
            if (held != null && previous != null && held < previous) {
                final Place entry = at.entry(index).field(field);
                into.add(found(entry, declared.rule(), null, entry.shown() + " must be in ascending order"));
                return;
            }
            previous = held == null ? previous : held;
        }
    }

    private static @Nullable JsonElement fieldOf(final JsonElement entry, final String field) {
        if (field.isEmpty()) {
            return entry;
        }
        return entry instanceof final JsonObject object ? object.get(field) : null;
    }

    private static Found found(
            final Place at, final SettingRule rule, final @Nullable String value, final String sentence) {
        return new Found(new Breach(at.setting(), at.entries(), rule, value), sentence);
    }

    private static boolean isEmpty(final @Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return true;
        }
        if (value instanceof final JsonArray list) {
            return list.isEmpty();
        }
        return value instanceof final JsonPrimitive text
                && text.isString()
                && text.getAsString().isBlank();
    }

    /** Returns the number a value holds, or {@code null} for anything else, which a rule on a number skips. */
    private static @Nullable Double number(final @Nullable JsonElement value) {
        if (!(value instanceof final JsonPrimitive primitive) || primitive.isBoolean()) {
            return null;
        }
        try {
            return Double.parseDouble(primitive.getAsString().strip());
        } catch (final NumberFormatException notANumber) {
            return null;
        }
    }

    private static String text(final @Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value instanceof final JsonPrimitive primitive ? primitive.getAsString() : value.toString();
    }
}

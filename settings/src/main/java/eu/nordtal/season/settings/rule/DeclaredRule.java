package eu.nordtal.season.settings.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.database.setting.SettingRule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One rule a setting declares, as its schema node lists it.
 *
 * @param limit the number a bound, a count or a length names, else {@code null}
 * @param field the key a list rule compares in each entry, or the sibling {@link SettingRule#ABOVE} names
 */
public record DeclaredRule(
        SettingRule rule, @Nullable Long limit, @Nullable String field) {

    public DeclaredRule {
        Objects.requireNonNull(rule, "rule");
    }

    /** Returns the rules the annotations on {@code getter} declare, in the order of {@link SettingRule}. */
    public static List<DeclaredRule> of(final Method getter) {
        final List<DeclaredRule> rules = new ArrayList<>();
        if (getter.isAnnotationPresent(Required.class)) {
            rules.add(new DeclaredRule(SettingRule.REQUIRED, null, null));
        }
        if (getter.isAnnotationPresent(Positive.class)) {
            rules.add(new DeclaredRule(SettingRule.POSITIVE, null, null));
        }
        if (getter.isAnnotationPresent(NotNegative.class)) {
            rules.add(new DeclaredRule(SettingRule.NOT_NEGATIVE, null, null));
        }
        final Between between = getter.getAnnotation(Between.class);
        if (between != null && between.least() != Long.MIN_VALUE) {
            rules.add(new DeclaredRule(SettingRule.AT_LEAST, between.least(), null));
        }
        if (between != null && between.most() != Long.MAX_VALUE) {
            rules.add(new DeclaredRule(SettingRule.AT_MOST, between.most(), null));
        }
        final Above above = getter.getAnnotation(Above.class);
        if (above != null) {
            rules.add(new DeclaredRule(SettingRule.ABOVE, null, above.value()));
        }
        final Entries entries = getter.getAnnotation(Entries.class);
        if (entries != null && entries.exactly() >= 0) {
            rules.add(new DeclaredRule(SettingRule.ENTRIES, (long) entries.exactly(), null));
        }
        if (entries != null && entries.most() >= 0) {
            rules.add(new DeclaredRule(SettingRule.MOST_ENTRIES, (long) entries.most(), null));
        }
        final Longest longest = getter.getAnnotation(Longest.class);
        if (longest != null) {
            rules.add(new DeclaredRule(SettingRule.LONGEST, (long) longest.value(), null));
        }
        final Unique unique = getter.getAnnotation(Unique.class);
        if (unique != null) {
            rules.add(new DeclaredRule(SettingRule.UNIQUE, null, unique.value()));
        }
        final Ascending ascending = getter.getAnnotation(Ascending.class);
        if (ascending != null) {
            rules.add(new DeclaredRule(SettingRule.ASCENDING, null, ascending.value()));
        }
        rules.sort(Comparator.comparing(DeclaredRule::rule));
        return List.copyOf(rules);
    }

    /** Returns {@code rules} as the array a schema node holds. */
    public static JsonArray json(final List<DeclaredRule> rules) {
        final JsonArray array = new JsonArray();
        for (final DeclaredRule declared : rules) {
            final JsonObject one = new JsonObject();
            one.addProperty("rule", declared.rule().name());
            if (declared.limit() != null) {
                one.addProperty("limit", declared.limit());
            }
            if (declared.field() != null) {
                one.addProperty("field", declared.field());
            }
            array.add(one);
        }
        return array;
    }

    /** Returns the rules a schema node lists, or none. */
    public static List<DeclaredRule> on(final JsonObject node) {
        if (!(node.get("rules") instanceof final JsonArray array)) {
            return List.of();
        }
        final List<DeclaredRule> rules = new ArrayList<>();
        for (final JsonElement element : array) {
            final JsonObject one = element.getAsJsonObject();
            rules.add(new DeclaredRule(
                    SettingRule.valueOf(one.get("rule").getAsString()),
                    one.has("limit") ? one.get("limit").getAsLong() : null,
                    one.has("field") ? one.get("field").getAsString() : null));
        }
        return rules;
    }
}

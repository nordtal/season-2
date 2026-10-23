package eu.nordtal.season.database.journal;

import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the free-text query of the journal: each word becomes one {@link Term}, and the terms combine with AND.
 * A word is read as the first kind of {@link Term.Kind} it fits, and as a name when it fits none. A word read as an
 * action still matches the names it starts, since the names are not known here.
 */
public final class JournalSearch {

    private static final Pattern PAYMENT = Pattern.compile("NT-[0-9A-F]{6}");
    private static final Pattern DISCORD = Pattern.compile("[0-9]{17,20}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DAYS = Pattern.compile("([0-9]{1,5})d");

    private JournalSearch() {}

    /**
     * Returns the terms of {@code query}, one per word, in the order typed.
     *
     * @param services the services the journal knows
     * @param labels the label of each action in the viewer's language
     */
    public static List<Term> parse(
            final @Nullable String query, final Set<String> services, final Map<JournalAction, String> labels) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.trim().split("\\s+"))
                .map(word -> term(word, services, labels))
                .toList();
    }

    private static Term term(final String word, final Set<String> services, final Map<JournalAction, String> labels) {
        final String upper = word.toUpperCase(Locale.ROOT);
        final String lower = word.toLowerCase(Locale.ROOT);
        final String constant = upper.replace('-', '_');
        if (PAYMENT.matcher(upper).matches()) {
            return new Term(Term.Kind.PAYMENT, word, List.of(upper));
        }
        if (DISCORD.matcher(word).matches()) {
            return new Term(Term.Kind.DISCORD, word, List.of(word));
        }
        if (UUID_TEXT.matcher(lower).matches()) {
            return new Term(Term.Kind.UUID, word, List.of(UUID.fromString(lower).toString()));
        }
        final var days = DAYS.matcher(lower);
        if (days.matches()) {
            return new Term(Term.Kind.DAYS, word, List.of(String.valueOf(Integer.parseInt(days.group(1)))));
        }
        if (services.contains(lower)) {
            return new Term(Term.Kind.SERVICE, word, List.of(lower));
        }
        final String named = named(constant);
        if (named != null) {
            return new Term(kindOf(constant), word, List.of(named));
        }
        final List<String> actions = labels.entrySet().stream()
                .filter(label -> label.getValue().toLowerCase(Locale.ROOT).contains(lower))
                .map(label -> label.getKey().name())
                .sorted()
                .toList();
        return actions.isEmpty()
                ? new Term(Term.Kind.NAME, word, List.of(word))
                : new Term(Term.Kind.ACTION, word, actions);
    }

    /** Returns the constant a level, run kind, run status or gauge word names, null for none. */
    private static @Nullable String named(final String constant) {
        if (has(Alert.Level.values(), constant)
                || has(UpdateKind.values(), constant)
                || has(UpdateStatus.values(), constant)) {
            return constant;
        }
        return gauge(constant);
    }

    private static Term.Kind kindOf(final String constant) {
        if (has(Alert.Level.values(), constant)) {
            return Term.Kind.LEVEL;
        }
        if (has(UpdateKind.values(), constant)) {
            return Term.Kind.RUN_KIND;
        }
        return has(UpdateStatus.values(), constant) ? Term.Kind.RUN_STATUS : Term.Kind.GAUGE;
    }

    /** Returns the gauge whose name is {@code constant} or whose name alone starts with it as a word. */
    private static @Nullable String gauge(final String constant) {
        final List<String> named = Arrays.stream(Measure.Gauge.values())
                .map(Enum::name)
                .filter(name -> name.equals(constant) || name.startsWith(constant + "_"))
                .toList();
        return named.size() == 1 ? named.getFirst() : null;
    }

    private static boolean has(final Enum<?>[] values, final String constant) {
        return Arrays.stream(values).anyMatch(value -> value.name().equals(constant));
    }

    /**
     * One recognised word of the query.
     *
     * @param word the word as typed
     * @param values what it matches: one value, or every action whose label contains it (an action word also matches
     *     the names it starts)
     */
    public record Term(Kind kind, String word, List<String> values) {

        public Term {
            Objects.requireNonNull(kind, "kind");
            values = List.copyOf(values);
        }

        /** What a word was read as, in the order a word is tried. */
        public enum Kind {
            PAYMENT,
            DISCORD,
            UUID,
            DAYS,
            SERVICE,
            LEVEL,
            RUN_KIND,
            RUN_STATUS,
            GAUGE,
            ACTION,
            NAME
        }
    }
}

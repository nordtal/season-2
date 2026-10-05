package eu.nordtal.season.messages.value;

import eu.nordtal.season.messages.MessageRef;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Every kind's value as plain text, once; the one formatter of durations, dates and money.
 * It serves the console, a log and every word of a Minecraft line that is not a name, an item or a glyph.
 */
public final class ValueText {

    /** Where a game's line places an argument: {@code %s} in turn, or {@code %2$s} by position. */
    private static final Pattern ARGUMENT = Pattern.compile("%(?:(\\d+)\\$)?s");

    private ValueText() {}

    /**
     * Returns a value as a reader in {@code locale} and {@code zone} reads it.
     *
     * @param value the value, or {@code null} for the kind's replacement word
     * @param style one of the kind's {@link Kind#styles()}, or {@code null} for its default
     */
    public static String of(
            final Kind kind,
            final @Nullable Object value,
            final @Nullable String style,
            final Locale locale,
            final ZoneId zone,
            final Words words) {
        if (value == null) {
            return words.missing(kind);
        }
        return switch (kind) {
            case TEXT -> String.valueOf(value);
            case NUMBER -> number((Number) value, "plain".equals(style), locale);
            case DURATION -> duration((Duration) value, style, words);
            case INSTANT -> instant((Instant) value, style, locale, zone);
            case MONEY -> money((Money) value, locale);
            case LIST -> list((List<?>) value, "or".equals(style), locale, zone, words);
            case DISPLAY_NAME -> ((DisplayName) value).name();
            case MENTION -> "@" + ((Mention) value).name();
            case ITEM -> content((GameContent) value, locale, zone, words);
            case GLYPH -> "";
            case CHOICE ->
                value instanceof final Boolean yes
                        ? words.word(yes ? "choice.yes" : "choice.no", Map.of())
                        : Kind.choiceOf(value);
            case MESSAGE -> words.message((MessageRef) value);
        };
    }

    private static String number(final Number number, final boolean plain, final Locale locale) {
        final BigDecimal decimal = number instanceof final BigDecimal exact
                ? exact
                : number instanceof Double || number instanceof Float
                        ? BigDecimal.valueOf(number.doubleValue())
                        : BigDecimal.valueOf(number.longValue());
        if (plain) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        final NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMaximumFractionDigits(2);
        return format.format(decimal);
    }

    /** The two largest units that are not zero, so a countdown reads {@code 2 hours and 5 minutes}; or a style's. */
    private static String duration(final Duration duration, final @Nullable String style, final Words words) {
        final long total = Math.max(0, duration.toSeconds());
        if ("clock".equals(style)) {
            final long hours = total / 3600;
            final long minutes = total / 60 % 60;
            final long seconds = total % 60;
            return hours > 0
                    ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                    : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
        }
        final long[] amounts = {total / 86_400, total / 3600 % 24, total / 60 % 60, total % 60};
        final String[] units = {"days", "hours", "minutes", "seconds"};
        if ("minutes".equals(style)) {
            // Every unit down to the minute, so a span typed in days, hours and minutes reads back as it was typed.
            final List<String> parts = new ArrayList<>();
            for (int unit = 0; unit < units.length - 1; unit++) {
                if (amounts[unit] > 0 || (unit == units.length - 2 && parts.isEmpty())) {
                    parts.add(words.word("duration.short." + units[unit], Map.of("n", amounts[unit])));
                }
            }
            return String.join(" ", parts);
        }
        final String prefix = "short".equals(style) ? "duration.short." : "duration.";
        final List<String> parts = new ArrayList<>();
        for (int unit = 0; unit < units.length && parts.size() < 2; unit++) {
            final boolean started = !parts.isEmpty();
            if (amounts[unit] > 0 || (unit == units.length - 1 && !started)) {
                parts.add(words.word(prefix + units[unit], Map.of("n", amounts[unit])));
            } else if (started) {
                break;
            }
        }
        if ("short".equals(style)) {
            return String.join(" ", parts);
        }
        return parts.size() == 1
                ? parts.getFirst()
                : words.word("list.and", Map.of("rest", parts.getFirst(), "last", parts.getLast()));
    }

    private static String instant(
            final Instant instant, final @Nullable String style, final Locale locale, final ZoneId zone) {
        // relative is Discord's own countdown; as text it is the moment itself.
        final DateTimeFormatter format = switch (style == null ? "datetime" : style) {
            case "date" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
            case "time" -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT);
            default -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);
        };
        return format.withLocale(locale).format(instant.atZone(zone));
    }

    private static String money(final Money money, final Locale locale) {
        final NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(money.currency());
        return format.format(BigDecimal.valueOf(money.minor(), money.currency().getDefaultFractionDigits()));
    }

    /** The English text with each argument, in its own kind, where the game's line places it. */
    private static String content(
            final GameContent content, final Locale locale, final ZoneId zone, final Words words) {
        if (content.args().isEmpty()) {
            return content.english();
        }
        final List<String> shown = new ArrayList<>(content.args().size());
        for (final Object arg : content.args()) {
            final Kind kind = Kind.ofValue(arg).orElse(Kind.TEXT);
            shown.add(kind == Kind.LIST ? String.valueOf(arg) : of(kind, arg, null, locale, zone, words));
        }
        final Matcher slot = ARGUMENT.matcher(content.english());
        final StringBuilder out = new StringBuilder();
        int next = 0;
        while (slot.find()) {
            final int index = slot.group(1) == null ? next++ : Integer.parseInt(slot.group(1)) - 1;
            slot.appendReplacement(out, Matcher.quoteReplacement(index < shown.size() ? shown.get(index) : ""));
        }
        slot.appendTail(out);
        return out.toString();
    }

    private static String list(
            final List<?> items, final boolean or, final Locale locale, final ZoneId zone, final Words words) {
        final List<String> shown = new ArrayList<>(items.size());
        for (final Object item : items) {
            final Kind kind = Kind.ofValue(item).orElse(Kind.TEXT);
            shown.add(kind == Kind.LIST ? String.valueOf(item) : of(kind, item, null, locale, zone, words));
        }
        return join(shown, or, words);
    }

    /**
     * Joins items already shown as text with the reader's words: {@code a, b and c}, or {@code a, b or c}.
     * Every target that shows a list as text joins it here.
     */
    public static String join(final List<String> shown, final boolean or, final Words words) {
        if (shown.isEmpty()) {
            return words.missing(Kind.LIST);
        }
        if (shown.size() == 1) {
            return shown.getFirst();
        }
        final String rest = String.join(", ", shown.subList(0, shown.size() - 1));
        return words.word(or ? "list.or" : "list.and", Map.of("rest", rest, "last", shown.getLast()));
    }
}

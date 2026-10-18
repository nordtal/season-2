package eu.nordtal.season.messages.document;

import eu.nordtal.season.messages.value.Kind;
import eu.nordtal.season.messages.value.Money;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The {@code when} of a part, a line or a row item: {@code value}, {@code !value} or {@code value=constant}.
 * A bare value holds when it is true, a number or amount above zero, or a text or list that is not empty.
 */
public record Condition(
        String value, boolean negated, @Nullable String constant) {

    private static final Pattern FORM = Pattern.compile("(!?)([a-z][A-Za-z0-9_.]*)(?:=([a-z0-9][a-z0-9-]*))?");

    /** Returns the condition written as {@code when}, or {@code null} for a malformed one. */
    public static @Nullable Condition parse(final String when) {
        final Matcher matcher = FORM.matcher(when);
        if (!matcher.matches() || (!matcher.group(1).isEmpty() && matcher.group(3) != null)) {
            return null;
        }
        return new Condition(matcher.group(2), !matcher.group(1).isEmpty(), matcher.group(3));
    }

    /** Returns whether {@code when} holds. No condition always holds; a malformed one never does. */
    public static boolean holds(final @Nullable String when, final Map<String, ?> values) {
        if (when == null) {
            return true;
        }
        final Condition condition = parse(when);
        return condition != null && condition.holds(values);
    }

    public boolean holds(final Map<String, ?> values) {
        final Object value = values.get(this.value);
        if (constant != null) {
            return value != null && constant.equals(Kind.choiceOf(value));
        }
        return negated != truthy(value);
    }

    private static boolean truthy(final @Nullable Object value) {
        return switch (value) {
            case null -> false;
            case final Boolean flag -> flag;
            case final BigDecimal decimal -> decimal.signum() > 0;
            case final Number number -> number.doubleValue() > 0;
            case final Money money -> money.minor() > 0;
            case final CharSequence text -> !text.isEmpty();
            case final Collection<?> items -> !items.isEmpty();
            default -> true;
        };
    }
}

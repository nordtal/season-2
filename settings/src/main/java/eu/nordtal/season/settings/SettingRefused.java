package eu.nordtal.season.settings;

import eu.nordtal.season.database.setting.SettingRule;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Refuses a group of settings by naming the setting at fault and the rule it broke.
 * Its message is the English sentence for the log. Steward words the setting and the rule itself.
 */
public final class SettingRefused extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String setting;
    private final SettingRule rule;
    private final @Nullable String value;

    /**
     * @param setting the dotted path of the setting, or the group's name where no one setting is at fault
     * @param value the value the rule names: a limit, another setting or the value at fault
     */
    public SettingRefused(
            final String setting, final SettingRule rule, final @Nullable Object value, final String sentence) {
        super(sentence);
        this.setting = Objects.requireNonNull(setting, "setting");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.value = value == null ? null : String.valueOf(value);
    }

    /** Refuses {@code setting} with a rule that names no value. */
    public SettingRefused(final String setting, final SettingRule rule, final String sentence) {
        this(setting, rule, null, sentence);
    }

    public String setting() {
        return setting;
    }

    public SettingRule rule() {
        return rule;
    }

    public @Nullable String value() {
        return value;
    }

    /** Returns the refusal among {@code failure} and its causes, or the rule {@code VALID} for the whole group. */
    public static SettingRefused of(final Throwable failure, final String group) {
        for (@Nullable Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof final SettingRefused refused) {
                return refused;
            }
        }
        return new SettingRefused(group, SettingRule.VALID, String.valueOf(failure.getMessage()));
    }
}

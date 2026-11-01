package eu.nordtal.season.steward.settings;

import eu.nordtal.season.database.DatabaseMessages;
import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.settings.rule.SettingRules;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A save the declared rules or the server's trial refused, answered with the broken rule as data for the form. */
public final class SettingsRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient MessageRef why;
    private final transient SettingRules.@Nullable Breach breach;

    /**
     * Refuses a save.
     *
     * @param why the reason as a message of the database bundle
     * @param breach the broken rule, or {@code null} for a reason a rule does not name
     */
    public SettingsRefusal(final MessageRef why, final SettingRules.@Nullable Breach breach) {
        super(why.key(), null, false, false);
        this.why = Objects.requireNonNull(why, "why");
        this.breach = breach;
    }

    /** Refuses with the first breach of the declared rules, worded with the index of each entry. */
    static SettingsRefusal declared(final String service, final String shown, final SettingRules.Breach breach) {
        return new SettingsRefusal(
                DatabaseMessages.MESSAGES.server().settingsRefused(service, shown, breach.rule(), breach.value()),
                breach);
    }

    /** Refuses with the server's refusal, whose setting and rule are the breach when the server named them. */
    static SettingsRefusal ofServer(final MessageRef refusal) {
        final Object setting = refusal.args().get("setting");
        final Object rule = refusal.args().get("rule");
        if (setting == null || !(rule instanceof final SettingRule named)) {
            return new SettingsRefusal(refusal, null);
        }
        final Object value = refusal.args().get("value");
        return new SettingsRefusal(
                refusal,
                new SettingRules.Breach(
                        String.valueOf(setting), List.of(), named, value == null ? null : String.valueOf(value)));
    }

    public MessageRef why() {
        return why;
    }

    public SettingRules.@Nullable Breach breach() {
        return breach;
    }
}

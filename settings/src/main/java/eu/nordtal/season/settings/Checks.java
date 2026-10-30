package eu.nordtal.season.settings;

import eu.nordtal.season.database.setting.SettingRule;
import org.jspecify.annotations.Nullable;

/** The rules a group's check applies to one setting, each refusing with a {@link SettingRefused}. */
public final class Checks {

    private Checks() {}

    public static void requireText(final String key, final @Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new SettingRefused(key, SettingRule.REQUIRED, key + " must not be empty");
        }
    }

    public static void requirePositive(final String key, final long value) {
        if (value <= 0) {
            throw new SettingRefused(key, SettingRule.POSITIVE, key + " must be greater than zero, was " + value);
        }
    }

    public static void requirePositive(final String key, final double value) {
        if (value <= 0) {
            throw new SettingRefused(key, SettingRule.POSITIVE, key + " must be greater than zero, was " + value);
        }
    }

    public static void requireNotNegative(final String key, final long value) {
        if (value < 0) {
            throw new SettingRefused(key, SettingRule.NOT_NEGATIVE, key + " must not be negative, was " + value);
        }
    }

    /** Refuses {@code value} outside {@code least} to {@code most}, naming the bound it passed. */
    public static void requireBetween(final String key, final long value, final long least, final long most) {
        if (value < least) {
            throw new SettingRefused(
                    key, SettingRule.AT_LEAST, least, key + " must be at least " + least + ", was " + value);
        }
        if (value > most) {
            throw new SettingRefused(
                    key, SettingRule.AT_MOST, most, key + " must be at most " + most + ", was " + value);
        }
    }

    /** Runs the checks of one entry of a list, naming {@code entry} in the sentence of a refusal. */
    public static void inEntry(final String entry, final Runnable checks) {
        try {
            checks.run();
        } catch (final SettingRefused refused) {
            throw new SettingRefused(
                    refused.setting(), refused.rule(), refused.value(), "'" + entry + "': " + refused.getMessage());
        }
    }

    public static void requireSecret(final String key, final String variable, final @Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new SettingRefused(
                    key, SettingRule.REQUIRED, key + " is empty. Set " + variable + " in the environment.");
        }
    }
}

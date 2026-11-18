package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.discordbot.Ids;
import java.util.Locale;
import java.util.Optional;

/**
 * The state of a confirm dialog: the country and language it shows and whether the member chose the language.
 * The state lives in the ids of the dialog's components, so a dialog outlives a restart of the bot.
 *
 * @param chosen whether the member chose the language in the dialog, so a new country keeps it
 */
record Draft(String country, String language, boolean chosen) {

    /** The component of the dialog that a member used. */
    enum Field {
        COUNTRY,
        LANGUAGE,
        CONFIRM
    }

    /** A component id of a dialog and the state it carries. */
    record Used(Field field, Draft draft) {}

    private static final String SEPARATOR = ":";

    /** Returns the dialog that a country choice in the choose channel opens, with the country's language. */
    static Draft of(final Choices.Choice country) {
        return new Draft(country.value(), country.language(), false);
    }

    /** Returns the dialog with another country, and its language unless the member chose one. */
    Draft withCountry(final Choices.Choice next) {
        return new Draft(next.value(), chosen ? language : next.language(), chosen);
    }

    /** Returns the dialog with the language the member chose. */
    Draft withLanguage(final String tag) {
        return new Draft(country, tag, true);
    }

    String id(final Field field) {
        return Ids.ONBOARD_DRAFT
                + field.name().toLowerCase(Locale.ROOT)
                + SEPARATOR
                + country
                + SEPARATOR
                + language
                + SEPARATOR
                + (chosen ? "1" : "0");
    }

    /** Reads a component id of a dialog, or returns nothing for any other id. */
    static Optional<Used> parse(final String id) {
        if (!id.startsWith(Ids.ONBOARD_DRAFT)) {
            return Optional.empty();
        }
        final String[] parts = id.substring(Ids.ONBOARD_DRAFT.length()).split(SEPARATOR, -1);
        if (parts.length != 4 || parts[1].isEmpty() || parts[2].isEmpty()) {
            return Optional.empty();
        }
        final Field field;
        try {
            field = Field.valueOf(parts[0].toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            return Optional.empty();
        }
        return Optional.of(new Used(field, new Draft(parts[1], parts[2], "1".equals(parts[3]))));
    }
}

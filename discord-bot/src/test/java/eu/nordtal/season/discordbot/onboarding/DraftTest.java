package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.discordbot.Ids;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DraftTest {
    private static final Choices.Choice GERMANY =
            new Choices.Choice(Choices.Kind.COUNTRY, "de", "Deutschland", "de", "Europe/Berlin", "de", "", "");
    private static final Choices.Choice UNITED_KINGDOM =
            new Choices.Choice(Choices.Kind.COUNTRY, "gb", "United Kingdom", "gb", "Europe/London", "en", "", "");

    @Test
    void aCountryChoiceOpensTheDialogWithTheCountrysLanguageNotChosenYet() {
        assertEquals(new Draft("de", "de", false), Draft.of(GERMANY));
    }

    @Test
    void anotherCountryBringsItsLanguageUntilTheMemberChoseOne() {
        assertEquals(new Draft("gb", "en", false), Draft.of(GERMANY).withCountry(UNITED_KINGDOM));
        assertEquals(
                new Draft("gb", "de", true),
                Draft.of(GERMANY).withLanguage("de").withCountry(UNITED_KINGDOM));
    }

    @Test
    void everyComponentIdCarriesTheWholeStateAndReadsBack() {
        final Draft draft = new Draft("us-east", "en", true);
        for (final Draft.Field field : Draft.Field.values()) {
            final String id = draft.id(field);
            assertTrue(id.startsWith(Ids.ONBOARD_DRAFT), id);
            assertTrue(id.length() <= 100, id);
            assertEquals(Optional.of(new Draft.Used(field, draft)), Draft.parse(id));
        }
    }

    @Test
    void anyOtherIdIsNoDialog() {
        assertEquals(Optional.empty(), Draft.parse(Ids.ONBOARD_COUNTRY));
        assertEquals(Optional.empty(), Draft.parse(Ids.ONBOARD_DRAFT + "confirm:de:de"));
        assertEquals(Optional.empty(), Draft.parse(Ids.ONBOARD_DRAFT + "jump:de:de:0"));
        assertEquals(Optional.empty(), Draft.parse(Ids.ONBOARD_DRAFT + "confirm::de:0"));
    }
}

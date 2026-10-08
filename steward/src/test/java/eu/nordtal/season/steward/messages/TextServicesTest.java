package eu.nordtal.season.steward.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A service shows a text its jar ships only where it draws one of the text's places. */
class TextServicesTest {

    @Test
    void aGameServerShowsATextOfTheGameButNotOneOfSteward() {
        assertTrue(TextServices.shows("smp", "smp", List.of("CHAT", "TITLE")));
        assertFalse(TextServices.shows("smp", "admin", List.of("STEWARD", "PUSH")));
    }

    @Test
    void theBotShowsATextWithADiscordPlaceAndNoOther() {
        assertTrue(TextServices.shows("discord-bot", "admin", List.of("STEWARD", "DISCORD_EMBED_HEADING")));
        assertFalse(TextServices.shows("discord-bot", "database", List.of("STEWARD", "CHAT")));
    }

    @Test
    void stewardShowsItsPageAndItsNotificationsAndNoGameText() {
        assertTrue(TextServices.shows("steward", "admin", List.of("PUSH")));
        assertFalse(TextServices.shows("steward", "smp", List.of("CHAT")));
    }

    @Test
    void aBuildingBlockStaysWithEveryServiceThatShipsIt() {
        assertTrue(TextServices.shows("smp", "values", List.of("DISCORD_MESSAGE", "STEWARD")));
        assertTrue(TextServices.shows("steward", "values", List.of("CHAT")));
    }

    @Test
    void aTextThatNamesNoPlaceStaysWithEveryServiceThatShipsIt() {
        assertTrue(TextServices.shows("discord-bot", "smp", List.of()));
    }

    @Test
    void onlyTheServicesThatShowATextAreKeptInTheirOrder() {
        assertEquals(
                List.of("discord-bot", "steward"),
                TextServices.showing(
                        List.of("discord-bot", "smp", "steward"), "admin", List.of("STEWARD", "DISCORD_EMBED")));
    }
}

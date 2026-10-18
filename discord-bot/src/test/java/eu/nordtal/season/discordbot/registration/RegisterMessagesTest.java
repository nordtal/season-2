package eu.nordtal.season.discordbot.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.util.Locale;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import org.junit.jupiter.api.Test;

class RegisterMessagesTest {

    @Test
    void theRegisterButtonCarriesTheGamesRegisterId() {
        final DocumentRenderer.Drawn drawn =
                RegisterMessages.register(StoredDocumentsStandIn.renderer(), Locale.ENGLISH);

        assertEquals(
                Ids.of(Game.HUNGER_GAMES).register(),
                ((ActionRow) drawn.components().getLast())
                        .getButtons()
                        .getFirst()
                        .getCustomId());
    }
}

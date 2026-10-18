package eu.nordtal.season.discordbot.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.StoredLayoutsStandIn;
import eu.nordtal.season.messages.Messages;
import java.util.Locale;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import org.junit.jupiter.api.Test;

class RegisterMessagesTest {

    @Test
    void theRegisterButtonCarriesTheGamesRegisterId() {
        final LayoutRenderer.Drawn drawn = RegisterMessages.register(
                new LayoutRenderer(
                        DiscordRenderer.of(Messages.load("messages/access", Locale.ENGLISH)),
                        StoredLayoutsStandIn.none()),
                Locale.ENGLISH);

        assertEquals(
                Ids.of(Game.HUNGER_GAMES).register(),
                ((ActionRow) drawn.components().getLast())
                        .getButtons()
                        .getFirst()
                        .getCustomId());
    }
}

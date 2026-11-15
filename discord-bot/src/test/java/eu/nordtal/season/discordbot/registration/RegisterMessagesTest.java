package eu.nordtal.season.discordbot.registration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.junit.jupiter.api.Test;

class RegisterMessagesTest {

    @Test
    void theRegisterMessageIsOneBoxUnderItsBannerWithTheGamesRegisterButton() {
        final DocumentRenderer.Drawn drawn =
                RegisterMessages.register(StoredDocumentsStandIn.renderer(), Locale.ENGLISH);

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertEquals(List.of("hunger-games.png"), drawn.attachments()),
                () -> assertEquals(
                        Ids.of(Game.HUNGER_GAMES).register(),
                        MessageComponentTree.of(drawn.components())
                                .findAll(Button.class)
                                .getFirst()
                                .getCustomId()));
    }
}

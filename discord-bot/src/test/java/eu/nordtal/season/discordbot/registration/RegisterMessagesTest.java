package eu.nordtal.season.discordbot.registration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.TeamContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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

    @Test
    void theInvitationIsOneBoxThatNamesWhoInvitesIntoWhichTeamAboveItsTwoAnswers() {
        final UUID member = UUID.fromString("22222222-2222-2222-2222-222222222222");
        final Ids ids = Ids.of(Game.HUNGER_GAMES);
        final DocumentRenderer.Drawn drawn = RegisterFlow.invitation(
                StoredDocumentsStandIn.renderer(),
                ids,
                member,
                new RegisterFlow.Invitation(
                        new DiscordMemberContext(DiscordId.of("300000000000000002"), "Fox"),
                        new TeamContext("Red_Fox")),
                Locale.ENGLISH);

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertEquals(
                        "<@300000000000000002> invites you to team **Red\\_Fox** for the Hunger Games.", drawn.text()),
                () -> assertEquals(
                        List.of(ids.inviteAccept() + member, ids.inviteDecline() + member),
                        MessageComponentTree.of(drawn.components()).findAll(Button.class).stream()
                                .map(Button::getCustomId)
                                .toList()));
    }
}

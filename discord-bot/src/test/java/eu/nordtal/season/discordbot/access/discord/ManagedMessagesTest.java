package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.StoredLayoutsStandIn;
import eu.nordtal.season.messages.Messages;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.junit.jupiter.api.Test;

/** The contribution and link messages, drawn from their layouts. */
class ManagedMessagesTest {

    private static final LayoutRenderer RENDERER = new LayoutRenderer(
            DiscordRenderer.of(Messages.load("messages/access", Locale.ENGLISH, Locale.GERMAN)),
            StoredLayoutsStandIn.none());

    @Test
    void theContributionMessageListsEachTierOverItsBannerWithTheBuyButtonUnderIt() {
        final LayoutRenderer.Drawn drawn = ManagedMessages.contribution(
                RENDERER, Tiers.of(List.of(new Tier(30, 500), new Tier(90, 1250)), 500), Locale.ENGLISH);
        final Container box = (Container) drawn.components().getFirst();
        final ActionRow row = (ActionRow) drawn.components().getLast();

        assertAll(
                () -> assertEquals(
                        "### Prices\n**€5.00** 30 days\n**€12.50** 90 days",
                        ((TextDisplay) box.getComponents().get(1)).getContent()),
                () -> assertEquals(List.of("contribution.png"), drawn.attachments()),
                () -> assertEquals(Ids.BUY, row.getButtons().getFirst().getCustomId()),
                () -> assertEquals("Buy access", row.getButtons().getFirst().getLabel()));
    }

    @Test
    void theLinkStepsNameTheButtonByItsOwnLabel() {
        final LayoutRenderer.Drawn drawn = ManagedMessages.link(RENDERER, Locale.GERMAN);
        final Container box = (Container) drawn.components().getFirst();
        final ActionRow row = (ActionRow) drawn.components().getLast();

        assertEquals(
                "### Schritte\nServer betreten → Code erhalten → **"
                        + row.getButtons().getFirst().getLabel() + "**",
                ((TextDisplay) box.getComponents().get(1)).getContent());
    }
}

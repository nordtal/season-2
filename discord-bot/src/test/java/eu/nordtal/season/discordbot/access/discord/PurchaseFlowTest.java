package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.StoredLayoutsStandIn;
import eu.nordtal.season.messages.Messages;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PurchaseFlowTest {

    private static final LayoutRenderer RENDERER = new LayoutRenderer(
            DiscordRenderer.of(Messages.load("messages/access", Locale.ENGLISH)), StoredLayoutsStandIn.none());

    private static final Tiers TIERS = Tiers.of(List.of(new Tier(30, 500), new Tier(90, 1250)), 300);

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Test
    void theChoiceAsksOverASelectOfEveryTier() {
        final LayoutRenderer.Drawn drawn = PurchaseFlow.choose(RENDERER, TIERS, Locale.ENGLISH);
        final StringSelectMenu select = (StringSelectMenu)
                ((ActionRow) drawn.components().getLast()).getComponents().getFirst();

        assertAll(
                () -> assertEquals(
                        "How long do you want access for?",
                        ((TextDisplay) drawn.components().getFirst()).getContent()),
                () -> assertEquals(Ids.DAYS_SELECT, select.getCustomId()),
                () -> assertEquals(
                        List.of("30 days: €5.00", "90 days: €12.50"),
                        select.getOptions().stream()
                                .map(option -> option.getLabel())
                                .toList()),
                () -> assertEquals(
                        List.of("30", "90"),
                        select.getOptions().stream()
                                .map(option -> option.getValue())
                                .toList()));
    }

    @Test
    void theSummaryWithoutADonationOffersOneAtItsPrice() {
        final LayoutRenderer.Drawn drawn = PurchaseFlow.summary(RENDERER, TIERS, request(0, null), Locale.ENGLISH);
        final List<Button> buttons = ((ActionRow) drawn.components().getLast()).getButtons();

        assertAll(
                () -> assertEquals(
                        "**30 days of access** for €5.00.\nTotal: **€5.00**",
                        ((TextDisplay) drawn.components().getFirst()).getContent()),
                () -> assertEquals(
                        List.of(Ids.CONFIRM, Ids.CHANGE, Ids.DONATION),
                        buttons.stream().map(Button::getCustomId).toList()),
                () -> assertEquals(
                        List.of(ButtonStyle.SUCCESS, ButtonStyle.SECONDARY, ButtonStyle.SECONDARY),
                        buttons.stream().map(Button::getStyle).toList()),
                () -> assertEquals("Add a €3.00 donation", buttons.getLast().getLabel()));
    }

    @Test
    void theSummaryWithADonationNamesItAndOffersToRemoveIt() {
        final LayoutRenderer.Drawn drawn = PurchaseFlow.summary(RENDERER, TIERS, request(300, null), Locale.ENGLISH);

        assertAll(
                () -> assertEquals("""
                        **30 days of access** for €5.00.
                        Including a €3.00 donation, which grants the permanent donor role.
                        Total: **€8.00**""", ((TextDisplay) drawn.components().getFirst()).getContent()),
                () -> assertEquals(
                        "Remove the donation",
                        ((ActionRow) drawn.components().getLast())
                                .getButtons()
                                .getLast()
                                .getLabel()));
    }

    @Test
    void theLinkIsOneTextBlockOfTheLinkItsReferenceAndItsExpiry() {
        final LayoutRenderer.Drawn drawn =
                PurchaseFlow.link(RENDERER, request(0, "https://bunq.me/t/example"), Locale.ENGLISH);

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertEquals(
                        "Pay **€5.00** here: https://bunq.me/t/example",
                        drawn.text().lines().findFirst().orElseThrow()),
                () -> assertEquals(
                        "The link expires <t:" + NOW.plusSeconds(3600).getEpochSecond() + ":R>.",
                        drawn.text().lines().toList().getLast()));
    }

    private static PaymentRequest request(final int donationCents, final @Nullable String shareUrl) {
        return new PaymentRequest(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "NTABC123",
                DiscordId.of("300000000000000003"),
                30,
                500 + donationCents,
                donationCents,
                PaymentRequestStatus.OPEN,
                null,
                shareUrl,
                null,
                NOW,
                NOW.plusSeconds(3600),
                null,
                null,
                null,
                null,
                null,
                null);
    }
}

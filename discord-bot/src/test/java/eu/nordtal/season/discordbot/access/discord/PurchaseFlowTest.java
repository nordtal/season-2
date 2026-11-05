package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordbot.access.payment.Purchases;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PurchaseFlowTest {

    private static final DocumentRenderer RENDERER = StoredDocumentsStandIn.renderer();

    private static final Tiers TIERS = Tiers.of(List.of(new Tier(30, 500), new Tier(90, 1250)), 300);

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Test
    void theChoiceAsksOverASelectOfEveryTier() {
        final DocumentRenderer.Drawn drawn = PurchaseFlow.choose(RENDERER, TIERS, Locale.ENGLISH);
        final StringSelectMenu select = (StringSelectMenu)
                ((ActionRow) drawn.components().getLast()).getComponents().getFirst();

        assertAll(
                () -> assertEquals(
                        "How long do you want access?",
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
        final DocumentRenderer.Drawn drawn = PurchaseFlow.summary(RENDERER, TIERS, request(0, null), Locale.ENGLISH);
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
        final DocumentRenderer.Drawn drawn = PurchaseFlow.summary(RENDERER, TIERS, request(300, null), Locale.ENGLISH);

        assertAll(
                () -> assertEquals("""
                        **30 days of access** for €5.00.
                        This includes a donation of €3.00. It gives you the permanent donor role.
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
        final DocumentRenderer.Drawn drawn =
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

    @Test
    void accessIsForSaleOnceTheSeasonHasALaunchDateAheadOrBehind() {
        final SeasonState closed = new SeasonState(null, false, SeasonMode.HUNGER_GAMES);

        assertFalse(Purchases.onSale(closed), "no launch date, nothing to sell");
        assertTrue(Purchases.onSale(closed.withLaunch(NOW.plus(Duration.ofDays(3)))), "the period starts at the date");
        assertTrue(Purchases.onSale(closed.withLaunch(NOW.minus(Duration.ofDays(3)))));
        assertTrue(Purchases.onSale(closed.withLaunch(NOW).withMaintenance(true)), "maintenance does not stop sales");
    }

    @Test
    void everyStepOfAPurchaseIsRefusedWithoutALaunchDateInEitherLanguage() {
        final List<String> replies = new ArrayList<>();

        assertTrue(PurchaseFlow.refusedWhileClosed(callback(replies), Locale.ENGLISH, RENDERER, () -> false));
        assertTrue(PurchaseFlow.refusedWhileClosed(callback(replies), Locale.GERMAN, RENDERER, () -> false));

        assertEquals(2, replies.size());
        assertTrue(replies.getFirst().contains("has no launch date yet"), replies.getFirst());
        assertTrue(replies.getLast().contains("hat noch keinen Starttermin"), replies.getLast());
        assertTrue(replies.stream().allMatch(reply -> reply.startsWith("ephemeral ")), replies.toString());
    }

    @Test
    void aSeasonWithALaunchDateLetsThePurchaseGoOnWithoutAWord() {
        final List<String> replies = new ArrayList<>();

        assertFalse(PurchaseFlow.refusedWhileClosed(callback(replies), Locale.ENGLISH, RENDERER, () -> true));
        assertEquals(List.of(), replies);
    }

    @Test
    void aSeasonThatCannotBeReadRefusesThePurchaseAsFailedRatherThanSellingBlind() {
        final List<String> replies = new ArrayList<>();

        assertTrue(PurchaseFlow.refusedWhileClosed(callback(replies), Locale.ENGLISH, RENDERER, () -> {
            throw new IllegalStateException("the database is gone");
        }));
        assertEquals(1, replies.size());
        assertFalse(replies.getFirst().contains("launch date"), replies.getFirst());
    }

    /** A member's interaction that writes each reply it is given as its text, marked when it is ephemeral. */
    private static IReplyCallback callback(final List<String> replies) {
        final String[] sent = new String[1];
        final ReplyCallbackAction action = (ReplyCallbackAction) Proxy.newProxyInstance(
                ReplyCallbackAction.class.getClassLoader(),
                new Class<?>[] {ReplyCallbackAction.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setEphemeral" -> {
                        sent[0] = ((Boolean) args[0] ? "ephemeral " : "") + sent[0];
                        yield proxy;
                    }
                    case "queue" -> {
                        replies.add(sent[0]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (IReplyCallback) Proxy.newProxyInstance(
                IReplyCallback.class.getClassLoader(), new Class<?>[] {IReplyCallback.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("reply") || !(args[0] instanceof MessageCreateData data)) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    sent[0] = data.toData().toString();
                    return action;
                });
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

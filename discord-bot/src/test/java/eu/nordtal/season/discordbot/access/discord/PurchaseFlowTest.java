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
import eu.nordtal.season.discordbot.PrivateReplies;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordbot.access.payment.Purchases;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PurchaseFlowTest {

    private static final DocumentRenderer RENDERER = StoredDocumentsStandIn.renderer();

    private static final Tiers TIERS = Tiers.of(List.of(new Tier(30, 500), new Tier(90, 1250)), 300);

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private static final PrivateReplies REPLIES = new PrivateReplies(Clock.systemUTC());

    @Test
    void theChoiceAsksInOneBoxOverASelectOfEveryTier() {
        final DocumentRenderer.Drawn drawn = PurchaseFlow.choose(RENDERER, TIERS, Locale.ENGLISH);
        final Container box = (Container) drawn.components().getFirst();
        final StringSelectMenu select = (StringSelectMenu)
                ((ActionRow) box.getComponents().getLast()).getComponents().getFirst();

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertEquals("Choose how long your access should last.", drawn.text()),
                () -> assertEquals(Ids.DAYS_SELECT, select.getCustomId()),
                () -> assertEquals(
                        List.of("30 days for €5.00", "90 days for €12.50"),
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
    void theSummaryWithoutADonationIsThePurchaseAloneAndOffersADonationAtItsPrice() {
        final DocumentRenderer.Drawn drawn = PurchaseFlow.summary(RENDERER, TIERS, request(0, null), Locale.ENGLISH);
        final List<Button> buttons = buttons(drawn);

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertEquals("## Your purchase\n**30 days of access** for €5.00", drawn.text()),
                () -> assertEquals(
                        List.of(Ids.CONFIRM, Ids.CHANGE, Ids.DONATION),
                        buttons.stream().map(Button::getCustomId).toList()),
                () -> assertEquals(
                        List.of(ButtonStyle.SUCCESS, ButtonStyle.SECONDARY, ButtonStyle.SECONDARY),
                        buttons.stream().map(Button::getStyle).toList()),
                () -> assertEquals("Add a €3.00 donation", buttons.getLast().getLabel()));
    }

    @Test
    void theSummaryWithADonationNamesItWithTheTotalAndOffersToRemoveIt() {
        final DocumentRenderer.Drawn drawn = PurchaseFlow.summary(RENDERER, TIERS, request(300, null), Locale.ENGLISH);

        assertAll(
                () -> assertEquals("""
                        ## Your purchase
                        **30 days of access** for €5.00
                        **Donation:** €3.00 for the permanent donor role
                        Total: **€8.00**""", drawn.text()),
                () -> assertEquals("Remove donation", buttons(drawn).getLast().getLabel()));
    }

    @Test
    void theLinkIsOneBoxOfTheReferenceTheExpiryAndAButtonThatOpensTheLinkAtItsAmount() {
        final DocumentRenderer.Drawn drawn =
                PurchaseFlow.link(RENDERER, request(0, "https://bunq.me/t/example"), Locale.ENGLISH);
        final Button pay = buttons(drawn).getFirst();

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertEquals(
                        "## Payment link\nKeep **NTABC123** unchanged in the payment reference.\n"
                                + "The link expires <t:" + NOW.plusSeconds(3600).getEpochSecond() + ":R>.",
                        drawn.text()),
                () -> assertEquals(1, buttons(drawn).size()),
                () -> assertEquals(ButtonStyle.LINK, pay.getStyle()),
                () -> assertEquals("https://bunq.me/t/example", pay.getUrl()),
                () -> assertEquals("Pay €5.00", pay.getLabel()));
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

        assertTrue(PurchaseFlow.refusedWhileClosed(callback(replies), Locale.ENGLISH, RENDERER, REPLIES, () -> false));
        assertTrue(PurchaseFlow.refusedWhileClosed(callback(replies), Locale.GERMAN, RENDERER, REPLIES, () -> false));

        assertEquals(2, replies.size());
        assertTrue(replies.getFirst().contains("once the season has a start date"), replies.getFirst());
        assertTrue(replies.getLast().contains("sobald der Starttermin der Saison feststeht"), replies.getLast());
        assertTrue(replies.stream().allMatch(reply -> reply.startsWith("ephemeral ")), replies.toString());
    }

    @Test
    void aSeasonWithALaunchDateLetsThePurchaseGoOnWithoutAWord() {
        final List<String> replies = new ArrayList<>();

        assertFalse(PurchaseFlow.refusedWhileClosed(callback(replies), Locale.ENGLISH, RENDERER, REPLIES, () -> true));
        assertEquals(List.of(), replies);
    }

    @Test
    void aSeasonThatCannotBeReadRefusesThePurchaseAsFailedRatherThanSellingBlind() {
        final List<String> replies = new ArrayList<>();

        assertTrue(PurchaseFlow.refusedWhileClosed(callback(replies), Locale.ENGLISH, RENDERER, REPLIES, () -> {
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
                    if (method.getName().equals("getUser")) {
                        return Proxy.newProxyInstance(
                                User.class.getClassLoader(),
                                new Class<?>[] {User.class},
                                (user, asked, none) -> "300000000000000003");
                    }
                    if (!method.getName().equals("reply") || !(args[0] instanceof MessageCreateData data)) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    sent[0] = data.toData().toString();
                    return action;
                });
    }

    private static List<Button> buttons(final DocumentRenderer.Drawn drawn) {
        return MessageComponentTree.of(drawn.components()).findAll(Button.class);
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

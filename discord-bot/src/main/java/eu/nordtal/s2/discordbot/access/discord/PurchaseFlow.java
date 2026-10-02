package eu.nordtal.s2.discordbot.access.discord;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.payment.Money;
import eu.nordtal.s2.database.payment.PaymentRequest;
import eu.nordtal.s2.database.payment.PaymentRequestStatus;
import eu.nordtal.s2.database.payment.PaymentRequests;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.Ids;
import eu.nordtal.s2.discordbot.access.payment.Purchases;
import eu.nordtal.s2.discordbot.access.payment.Tier;
import eu.nordtal.s2.discordbot.access.payment.Tiers;
import eu.nordtal.s2.messages.Messages;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IDeferrableCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.utils.TimeFormat;

/**
 * The buy-access flow: a button, a day selection, a summary, and a payment link.
 *
 * State lives in the open {@code payment_request}; only the wait for a link is in memory.
 */
@Slf4j
public final class PurchaseFlow extends ListenerAdapter {

    private final Tiers tiers;
    private final Purchases purchases;
    private final PaymentRequests requests;
    private final Messages messages;
    private final AccessRoles roles;
    private final AdminLog admin;
    private final ExecutorService executor;

    /** Ephemeral messages waiting for a link, one per request, so a second confirm replaces the older one. */
    private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();

    /** Held across reading the row and editing the message, so a stale "being created" never overwrites the link. */
    private final Object drawing = new Object();

    /** How long a message says the link is coming, under the fifteen minutes an interaction hook lives. */
    private static final Duration GIVE_UP = Duration.ofMinutes(10);

    /** One ephemeral message, and when it started waiting. */
    private record Waiting(InteractionHook hook, Locale locale, Instant since) {}

    private final Clock clock;

    public PurchaseFlow(
            final Tiers tiers,
            final Purchases purchases,
            final PaymentRequests requests,
            final Messages messages,
            final AccessRoles roles,
            final AdminLog admin,
            final ExecutorService executor,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.tiers = tiers;
        this.purchases = purchases;
        this.requests = requests;
        this.messages = messages;
        this.roles = roles;
        this.admin = admin;
        this.executor = executor;
    }

    @Override
    public void onButtonInteraction(final ButtonInteractionEvent event) {
        final String id = event.getComponentId();
        if (!id.startsWith("access:")) {
            return;
        }
        final Locale locale = roles.localeOf(DiscordId.of(event.getUser().getId()));

        switch (id) {
            case Ids.BUY, Ids.CHANGE -> chooseDays(event, locale);
            case Ids.DONATION -> toggleDonation(event, locale);
            case Ids.CONFIRM -> confirm(event, locale);
            default -> log.debug("Ignoring unknown component id {}", id);
        }
    }

    @Override
    public void onStringSelectInteraction(final StringSelectInteractionEvent event) {
        if (!Ids.DAYS_SELECT.equals(event.getComponentId())) {
            return;
        }
        final Locale locale = roles.localeOf(DiscordId.of(event.getUser().getId()));
        final int days = Integer.parseInt(event.getValues().getFirst());

        final Optional<Tier> tier = tiers.byDays(days);
        if (tier.isEmpty()) {
            // The prices changed between the message being rendered and the click.
            reply(event, messages.format(locale, MESSAGES.purchase().tierGone()));
            return;
        }

        // The row, not the button, decides whether the donation stays on across a tier change.
        final boolean donation = requests.openOf(DiscordId.of(event.getUser().getId()))
                .map(PaymentRequest::donationRequested)
                .orElse(false);

        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                final PaymentRequest request =
                        purchases.select(DiscordId.of(event.getUser().getId()), tier.get(), donation);
                showSummary(event, locale, request);
            } catch (final RuntimeException exception) {
                fail(event, locale, "selecting " + days + " days", exception);
            }
        });
    }

    private void chooseDays(final ButtonInteractionEvent event, final Locale locale) {
        final List<SelectOption> options = new ArrayList<>();
        for (final Tier tier : tiers.all()) {
            options.add(SelectOption.of(
                    messages.format(locale, MESSAGES.purchase().option(tier.days(), Money.format(tier.priceCents()))),
                    String.valueOf(tier.days())));
        }

        final ActionRow row = ActionRow.of(StringSelectMenu.create(Ids.DAYS_SELECT)
                .setPlaceholder(messages.format(locale, MESSAGES.purchase().choose()))
                .addOptions(options)
                .build());

        if (Ids.CHANGE.equals(event.getComponentId())) {
            event.editMessage(messages.format(locale, MESSAGES.purchase().choose()))
                    .setComponents(row)
                    .queue();
        } else {
            event.reply(messages.format(locale, MESSAGES.purchase().choose()))
                    .setEphemeral(true)
                    .addComponents(row)
                    .queue();
        }
    }

    private void toggleDonation(final ButtonInteractionEvent event, final Locale locale) {
        final Optional<PaymentRequest> open =
                requests.openOf(DiscordId.of(event.getUser().getId()));
        if (open.isEmpty() || open.get().tab().isPresent()) {
            reply(event, messages.format(locale, MESSAGES.purchase().gone()));
            return;
        }

        final PaymentRequest request = open.get();
        final Optional<Tier> tier = tiers.byDays(request.days());
        if (tier.isEmpty()) {
            reply(event, messages.format(locale, MESSAGES.purchase().tierGone()));
            return;
        }

        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                showSummary(
                        event,
                        locale,
                        purchases.select(
                                DiscordId.of(event.getUser().getId()), tier.get(), !request.donationRequested()));
            } catch (final RuntimeException exception) {
                fail(event, locale, "toggling the donation", exception);
            }
        });
    }

    private void confirm(final ButtonInteractionEvent event, final Locale locale) {
        final Optional<PaymentRequest> open =
                requests.openOf(DiscordId.of(event.getUser().getId()));
        if (open.isEmpty()) {
            reply(event, messages.format(locale, MESSAGES.purchase().gone()));
            return;
        }

        final PaymentRequest request = open.get();
        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                // Writes tab_requested and returns; the row read below says which case this is.
                purchases.confirm(request);

                synchronized (drawing) {
                    final PaymentRequest fresh = requests.byId(request.id()).orElse(request);
                    if (settled(event.getHook(), locale, fresh)) {
                        // A second confirm on a row with a tab, or the request is gone: nothing to wait for.
                        return;
                    }
                    // Registered before the message is drawn, so a tab arriving this instant is caught by fillIn().
                    waiting.put(request.id(), new Waiting(event.getHook(), locale, clock.instant()));
                    event.getHook()
                            .editOriginal(messages.format(
                                    locale, MESSAGES.purchase().linkSection().pending()))
                            .setComponents(List.of())
                            .queue();
                }
            } catch (final RuntimeException exception) {
                waiting.remove(request.id());
                fail(event, locale, "asking for the payment link", exception);
            }
        });
    }

    /**
     * Finishes every waiting message whose row now has a link, a refusal, or has waited too long.
     *
     * Called on {@code nordtal_payment} and by the payment timer; it re-reads each row in full.
     */
    public void fillIn() {
        if (waiting.isEmpty()) {
            return;
        }
        synchronized (drawing) {
            final Iterator<Map.Entry<UUID, Waiting>> entries =
                    waiting.entrySet().iterator();
            while (entries.hasNext()) {
                final Map.Entry<UUID, Waiting> entry = entries.next();
                final Optional<PaymentRequest> row = requests.byId(entry.getKey());
                if (row.isEmpty()) {
                    // Nothing deletes a payment_request, so this is unreachable; dropping the entry is still sane.
                    entries.remove();
                    continue;
                }
                final Waiting waiter = entry.getValue();
                if (settled(waiter.hook(), waiter.locale(), row.get())) {
                    entries.remove();
                } else if (Duration.between(waiter.since(), clock.instant()).compareTo(GIVE_UP) > 0) {
                    // The last thing this message says; it names the reference, which is what an admin needs.
                    waiter.hook()
                            .editOriginal(messages.format(
                                    waiter.locale(),
                                    MESSAGES.purchase()
                                            .linkSection()
                                            .slow(row.get().reference())))
                            .setComponents(List.of())
                            .queue();
                    log.warn("Request {} had no tab after {}", row.get().reference(), GIVE_UP);
                    entries.remove();
                }
            }
        }
    }

    /**
     * Draws the end of the wait, if it has come.
     *
     * @return {@code true} when the message is final; {@code false} while the row is still waiting for an answer
     */
    private boolean settled(final InteractionHook hook, final Locale locale, final PaymentRequest request) {
        if (request.status() != PaymentRequestStatus.OPEN) {
            edit(hook, messages.format(locale, MESSAGES.purchase().gone()));
            return true;
        }
        if (request.shareUrl() != null) {
            edit(
                    hook,
                    messages.format(
                                    locale,
                                    MESSAGES.purchase().link(Money.format(request.amountCents()), request.shareUrl()))
                            + "\n"
                            + messages.format(
                                    locale, MESSAGES.purchase().linkSection().reference(request.reference()))
                            + "\n"
                            + messages.format(
                                    locale,
                                    MESSAGES.purchase()
                                            .linkSection()
                                            .ttl(TimeFormat.RELATIVE.format(request.expires()))));
            return true;
        }
        if (request.tabFailed() != null) {
            // bunq's error text goes to the admins as an alert, not to the buyer.
            admin.alert(new Alert(
                    AlertType.PAYMENT,
                    Alert.Level.DOWN,
                    "purchase",
                    "bunq refused a payment link",
                    "`" + request.reference() + "` `" + request.tabFailed() + "`",
                    "/payments"));
            edit(hook, messages.format(locale, MESSAGES.purchase().linkSection().refused()));
            return true;
        }
        return false;
    }

    private void edit(final InteractionHook hook, final String text) {
        hook.editOriginal(text)
                .setComponents(List.of())
                .queue(ok -> {}, failure -> log.warn("Could not finish a purchase message", failure));
    }

    private void showSummary(final IDeferrableCallback event, final Locale locale, final PaymentRequest request) {
        final StringBuilder text = new StringBuilder()
                .append(messages.format(
                        locale,
                        MESSAGES.purchase()
                                .summary(
                                        request.days(),
                                        Money.format(request.amountCents() - request.donationCents()))));
        if (request.donationRequested()) {
            text.append('\n')
                    .append(messages.format(
                            locale,
                            MESSAGES.purchase().summarySection().donation(Money.format(request.donationCents()))));
        }
        text.append('\n')
                .append(messages.format(
                        locale, MESSAGES.purchase().summarySection().total(Money.format(request.amountCents()))));

        final Button donation = request.donationRequested()
                ? Button.secondary(
                        Ids.DONATION,
                        messages.format(
                                locale, MESSAGES.purchase().button().donation().remove()))
                : Button.secondary(
                        Ids.DONATION,
                        messages.format(
                                locale,
                                MESSAGES.purchase().button().donation().add(Money.format(tiers.donationCents()))));

        event.getHook()
                .editOriginal(text.toString())
                .setComponents(ActionRow.of(
                        Button.success(
                                Ids.CONFIRM,
                                messages.format(
                                        locale, MESSAGES.purchase().button().confirm())),
                        Button.secondary(
                                Ids.CHANGE,
                                messages.format(
                                        locale, MESSAGES.purchase().button().change())),
                        donation))
                .queue();
    }

    private void reply(final IReplyCallback event, final String text) {
        event.reply(text).setEphemeral(true).queue();
    }

    /** Tells the user a plain sentence and raises the detail as an alert. */
    private void fail(
            final IDeferrableCallback event, final Locale locale, final String what, final RuntimeException exception) {
        log.error("Purchase failed while {}", what, exception);
        admin.alert(new Alert(
                AlertType.PAYMENT,
                Alert.Level.DOWN,
                "purchase",
                "A purchase failed",
                event.getUser().getAsMention() + " " + what + " `" + exception + "`",
                "/payments"));
        event.getHook()
                .editOriginal(messages.format(locale, MESSAGES.purchase().failed()))
                .setComponents(List.of())
                .queue();
    }
}

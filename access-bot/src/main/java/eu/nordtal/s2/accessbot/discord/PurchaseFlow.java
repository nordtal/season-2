package eu.nordtal.s2.accessbot.discord;

import eu.nordtal.s2.accessbot.bunq.Money;
import eu.nordtal.s2.accessbot.config.AccessSpec;
import eu.nordtal.s2.accessbot.payment.PaymentRequest;
import eu.nordtal.s2.accessbot.payment.Purchases;
import eu.nordtal.s2.accessbot.payment.PaymentRequests;
import eu.nordtal.s2.accessbot.payment.Tier;
import eu.nordtal.s2.accessbot.payment.Tiers;
import eu.nordtal.s2.common.message.Messages;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IDeferrableCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * The buy-access flow: a button, a day selection, a summary, and a payment link.
 *
 * <h2>The row is the state</h2>
 * There is no cache of half-finished purchases. Every handler here looks the user's one open
 * {@code payment_request} up and works from that, so a restart in the middle of a purchase is
 * invisible: the next click reads the same row. Season 1 kept the flow in a Guava cache and
 * answered "setup expired" to everybody who was mid-purchase when the bot restarted.
 *
 * <h2>Blocking work is off the gateway thread</h2>
 * Creating and cancelling a bunq.me tab are HTTP calls. They run on {@code executor} after the
 * interaction has been acknowledged, because a JDA event thread that blocks on a bank stalls every
 * other interaction in the guild, and an interaction that is not acknowledged within three seconds
 * is dead.
 */
@Slf4j
public final class PurchaseFlow extends ListenerAdapter {

    private final AccessSpec config;
    private final Tiers tiers;
    private final Purchases purchases;
    private final PaymentRequests requests;
    private final Messages messages;
    private final AccessRoles roles;
    private final AdminLog admin;
    private final ExecutorService executor;

    public PurchaseFlow(final AccessSpec config, final Tiers tiers, final Purchases purchases,
                        final PaymentRequests requests, final Messages messages, final AccessRoles roles,
                        final AdminLog admin, final ExecutorService executor) {
        this.config = config;
        this.tiers = tiers;
        this.purchases = purchases;
        this.requests = requests;
        this.messages = messages;
        this.roles = roles;
        this.admin = admin;
        this.executor = executor;
    }

    @Override
    public void onButtonInteraction(final @NotNull ButtonInteractionEvent event) {
        final String id = event.getComponentId();
        if (!id.startsWith("access:")) {
            return;
        }
        final Locale locale = roles.localeOf(event.getUser().getId());

        switch (id) {
            case Ids.BUY, Ids.CHANGE -> chooseDays(event, locale);
            case Ids.DONATION -> toggleDonation(event, locale);
            case Ids.CONFIRM -> confirm(event, locale);
            default -> log.debug("Ignoring unknown component id {}", id);
        }
    }

    @Override
    public void onStringSelectInteraction(final @NotNull StringSelectInteractionEvent event) {
        if (!Ids.DAYS_SELECT.equals(event.getComponentId())) {
            return;
        }
        final Locale locale = roles.localeOf(event.getUser().getId());
        final int days = Integer.parseInt(event.getValues().getFirst());

        final Optional<Tier> tier = tiers.byDays(days);
        if (tier.isEmpty()) {
            // The prices changed between the message being rendered and the click.
            reply(event, messages.get(locale, "purchase.tier-gone"));
            return;
        }

        // Whether the donation stays on across a change of tier is decided by the row, not by the
        // button that was clicked, so somebody who added a donation and then changed their mind
        // about the length keeps the donation.
        final boolean donation = requests.openOf(event.getUser().getId())
                .map(PaymentRequest::donationRequested)
                .orElse(false);

        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                final PaymentRequest request = purchases.select(event.getUser().getId(), tier.get(), donation);
                showSummary(event, locale, request);
            } catch (final RuntimeException exception) {
                fail(event, locale, "selecting " + days + " days", exception);
            }
        });
    }

    // ---------------------------------------------------------------- steps

    private void chooseDays(final ButtonInteractionEvent event, final Locale locale) {
        final List<SelectOption> options = new ArrayList<>();
        for (final Tier tier : tiers.all()) {
            options.add(SelectOption.of(
                    messages.format(locale, "purchase.option",
                            "days", tier.days(), "price", Money.format(tier.priceCents())),
                    String.valueOf(tier.days())));
        }

        final ActionRow row = ActionRow.of(StringSelectMenu.create(Ids.DAYS_SELECT)
                .setPlaceholder(messages.get(locale, "purchase.choose"))
                .addOptions(options)
                .build());

        if (Ids.CHANGE.equals(event.getComponentId())) {
            event.editMessage(messages.get(locale, "purchase.choose")).setComponents(row).queue();
        } else {
            event.reply(messages.get(locale, "purchase.choose"))
                    .setEphemeral(true)
                    .addComponents(row)
                    .queue();
        }
    }

    private void toggleDonation(final ButtonInteractionEvent event, final Locale locale) {
        final Optional<PaymentRequest> open = requests.openOf(event.getUser().getId());
        if (open.isEmpty() || open.get().tab().isPresent()) {
            reply(event, messages.get(locale, "purchase.gone"));
            return;
        }

        final PaymentRequest request = open.get();
        final Optional<Tier> tier = tiers.byDays(request.days());
        if (tier.isEmpty()) {
            reply(event, messages.get(locale, "purchase.tier-gone"));
            return;
        }

        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                showSummary(event, locale,
                        purchases.select(event.getUser().getId(), tier.get(), !request.donationRequested()));
            } catch (final RuntimeException exception) {
                fail(event, locale, "toggling the donation", exception);
            }
        });
    }

    private void confirm(final ButtonInteractionEvent event, final Locale locale) {
        final Optional<PaymentRequest> open = requests.openOf(event.getUser().getId());
        if (open.isEmpty()) {
            reply(event, messages.get(locale, "purchase.gone"));
            return;
        }

        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                final PaymentRequest request = purchases.confirm(open.get());
                event.getHook().editOriginal(
                                messages.format(locale, "purchase.link",
                                        "total", Money.format(request.amountCents()),
                                        "url", request.shareUrl())
                                        + "\n" + messages.format(locale, "purchase.link.reference",
                                        "reference", request.reference())
                                        + "\n" + messages.format(locale, "purchase.link.ttl",
                                        "hours", config.payment().requestTtlHours()))
                        .setComponents(List.of())
                        .queue();
            } catch (final RuntimeException exception) {
                fail(event, locale, "creating the bunq.me tab", exception);
            }
        });
    }

    private void showSummary(final IDeferrableCallback event, final Locale locale,
                             final PaymentRequest request) {
        final StringBuilder text = new StringBuilder()
                .append(messages.format(locale, "purchase.summary",
                        "days", request.days(),
                        "price", Money.format(request.amountCents() - request.donationCents())));
        if (request.donationRequested()) {
            text.append('\n').append(messages.format(locale, "purchase.summary.donation",
                    "donation", Money.format(request.donationCents())));
        }
        text.append('\n').append(messages.format(locale, "purchase.summary.total",
                "total", Money.format(request.amountCents())));

        final Button donation = request.donationRequested()
                ? Button.secondary(Ids.DONATION, messages.get(locale, "purchase.button.donation.remove"))
                : Button.secondary(Ids.DONATION, messages.format(locale, "purchase.button.donation.add",
                "amount", Money.format(tiers.donationCents())));

        event.getHook().editOriginal(text.toString())
                .setComponents(ActionRow.of(
                        Button.success(Ids.CONFIRM, messages.get(locale, "purchase.button.confirm")),
                        Button.secondary(Ids.CHANGE, messages.get(locale, "purchase.button.change")),
                        donation))
                .queue();
    }

    // ---------------------------------------------------------------- failure

    private void reply(final IReplyCallback event, final String text) {
        event.reply(text).setEphemeral(true).queue();
    }

    /**
     * One place for "the bank or the database said no".
     * <p>
     * The user gets a plain sentence and an admin gets the detail: a stack trace in a log nobody
     * is watching is how season 1 lost failed role assignments.
     * </p>
     */
    private void fail(final IDeferrableCallback event, final Locale locale, final String what,
                      final RuntimeException exception) {
        log.error("Purchase failed while {}", what, exception);
        admin.alert("A purchase failed while " + what + ": `" + exception + "`");
        event.getHook().editOriginal(messages.get(locale, "purchase.failed"))
                .setComponents(List.of())
                .queue();
    }
}

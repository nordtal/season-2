package eu.nordtal.s2.discordbot.access.discord;

import eu.nordtal.s2.discordbot.discord.Ids;

import eu.nordtal.s2.discordbot.discord.AdminLog;

import eu.nordtal.s2.common.payment.Money;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.common.payment.PaymentRequest;
import eu.nordtal.s2.common.payment.PaymentRequestStatus;
import eu.nordtal.s2.discordbot.access.payment.Purchases;
import eu.nordtal.s2.common.payment.PaymentRequests;
import eu.nordtal.s2.discordbot.access.payment.Tier;
import eu.nordtal.s2.discordbot.access.payment.Tiers;
import eu.nordtal.s2.common.message.Messages;

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
import org.jetbrains.annotations.NotNull;

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
 * Every database write here runs on {@code executor} after the interaction has been acknowledged,
 * because a JDA event thread that blocks stalls every other interaction in the guild, and an
 * interaction that is not acknowledged within three seconds is dead.
 *
 * <h2>The payment link arrives after the answer (steward/109)</h2>
 * Confirming used to call bunq from this class and print the link in the same breath. The key now
 * lives in {@code steward-worker}, so confirming writes {@code tab_requested} and the message says
 * the link is being made. {@link #fillIn()} is what finishes the sentence: driven by
 * {@code nordtal_payment} and by the bot's payment timer underneath it, it re-reads every row
 * somebody is waiting on and edits the ephemeral message into the link, into the refusal
 * ({@code tab_failed}), or - if neither ever comes - into a line naming the reference, so that
 * "your payment link is being created" is never the last thing a person is told.
 *
 * <h2>What waiting costs, and why it is a map and not a row</h2>
 * An {@link InteractionHook} cannot be stored: it is a token, valid for fifteen minutes, that only
 * this process holds. So the wait is in memory and a restart loses it - the request itself, its
 * reference and its tab all survive in the table, only the half-written message does not. That is
 * the same trade the rest of the flow refuses to make for <em>state</em>, and it is acceptable here
 * for exactly one reason: nothing is lost by it that is not re-derivable from a row.
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

    /**
     * Ephemeral messages waiting for a link, by the request they are waiting for.
     *
     * <p>One entry per request, not per message: somebody who confirms twice is looking at the
     * newer message, and the older one keeps the sentence it already has.</p>
     */
    private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();

    /**
     * Held across "read the row, then edit the message".
     *
     * <p>Two threads reach that pair - the interaction's own executor and whatever drives
     * {@link #fillIn()} - and without the lock the losing one can write "the link is being created"
     * over a message that already shows the link. The work under it is a query and a
     * {@code queue()}, so nothing here waits on Discord while holding it.</p>
     */
    private final Object drawing = new Object();

    /**
     * How long a message is left saying the link is coming.
     *
     * <p>Under the fifteen minutes an interaction hook lives, because the point of the limit is to
     * be able to say something else while the message can still be edited. A tab that takes ten
     * minutes is not coming: the worker's poll is thirty seconds and its queue is oldest-ask-first.
     * </p>
     */
    private static final Duration GIVE_UP = Duration.ofMinutes(10);

    /** One ephemeral message, and when it started waiting. */
    private record Waiting(InteractionHook hook, Locale locale, Instant since) {
    }

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

        final PaymentRequest request = open.get();
        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                // Writes tab_requested and returns. It is deliberately not asked whether it wrote:
                // false means the row already has a tab or is no longer open, and the row that is
                // read a line later says which of those it is - and what to draw for it.
                purchases.confirm(request);

                synchronized (drawing) {
                    final PaymentRequest fresh = requests.byId(request.id()).orElse(request);
                    if (settled(event.getHook(), locale, fresh)) {
                        // The link was already there - a second confirm on a row that has a tab -
                        // or the request went away underneath the click. Either way it is finished
                        // and nobody has to wait for anything.
                        return;
                    }
                    // Registered before the message is drawn, so a tab that arrives in this instant
                    // is picked up by fillIn() rather than falling between the two.
                    waiting.put(request.id(), new Waiting(event.getHook(), locale, Instant.now()));
                    event.getHook().editOriginal(messages.get(locale, "purchase.link.pending"))
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
     * Finishes every message that can be finished.
     *
     * <p>Called on {@code nordtal_payment} and by the bot's payment timer. It re-reads each awaited
     * row in full rather than trusting the signal - the signal carries no payload and is not the
     * state - and it is cheap when nobody is waiting, which is almost always.</p>
     */
    public void fillIn() {
        if (waiting.isEmpty()) {
            return;
        }
        synchronized (drawing) {
            final Iterator<Map.Entry<UUID, Waiting>> entries = waiting.entrySet().iterator();
            while (entries.hasNext()) {
                final Map.Entry<UUID, Waiting> entry = entries.next();
                final Optional<PaymentRequest> row = requests.byId(entry.getKey());
                if (row.isEmpty()) {
                    // Nothing deletes a payment_request, so this is unreachable rather than
                    // expected - and dropping the entry is still the only sane answer to it.
                    entries.remove();
                    continue;
                }
                final Waiting waiter = entry.getValue();
                if (settled(waiter.hook(), waiter.locale(), row.get())) {
                    entries.remove();
                } else if (Duration.between(waiter.since(), Instant.now()).compareTo(GIVE_UP) > 0) {
                    // The last thing this message will ever say. It names the reference because
                    // that is the one string an admin needs to find the row by hand.
                    waiter.hook().editOriginal(messages.format(waiter.locale(), "purchase.link.slow",
                                    "reference", row.get().reference()))
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
     * @return {@code true} when the message was edited into something final and nothing more is
     *         owed to it; {@code false} while the row is still between the ask and the answer
     */
    private boolean settled(final InteractionHook hook, final Locale locale,
                            final PaymentRequest request) {
        if (request.status() != PaymentRequestStatus.OPEN) {
            edit(hook, messages.get(locale, "purchase.gone"));
            return true;
        }
        if (request.shareUrl() != null) {
            edit(hook, messages.format(locale, "purchase.link",
                    "total", Money.format(request.amountCents()),
                    "url", request.shareUrl())
                    + "\n" + messages.format(locale, "purchase.link.reference",
                    "reference", request.reference())
                    + "\n" + messages.format(locale, "purchase.link.ttl",
                    "hours", config.payment().requestTtlHours()));
            return true;
        }
        if (request.tabFailed() != null) {
            // What bunq said goes to the admin channel and not to the buyer: it is a bank's error
            // text, and the person waiting can do nothing with it except worry.
            admin.alert("bunq refused a payment link for `" + request.reference() + "`: `"
                    + request.tabFailed() + "`");
            edit(hook, messages.get(locale, "purchase.link.refused"));
            return true;
        }
        return false;
    }

    private void edit(final InteractionHook hook, final String text) {
        hook.editOriginal(text).setComponents(List.of()).queue(ok -> {
        }, failure -> log.warn("Could not finish a purchase message", failure));
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

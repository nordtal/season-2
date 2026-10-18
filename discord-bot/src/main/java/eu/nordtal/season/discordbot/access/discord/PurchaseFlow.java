package eu.nordtal.season.discordbot.access.discord;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.payment.PaymentRequests;
import eu.nordtal.season.database.payment.TabRefusal;
import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.AdminLog;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.access.payment.Purchases;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IDeferrableCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

@Slf4j
public final class PurchaseFlow extends ListenerAdapter {

    record Option(@Example("30") long days, Money price) {}

    /**
     * @param price    the price of the days alone
     * @param donation the donation the total includes
     * @param donating whether the member added the donation
     * @param amount   the donation the button offers
     */
    record Summary(
            @Example("30") long days, Money price, Money donation, Money total, boolean donating, Money amount) {}

    record Link(
            Money total,
            @Example("https://bunq.me/t/example") String url,
            @Example("NT-A1B2C3") String reference,
            Instant until) {}

    static final DocumentSpec<DocumentSpec.None> CHOOSE = DocumentSpec.message(
            StoredDocuments.NAMESPACE, "purchase-choose", "Purchase", Slot.select("days", Option.class));

    static final DocumentSpec<Summary> SUMMARY = DocumentSpec.message(
            StoredDocuments.NAMESPACE,
            "purchase-summary",
            "Purchase",
            Summary.class,
            Slot.button("confirm"),
            Slot.button("change"),
            Slot.button("donation"));

    static final DocumentSpec<Link> LINK =
            DocumentSpec.message(StoredDocuments.NAMESPACE, "purchase-link", "Purchase", Link.class);

    private final Tiers tiers;
    private final Purchases purchases;
    private final PaymentRequests requests;
    private final DocumentRenderer documents;
    private final AccessRoles roles;
    private final AdminLog admin;
    private final Executor executor;

    private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();

    /** Held while the row is read and the message is edited, so a stale "being created" text cannot hide the link. */
    private final Object drawing = new Object();

    private record Waiting(InteractionHook hook, Locale locale, Instant since) {}

    private final Clock clock;

    public PurchaseFlow(
            final Tiers tiers,
            final Purchases purchases,
            final PaymentRequests requests,
            final DocumentRenderer documents,
            final AccessRoles roles,
            final AdminLog admin,
            final Executor executor,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.tiers = tiers;
        this.purchases = purchases;
        this.requests = requests;
        this.documents = documents;
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
            reply(event, locale, Replies.PURCHASE_TIER_GONE);
            return;
        }

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
        final DocumentRenderer.Drawn drawn = choose(documents, tiers, locale);
        if (Ids.CHANGE.equals(event.getComponentId())) {
            event.editMessage(drawn.edit()).queue();
        } else {
            event.reply(drawn.create()).setEphemeral(true).queue();
        }
    }

    static DocumentRenderer.Drawn choose(final DocumentRenderer documents, final Tiers tiers, final Locale locale) {
        final List<DocumentRenderer.Option> options = tiers.all().stream()
                .map(tier -> new DocumentRenderer.Option(
                        String.valueOf(tier.days()), new Option(tier.days(), Money.euroCents(tier.priceCents())), null))
                .toList();
        return documents.render(
                CHOOSE,
                new DocumentSpec.None(),
                Map.of("days", new DocumentRenderer.FilledSelect(Ids.DAYS_SELECT, options)),
                locale);
    }

    private void toggleDonation(final ButtonInteractionEvent event, final Locale locale) {
        final Optional<PaymentRequest> open =
                requests.openOf(DiscordId.of(event.getUser().getId()));
        if (open.isEmpty() || open.get().tab().isPresent()) {
            reply(event, locale, Replies.PURCHASE_GONE);
            return;
        }

        final PaymentRequest request = open.get();
        final Optional<Tier> tier = tiers.byDays(request.days());
        if (tier.isEmpty()) {
            reply(event, locale, Replies.PURCHASE_TIER_GONE);
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
            reply(event, locale, Replies.PURCHASE_GONE);
            return;
        }

        final PaymentRequest request = open.get();
        event.deferEdit().queue();
        executor.execute(() -> {
            try {
                purchases.confirm(request);

                synchronized (drawing) {
                    final PaymentRequest fresh = requests.byId(request.id()).orElse(request);
                    if (settled(event.getHook(), locale, fresh, Duration.ZERO)) {
                        return;
                    }
                    waiting.put(request.id(), new Waiting(event.getHook(), locale, clock.instant()));
                    event.getHook()
                            .editOriginal(documents
                                    .reply(locale, Replies.PURCHASE_PENDING)
                                    .edit())
                            .queue();
                }
            } catch (final RuntimeException exception) {
                waiting.remove(request.id());
                fail(event, locale, "asking for the payment link", exception);
            }
        });
    }

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
                    entries.remove();
                    continue;
                }
                final Waiting waiter = entry.getValue();
                final Duration waited = Duration.between(waiter.since(), clock.instant());
                if (settled(waiter.hook(), waiter.locale(), row.get(), waited)) {
                    entries.remove();
                }
            }
        }
    }

    private boolean settled(
            final InteractionHook hook, final Locale locale, final PaymentRequest request, final Duration waited) {
        final PaymentWait wait = PaymentWait.of(request, waited);
        switch (wait) {
            case GONE -> edit(hook, documents.reply(locale, Replies.PURCHASE_GONE));
            case LINK -> edit(hook, link(documents, request, locale));
            case REFUSED -> {
                final TabRefusal refusal = Objects.requireNonNull(request.tabRefusal());
                admin.alert(new Alert(
                        AlertType.PAYMENT,
                        Alert.Level.DOWN,
                        "purchase",
                        TEXTS.alert().linkRefused(),
                        List.of(TEXTS.alert().refused(request.reference(), refusal)),
                        "/payments"));
                edit(
                        hook,
                        documents.reply(
                                locale, new Sent<>(Replies.PURCHASE_REFUSED, new Replies.Refused(refusal.passes()))));
            }
            case GIVE_UP -> {
                edit(
                        hook,
                        documents.reply(
                                locale, new Sent<>(Replies.PURCHASE_SLOW, new Replies.Reference(request.reference()))));
                log.warn("Request {} had no tab after {}", request.reference(), PaymentWait.LIMIT);
            }
            case WAITING -> {}
        }
        return wait.ends();
    }

    static DocumentRenderer.Drawn link(
            final DocumentRenderer documents, final PaymentRequest request, final Locale locale) {
        return documents.render(
                LINK,
                new Link(
                        Money.euroCents(request.amountCents()),
                        Objects.requireNonNull(request.shareUrl()),
                        request.reference(),
                        request.expires()),
                Map.of(),
                locale);
    }

    private void edit(final InteractionHook hook, final DocumentRenderer.Drawn drawn) {
        hook.editOriginal(drawn.edit())
                .queue(ok -> {}, failure -> log.warn("Could not finish a purchase message", failure));
    }

    private void showSummary(final IDeferrableCallback event, final Locale locale, final PaymentRequest request) {
        event.getHook()
                .editOriginal(summary(documents, tiers, request, locale).edit())
                .queue();
    }

    static DocumentRenderer.Drawn summary(
            final DocumentRenderer documents, final Tiers tiers, final PaymentRequest request, final Locale locale) {
        return documents.render(
                SUMMARY,
                new Summary(
                        request.days(),
                        Money.euroCents(request.amountCents() - request.donationCents()),
                        Money.euroCents(request.donationCents()),
                        Money.euroCents(request.amountCents()),
                        request.donationRequested(),
                        Money.euroCents(tiers.donationCents())),
                Map.of(
                        "confirm", new DocumentRenderer.FilledButton(Ids.CONFIRM, ButtonStyle.SUCCESS),
                        "change", new DocumentRenderer.FilledButton(Ids.CHANGE, ButtonStyle.SECONDARY),
                        "donation", new DocumentRenderer.FilledButton(Ids.DONATION, ButtonStyle.SECONDARY)),
                locale);
    }

    private void reply(final IReplyCallback event, final Locale locale, final DocumentSpec<DocumentSpec.None> text) {
        event.reply(documents.reply(locale, text).create()).setEphemeral(true).queue();
    }

    private void fail(
            final IDeferrableCallback event, final Locale locale, final String what, final RuntimeException exception) {
        log.error("Purchase failed while {}", what, exception);
        admin.alert(new Alert(
                AlertType.PAYMENT,
                Alert.Level.DOWN,
                "purchase",
                TEXTS.alert().purchaseFailed(),
                List.of(TEXTS.alert().failedFor(LinkFlow.member(event.getUser()), String.valueOf(exception))),
                "/payments"));
        event.getHook()
                .editOriginal(documents.reply(locale, Replies.PURCHASE_FAILED).edit())
                .queue();
    }
}

package eu.nordtal.season.discordbot.access.discord;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.payment.PaymentRequests;
import eu.nordtal.season.database.payment.TabRefusal;
import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.AccessMessages;
import eu.nordtal.season.discordbot.AdminLog;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.access.payment.Purchases;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
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

    static final Layout CHOOSE = Layout.discord(
            "access",
            "purchase-choose",
            "Purchase",
            List.of(Element.select("days", "access/purchase.choose", "access/purchase.option")),
            Part.text(Line.text("access/purchase.choose")),
            Part.row("days"));

    static final Layout SUMMARY = Layout.discord(
            "access",
            "purchase-summary",
            "Purchase",
            List.of(
                    Element.button("confirm", "access/purchase.button.confirm"),
                    Element.button("change", "access/purchase.button.change"),
                    Element.button("donation", "access/purchase.button.donation.add")),
            Part.text(Line.text("access/purchase.summary"), Line.text("access/purchase.summary.total")),
            Part.row("confirm", "change", "donation"));

    static final Layout SUMMARY_DONATION = Layout.discord(
            "access",
            "purchase-summary-donation",
            "Purchase",
            List.of(
                    Element.button("confirm", "access/purchase.button.confirm"),
                    Element.button("change", "access/purchase.button.change"),
                    Element.button("donation", "access/purchase.button.donation.remove")),
            Part.text(
                    Line.text("access/purchase.summary"),
                    Line.text("access/purchase.summary.donation"),
                    Line.text("access/purchase.summary.total")),
            Part.row("confirm", "change", "donation"));

    static final Layout LINK = Layout.discord(
            "access",
            "purchase-link",
            "Purchase",
            List.of(),
            Part.text(
                    Line.text("access/purchase.link"),
                    Line.text("access/purchase.link.reference"),
                    Line.text("access/purchase.link.ttl")));

    private final Tiers tiers;
    private final Purchases purchases;
    private final PaymentRequests requests;
    private final LayoutRenderer layouts;
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
            final LayoutRenderer layouts,
            final AccessRoles roles,
            final AdminLog admin,
            final Executor executor,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.tiers = tiers;
        this.purchases = purchases;
        this.requests = requests;
        this.layouts = layouts;
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
            reply(event, locale, MESSAGES.purchase().tierGone());
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
        final LayoutRenderer.Drawn drawn = choose(layouts, tiers, locale);
        if (Ids.CHANGE.equals(event.getComponentId())) {
            event.editMessage(drawn.edit()).queue();
        } else {
            event.reply(drawn.create()).setEphemeral(true).queue();
        }
    }

    static LayoutRenderer.Drawn choose(final LayoutRenderer layouts, final Tiers tiers, final Locale locale) {
        final List<LayoutRenderer.Option> options = tiers.all().stream()
                .map(tier -> new LayoutRenderer.Option(
                        String.valueOf(tier.days()),
                        null,
                        MESSAGES.purchase()
                                .option(tier.days(), Money.euroCents(tier.priceCents()))
                                .args(),
                        null))
                .toList();
        return layouts.render(
                CHOOSE,
                LayoutRenderer.Filling.of(Map.of("days", new LayoutRenderer.FilledSelect(Ids.DAYS_SELECT, options))),
                locale);
    }

    private void toggleDonation(final ButtonInteractionEvent event, final Locale locale) {
        final Optional<PaymentRequest> open =
                requests.openOf(DiscordId.of(event.getUser().getId()));
        if (open.isEmpty() || open.get().tab().isPresent()) {
            reply(event, locale, MESSAGES.purchase().gone());
            return;
        }

        final PaymentRequest request = open.get();
        final Optional<Tier> tier = tiers.byDays(request.days());
        if (tier.isEmpty()) {
            reply(event, locale, MESSAGES.purchase().tierGone());
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
            reply(event, locale, MESSAGES.purchase().gone());
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
                            .editOriginal(layouts.reply(
                                            locale,
                                            MESSAGES.purchase().linkSection().pending())
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
            case GONE -> edit(hook, layouts.reply(locale, MESSAGES.purchase().gone()));
            case LINK -> edit(hook, link(layouts, request, locale));
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
                        layouts.reply(locale, MESSAGES.purchase().linkSection().refused(refusal.passes())));
            }
            case GIVE_UP -> {
                edit(
                        hook,
                        layouts.reply(locale, MESSAGES.purchase().linkSection().slow(request.reference())));
                log.warn("Request {} had no tab after {}", request.reference(), PaymentWait.LIMIT);
            }
            case WAITING -> {}
        }
        return wait.ends();
    }

    static LayoutRenderer.Drawn link(final LayoutRenderer layouts, final PaymentRequest request, final Locale locale) {
        return layouts.render(
                LINK,
                LayoutRenderer.Filling.of(
                        Map.of(),
                        MESSAGES.purchase()
                                .link(
                                        Money.euroCents(request.amountCents()),
                                        Objects.requireNonNull(request.shareUrl())),
                        MESSAGES.purchase().linkSection().reference(request.reference()),
                        MESSAGES.purchase().linkSection().ttl(request.expires())),
                locale);
    }

    private void edit(final InteractionHook hook, final LayoutRenderer.Drawn drawn) {
        hook.editOriginal(drawn.edit())
                .queue(ok -> {}, failure -> log.warn("Could not finish a purchase message", failure));
    }

    private void showSummary(final IDeferrableCallback event, final Locale locale, final PaymentRequest request) {
        event.getHook()
                .editOriginal(summary(layouts, tiers, request, locale).edit())
                .queue();
    }

    static LayoutRenderer.Drawn summary(
            final LayoutRenderer layouts, final Tiers tiers, final PaymentRequest request, final Locale locale) {
        final AccessMessages.Purchase purchase = MESSAGES.purchase();
        final List<MessageRef> texts = List.of(
                purchase.summary(request.days(), Money.euroCents(request.amountCents() - request.donationCents())),
                purchase.summarySection().total(Money.euroCents(request.amountCents())),
                purchase.summarySection().donation(Money.euroCents(request.donationCents())),
                purchase.button().donation().add(Money.euroCents(tiers.donationCents())));
        return layouts.render(
                request.donationRequested() ? SUMMARY_DONATION : SUMMARY,
                LayoutRenderer.Filling.of(
                        Map.of(
                                "confirm", new LayoutRenderer.FilledButton(Ids.CONFIRM, ButtonStyle.SUCCESS),
                                "change", new LayoutRenderer.FilledButton(Ids.CHANGE, ButtonStyle.SECONDARY),
                                "donation", new LayoutRenderer.FilledButton(Ids.DONATION, ButtonStyle.SECONDARY)),
                        texts.toArray(MessageRef[]::new)),
                locale);
    }

    private void reply(final IReplyCallback event, final Locale locale, final MessageRef text) {
        event.reply(layouts.reply(locale, text).create()).setEphemeral(true).queue();
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
                .editOriginal(
                        layouts.reply(locale, MESSAGES.purchase().failed()).edit())
                .queue();
    }
}

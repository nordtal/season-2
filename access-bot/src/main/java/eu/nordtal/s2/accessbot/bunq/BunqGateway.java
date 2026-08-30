package eu.nordtal.s2.accessbot.bunq;

import com.bunq.sdk.context.ApiContext;
import com.bunq.sdk.context.ApiEnvironmentType;
import com.bunq.sdk.context.BunqContext;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabApiObject;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabEntryApiObject;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabResultInquiryApiObject;
import com.bunq.sdk.model.generated.endpoint.PaymentApiObject;
import com.bunq.sdk.model.generated.object.AmountObject;
import eu.nordtal.s2.accessbot.config.BotSpec;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the bot does at bunq: create a tab, cancel a tab, ask a tab who paid it, and list
 * recent payments on the account.
 * <p>
 * An instance with its credentials in a field, not a class of statics as in season 1. The static
 * version meant {@code configure()} had to be called before anything else and nothing enforced
 * that, and it made the environment un-switchable at runtime.
 * </p>
 *
 * <h2>Matching a payment</h2>
 * The primary path is {@link #paymentsFor(long)}: a bunq.me tab knows the payments that settled it
 * ({@code BunqMeTabApiObject.get(...).getValue().getResultInquiries()}, each carrying a
 * {@code PaymentApiObject}). That is an exact link with no text parsing at all. The reference in
 * the description is only the fallback, for money that reaches the account outside a tab.
 *
 * <h2>Currency</h2>
 * EUR only. A payment in another currency is not something the "pay what you get" rule can be
 * applied to, so it is refused rather than converted at a rate nobody agreed on.
 */
@Slf4j
public final class BunqGateway {

    private static final String CURRENCY = "EUR";
    private static final String DEFAULT_CONTEXT_FILE = "bunq-config.conf";
    private static final String DEVICE_DESCRIPTION = "nordtal access bot";

    /** The status string bunq's own API uses to close a tab. */
    private static final String STATUS_CANCELLED = "CANCELLED";

    /**
     * bunq renders timestamps as {@code 2026-08-30 14:21:07.123456} in UTC, with no zone in the
     * string. {@code BunqGsonBuilder} parses them with exactly this pattern.
     */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]");

    private final BotSpec config;
    private final long accountId;

    private boolean contextLoaded;

    /**
     * @param config the loaded and validated bot configuration; the account id is known to be
     *               numeric because {@code Configs.bot()} checked it at startup
     */
    public BunqGateway(final BotSpec config) {
        this.config = Objects.requireNonNull(config, "config");
        this.accountId = Long.parseLong(config.bunq().accountId().trim());
    }

    /**
     * Creates a bunq.me tab.
     *
     * @param amountCents what it asks for; the payer can edit this on the bunq.me page, which is
     *                    why nothing downstream trusts it
     * @param description what the payer and we both see - the {@code NT-XXXXXX} reference
     * @return the tab id and the URL to send the payer to
     */
    public Tab createTab(final int amountCents, final String description) {
        loadContext();
        final Long tabId = BunqMeTabApiObject.create(
                new BunqMeTabEntryApiObject(
                        new AmountObject(Money.toDecimalString(amountCents), CURRENCY),
                        description),
                accountId).getValue();

        final BunqMeTabApiObject tab = BunqMeTabApiObject.get(tabId, accountId).getValue();
        return new Tab(tabId, tab.getBunqmeTabShareUrl());
    }

    /**
     * Closes a tab so it can no longer be paid.
     * <p>
     * This is a real call to bunq, not a status flip in our own table. A superseded or expired
     * request whose tab stays live is a URL somebody can still pay, and that payment would arrive
     * against a reference the bot refuses to book automatically.
     * </p>
     *
     * @param tabId the tab
     * @return whether bunq accepted the cancellation
     */
    public boolean cancelTab(final long tabId) {
        loadContext();
        try {
            BunqMeTabApiObject.update(tabId, accountId, STATUS_CANCELLED);
            return true;
        } catch (final RuntimeException exception) {
            // A tab that is already cancelled or already paid answers with an error. Neither is
            // worth failing the caller for - both mean it cannot be paid again.
            log.warn("Could not cancel bunq.me tab {}: {}", tabId, exception.toString());
            return false;
        }
    }

    /**
     * The payments that settled one tab - the exact link between a request and money.
     *
     * @param tabId the tab
     * @return the payments bunq attributes to it, possibly empty
     */
    public List<PaymentApiObject> paymentsFor(final long tabId) {
        loadContext();
        final List<BunqMeTabResultInquiryApiObject> inquiries =
                BunqMeTabApiObject.get(tabId, accountId).getValue().getResultInquiries();
        if (inquiries == null) {
            return List.of();
        }
        return inquiries.stream()
                .map(BunqMeTabResultInquiryApiObject::getPayment)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * The most recent payments on the account, newest first - the fallback path's input.
     *
     * @param count how many to ask for
     * @return the payments
     */
    public List<PaymentApiObject> recentPayments(final int count) {
        loadContext();
        return PaymentApiObject.list(accountId, Map.of("count", String.valueOf(count))).getValue();
    }

    /**
     * @param payment a payment
     * @return its amount in cents, or {@code null} when it is not a positive EUR amount - which is
     *         every outgoing payment and anything in another currency
     */
    public static Integer positiveEuroCents(final PaymentApiObject payment) {
        final AmountObject amount = payment.getAmount();
        if (amount == null || !CURRENCY.equals(amount.getCurrency())) {
            return null;
        }
        try {
            final int cents = Money.toCents(amount.getValue());
            return cents > 0 ? cents : null;
        } catch (final ArithmeticException | NumberFormatException exception) {
            log.warn("Payment {} has an unreadable amount '{}'", payment.getId(), amount.getValue());
            return null;
        }
    }

    /**
     * @param payment a payment
     * @return when bunq says it was created, or {@code null} if that cannot be read - a payment
     *         with no readable timestamp is treated as being before every watermark, so it is
     *         ignored rather than booked
     */
    public static Instant createdAt(final PaymentApiObject payment) {
        final String created = payment.getCreated();
        if (created == null || created.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(created.trim(), TIMESTAMP).toInstant(ZoneOffset.UTC);
        } catch (final DateTimeParseException exception) {
            log.warn("Payment {} has an unreadable creation time '{}'", payment.getId(), created);
            return null;
        }
    }

    // ---------------------------------------------------------------- the API context

    /**
     * Loads or creates the bunq API context, once per process.
     * <p>
     * The context file holds credentials and the installed device key. It belongs to <b>one</b>
     * environment: a context created against PRODUCTION cannot be used against SANDBOX, so
     * switching {@code bunq.environment} also means pointing {@code bunq.context-path} at a fresh
     * file.
     * </p>
     */
    private synchronized void loadContext() {
        if (contextLoaded) {
            return;
        }
        final Path path = contextPath();
        if (Files.notExists(path)) {
            final ApiContext context = ApiContext.create(environment(), config.bunq().apiKey(), DEVICE_DESCRIPTION);
            createParentDirectory(path);
            context.save(path.toString());
            BunqContext.loadApiContext(context);
            log.info("Created a new bunq API context for {} at {}", environment(), path);
        } else {
            BunqContext.loadApiContext(ApiContext.restore(path.toString()));
            log.info("Restored the bunq API context from {}", path);
        }
        contextLoaded = true;
    }

    private ApiEnvironmentType environment() {
        return ApiEnvironmentType.valueOf(config.bunq().environment());
    }

    private Path contextPath() {
        final String configured = config.bunq().contextPath();
        return configured == null || configured.isBlank()
                ? Path.of(DEFAULT_CONTEXT_FILE)
                : Path.of(configured);
    }

    private static void createParentDirectory(final Path path) {
        final Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (final IOException exception) {
            throw new IllegalStateException("Unable to create the bunq context directory: " + parent, exception);
        }
    }

    /**
     * A created bunq.me tab.
     *
     * @param id       the tab id, needed to cancel it and to ask who paid it
     * @param shareUrl the URL the payer is sent to
     */
    public record Tab(long id, String shareUrl) {
    }
}

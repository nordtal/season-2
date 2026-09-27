package eu.nordtal.s2.steward.worker.bunq;

import com.bunq.sdk.context.ApiContext;
import com.bunq.sdk.context.ApiEnvironmentType;
import com.bunq.sdk.context.BunqContext;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabApiObject;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabEntryApiObject;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabResultInquiryApiObject;
import com.bunq.sdk.model.generated.endpoint.PaymentApiObject;
import com.bunq.sdk.model.generated.object.AmountObject;
import eu.nordtal.s2.common.payment.Money;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Everything this network does at bunq, and the only class that talks to a bank.
 *
 * EUR only: another currency is refused rather than converted.
 */
@Slf4j
// No HTTP timeout is set here: the SDK's own ApiClient already bounds every call at 30 seconds.
public final class BunqGateway {

    private static final String CURRENCY = "EUR";
    private static final String DEFAULT_CONTEXT_FILE = "bunq-config.conf";
    private static final String DEVICE_DESCRIPTION = "nordtal steward worker";

    /** The status string bunq's own API uses to close a tab. */
    private static final String STATUS_CANCELLED = "CANCELLED";

    /** bunq's UTC timestamp shape, the same pattern {@code BunqGsonBuilder} parses with. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]");

    private final StewardSpec.BunqSpec config;
    private final long accountId;
    private final boolean configured;

    private boolean contextLoaded;

    /**
     * Creates the gateway from the loaded bunq block.
     *
     * @param config the {@code steward.yml} bunq block, whose account id {@code Configs.steward()} checked is numeric
     */
    public BunqGateway(final StewardSpec.BunqSpec config) {
        this.config = Objects.requireNonNull(config, "config");
        this.configured = isSet(config.apiKey()) && isSet(config.accountId());
        this.accountId = configured ? Long.parseLong(config.accountId().trim()) : 0L;
    }

    /** Returns whether a credential was filled in at all. */
    private static boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Returns whether there is a bunq account behind this at all.
     *
     * @return whether an API key and an account id were both configured
     */
    public boolean configured() {
        return configured;
    }

    /**
     * Returns the one line this container says about bunq on every start, never containing the key.
     *
     * @param poll how often the bank will be asked, for the "on" half
     * @return one line to log, at INFO when {@link #configured()} and WARN when not
     */
    public String startupLine(final Duration poll) {
        if (configured) {
            return "bunq is ON: payments on monetary account " + accountId + " are polled every " + poll.toSeconds()
                    + "s, and this container is the only one that holds the key.";
        }
        return "bunq is OFF: bunq.api-key and bunq.account-id are both empty in steward.yml"
                + " (NORDTAL_STEWARD_BUNQ_API_KEY / NORDTAL_STEWARD_BUNQ_ACCOUNT_ID), so no"
                + " payment link can be created and no payment will ever be noticed. Everything"
                + " else in the network is unaffected. If this deployment used to take money,"
                + " its environment file still says NORDTAL_BOT_BUNQ_* - those are the names"
                + " from before bunq moved into this container.";
    }

    /**
     * Writes {@link #startupLine(Duration)} to this class's log at the level that matches it.
     *
     * @param poll how often the bank will be asked
     */
    public void logStartupLine(final Duration poll) {
        if (configured) {
            log.info(startupLine(poll));
        } else {
            log.warn(startupLine(poll));
        }
    }

    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException(
                    "bunq is not configured: set bunq.api-key and bunq.account-id in steward.yml"
                            + " (NORDTAL_STEWARD_BUNQ_API_KEY / NORDTAL_STEWARD_BUNQ_ACCOUNT_ID)"
                            + " before asking the bank for anything. Nothing here can be answered"
                            + " without them.");
        }
    }

    /**
     * Creates a bunq.me tab.
     *
     * @param amountCents what it asks for; the payer can edit it, so nothing downstream trusts it
     * @param description what the payer and we both see, the {@code NT-XXXXXX} reference
     * @return the tab id and the URL to send the payer to
     */
    public Tab createTab(final int amountCents, final String description) {
        requireConfigured();
        loadContext();
        final Long tabId = BunqMeTabApiObject.create(
                        new BunqMeTabEntryApiObject(
                                new AmountObject(Money.toDecimalString(amountCents), CURRENCY), description),
                        accountId)
                .getValue();

        final BunqMeTabApiObject tab = BunqMeTabApiObject.get(tabId, accountId).getValue();
        return new Tab(tabId, tab.getBunqmeTabShareUrl());
    }

    /**
     * Closes a tab at bunq so it can no longer be paid.
     *
     * @param tabId the tab
     * @return whether bunq accepted the cancellation
     */
    public boolean cancelTab(final long tabId) {
        requireConfigured();
        loadContext();
        try {
            BunqMeTabApiObject.update(tabId, accountId, STATUS_CANCELLED);
            return true;
        } catch (final RuntimeException exception) {
            // A tab already cancelled or paid answers with an error; neither is worth failing the caller for.
            log.warn("Could not cancel bunq.me tab {}: {}", tabId, exception.toString());
            return false;
        }
    }

    /**
     * Returns the payments that settled one tab.
     *
     * @param tabId the tab
     * @return the payments bunq attributes to it, possibly empty
     */
    public List<PaymentApiObject> paymentsFor(final long tabId) {
        requireConfigured();
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
     * Returns the most recent payments on the account, newest first, for the fallback path.
     *
     * @param count how many to ask for
     * @return the payments
     */
    public List<PaymentApiObject> recentPayments(final int count) {
        requireConfigured();
        loadContext();
        return PaymentApiObject.list(accountId, Map.of("count", String.valueOf(count)))
                .getValue();
    }

    /**
     * Returns a payment's amount in cents.
     *
     * @param payment a payment
     * @return the cents, or {@code null} for anything outgoing or not in EUR
     */
    public static @Nullable Integer positiveEuroCents(final PaymentApiObject payment) {
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
     * Returns when bunq says a payment was created.
     *
     * @param payment a payment
     * @return the moment, or {@code null} if unreadable, which puts it before every watermark
     */
    public static @Nullable Instant createdAt(final PaymentApiObject payment) {
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

    /** Loads or creates the bunq API context, once per process. */
    private synchronized void loadContext() {
        if (contextLoaded) {
            return;
        }
        final Path path = contextPath();
        if (Files.notExists(path)) {
            final ApiContext context =
                    ApiContext.create(ApiEnvironmentType.PRODUCTION, config.apiKey(), DEVICE_DESCRIPTION);
            createParentDirectory(path);
            context.save(path.toString());
            BunqContext.loadApiContext(context);
            log.info("Created a new bunq API context at {}", path);
        } else {
            BunqContext.loadApiContext(ApiContext.restore(path.toString()));
            log.info("Restored the bunq API context from {}", path);
        }
        contextLoaded = true;
    }

    private Path contextPath() {
        final String configured = config.contextPath();
        return configured == null || configured.isBlank() ? Path.of(DEFAULT_CONTEXT_FILE) : Path.of(configured);
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
     * @param id the tab id, needed to cancel it and to ask who paid it
     * @param shareUrl the URL the payer is sent to
     */
    public record Tab(long id, String shareUrl) {}
}

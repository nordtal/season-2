package eu.nordtal.season.stewardbunq;

import com.bunq.sdk.context.ApiContext;
import com.bunq.sdk.context.ApiEnvironmentType;
import com.bunq.sdk.context.BunqContext;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabApiObject;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabEntryApiObject;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabResultInquiryApiObject;
import com.bunq.sdk.model.generated.endpoint.PaymentApiObject;
import com.bunq.sdk.model.generated.object.AmountObject;
import eu.nordtal.season.internalapi.BankWire;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Everything this network does at bunq, and the only class that talks to a bank.
 *
 * EUR only: another currency is refused rather than converted, and never reaches steward.
 */
@Slf4j
// No HTTP timeout is set here: the SDK's own ApiClient already bounds every call at 30 seconds.
public final class BunqGateway {

    private static final String CURRENCY = "EUR";
    private static final String DEVICE_DESCRIPTION = "nordtal steward-bunq";

    /** The status string bunq's own API uses to close a tab. */
    private static final String STATUS_CANCELLED = "CANCELLED";

    /** bunq's UTC timestamp shape, the same pattern {@code BunqGsonBuilder} parses with. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]");

    private static final BigDecimal CENTS_PER_EURO = BigDecimal.valueOf(100);

    private final String apiKey;
    private final Path contextPath;
    private final long accountId;
    private final boolean configured;

    private boolean contextLoaded;

    /**
     * Creates the gateway, refusing half a credential and an account id that is not a number.
     *
     * @param contextPath where bunq's session for this device is kept, created on the first call
     */
    public BunqGateway(final String apiKey, final String accountId, final Path contextPath) {
        final boolean key = !apiKey.isBlank();
        final boolean account = !accountId.isBlank();
        if (key != account) {
            throw new IllegalStateException("bunq needs both NORDTAL_STEWARD_BUNQ_API_KEY and"
                    + " NORDTAL_STEWARD_BUNQ_ACCOUNT_ID, or neither; only the "
                    + (key ? "key" : "account id") + " is set.");
        }
        this.apiKey = apiKey.trim();
        this.contextPath = Objects.requireNonNull(contextPath, "contextPath");
        this.configured = key;
        this.accountId = configured ? parseAccount(accountId) : 0L;
    }

    private static long parseAccount(final String accountId) {
        try {
            return Long.parseLong(accountId.trim());
        } catch (final NumberFormatException notANumber) {
            throw new IllegalStateException("NORDTAL_STEWARD_BUNQ_ACCOUNT_ID must be the number of a monetary"
                    + " account, not an IBAN or an alias; it is '" + accountId + "'.");
        }
    }

    /** Returns whether there is an account behind this, and which. */
    public BankWire.Account account() {
        return new BankWire.Account(configured, accountId);
    }

    /** Returns the one line this container says about bunq on every start, never containing the key. */
    String startupLine() {
        if (configured) {
            return "bunq is ON: monetary account " + accountId + " is the one billed and polled, and this container"
                    + " is the only one that holds the key.";
        }
        return "bunq is OFF: NORDTAL_STEWARD_BUNQ_API_KEY and NORDTAL_STEWARD_BUNQ_ACCOUNT_ID are both empty, so"
                + " no payment link can be created and no payment will ever be noticed. Everything else in the"
                + " network is unaffected.";
    }

    /** Writes {@link #startupLine()} at INFO when configured and at WARN when not. */
    public void logStartupLine() {
        if (configured) {
            log.info(startupLine());
        } else {
            log.warn(startupLine());
        }
    }

    private void requireConfigured() {
        if (!configured) {
            throw new NotConfigured();
        }
    }

    /** Thrown for a question to a bank this container has no key for. */
    public static final class NotConfigured extends IllegalStateException {
        NotConfigured() {
            super("bunq is not configured: set NORDTAL_STEWARD_BUNQ_API_KEY and NORDTAL_STEWARD_BUNQ_ACCOUNT_ID"
                    + " on steward-bunq before asking the bank for anything.");
        }
    }

    /**
     * Creates a bunq.me tab.
     *
     * @param amountCents what it asks for; the payer can edit it, so nothing downstream trusts it
     * @param description what the payer and we both see, the {@code NT-XXXXXX} reference
     */
    public BankWire.Tab createTab(final int amountCents, final String description) {
        requireConfigured();
        loadContext();
        final Long tabId = BunqMeTabApiObject.create(
                        new BunqMeTabEntryApiObject(new AmountObject(decimal(amountCents), CURRENCY), description),
                        accountId)
                .getValue();

        final BunqMeTabApiObject tab = BunqMeTabApiObject.get(tabId, accountId).getValue();
        return new BankWire.Tab(tabId, tab.getBunqmeTabShareUrl());
    }

    /** Closes a tab at bunq so it can no longer be paid, and says whether bunq accepted that. */
    public BankWire.Cancelled cancelTab(final long tabId) {
        requireConfigured();
        loadContext();
        try {
            BunqMeTabApiObject.update(tabId, accountId, STATUS_CANCELLED);
            return new BankWire.Cancelled(true);
        } catch (final RuntimeException exception) {
            // A tab already cancelled or paid answers with an error; neither is worth failing the caller for.
            log.warn("Could not cancel bunq.me tab {}: {}", tabId, exception.toString());
            return new BankWire.Cancelled(false);
        }
    }

    /** Returns the incoming EUR payments that settled one tab, possibly none. */
    public List<BankWire.Payment> paymentsFor(final long tabId) {
        requireConfigured();
        loadContext();
        final List<BunqMeTabResultInquiryApiObject> inquiries =
                BunqMeTabApiObject.get(tabId, accountId).getValue().getResultInquiries();
        if (inquiries == null) {
            return List.of();
        }
        return wire(inquiries.stream().map(BunqMeTabResultInquiryApiObject::getPayment));
    }

    /** Returns the incoming EUR payments among the account's latest {@code count}, newest first. */
    public List<BankWire.Payment> recentPayments(final int count) {
        requireConfigured();
        loadContext();
        return wire(PaymentApiObject.list(accountId, Map.of("count", String.valueOf(count))).getValue().stream());
    }

    private static List<BankWire.Payment> wire(final Stream<@Nullable PaymentApiObject> payments) {
        return payments.filter(Objects::nonNull)
                .map(BunqGateway::wire)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Returns a payment as steward sees it, or {@code null} for one without an id, a readable time or euros in. */
    static BankWire.@Nullable Payment wire(final PaymentApiObject payment) {
        final Long id = payment.getId();
        final Integer cents = positiveEuroCents(payment);
        final Instant created = createdAt(payment);
        if (id == null || cents == null || created == null) {
            return null;
        }
        final String description = payment.getDescription() == null ? "" : payment.getDescription();
        return new BankWire.Payment(id, cents, created.toString(), description);
    }

    /** Returns a payment's amount in cents, or {@code null} for anything outgoing or not in EUR. */
    private static @Nullable Integer positiveEuroCents(final PaymentApiObject payment) {
        final AmountObject amount = payment.getAmount();
        if (amount == null || !CURRENCY.equals(amount.getCurrency())) {
            return null;
        }
        try {
            final int cents = cents(amount.getValue());
            return cents > 0 ? cents : null;
        } catch (final ArithmeticException | NumberFormatException exception) {
            log.warn("Payment {} has an unreadable amount '{}'", payment.getId(), amount.getValue());
            return null;
        }
    }

    /** Returns when bunq says a payment was created, or {@code null} if unreadable. */
    private static @Nullable Instant createdAt(final PaymentApiObject payment) {
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

    /** Returns {@code cents} as bunq wants it, e.g. {@code "3.00"}, through BigDecimal and never a double. */
    static String decimal(final int cents) {
        return BigDecimal.valueOf(cents)
                .divide(CENTS_PER_EURO, 2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    /**
     * Returns a decimal amount as bunq returns it, in cents.
     *
     * @throws NumberFormatException if the value is not a decimal number
     */
    static int cents(final String value) {
        return new BigDecimal(value.trim())
                .multiply(CENTS_PER_EURO)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /** Loads or creates the bunq API context, once per process. */
    private synchronized void loadContext() {
        if (contextLoaded) {
            return;
        }
        if (Files.notExists(contextPath)) {
            final ApiContext context = ApiContext.create(ApiEnvironmentType.PRODUCTION, apiKey, DEVICE_DESCRIPTION);
            createParentDirectory(contextPath);
            context.save(contextPath.toString());
            BunqContext.loadApiContext(context);
            log.info("Created a new bunq API context at {}", contextPath);
        } else {
            BunqContext.loadApiContext(ApiContext.restore(contextPath.toString()));
            log.info("Restored the bunq API context from {}", contextPath);
        }
        contextLoaded = true;
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
}

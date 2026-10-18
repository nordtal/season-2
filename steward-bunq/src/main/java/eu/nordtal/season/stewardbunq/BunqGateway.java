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

/** The only class that calls bunq. It handles euros only and refuses any other currency. */
@Slf4j
public final class BunqGateway {

    private static final String CURRENCY = "EUR";
    private static final String DEVICE_DESCRIPTION = "nordtal steward-bunq";

    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]");

    private static final BigDecimal CENTS_PER_EURO = BigDecimal.valueOf(100);

    private final String apiKey;
    private final Path contextPath;
    private final long accountId;
    private final boolean configured;

    private boolean contextLoaded;

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

    public BankWire.Account account() {
        return new BankWire.Account(configured, accountId);
    }

    /** Returns the startup line, which never contains the key. */
    String startupLine() {
        if (configured) {
            return "bunq is ON: monetary account " + accountId + " is the one billed and polled, and this container"
                    + " is the only one that holds the key.";
        }
        return "bunq is OFF: NORDTAL_STEWARD_BUNQ_API_KEY and NORDTAL_STEWARD_BUNQ_ACCOUNT_ID are both empty, so"
                + " no payment link can be created and no payment will ever be noticed. Everything else in the"
                + " network is unaffected.";
    }

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

    public static final class NotConfigured extends IllegalStateException {
        NotConfigured() {
            super("bunq is not configured: set NORDTAL_STEWARD_BUNQ_API_KEY and NORDTAL_STEWARD_BUNQ_ACCOUNT_ID"
                    + " on steward-bunq before asking the bank for anything.");
        }
    }

    /** Creates a bunq.me tab. The payer can edit {@code amountCents}, so no caller trusts it. */
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

    /** Cancels a tab and returns whether bunq accepted that. */
    public BankWire.Cancelled cancelTab(final long tabId) {
        requireConfigured();
        loadContext();
        try {
            BunqMeTabApiObject.update(tabId, accountId, STATUS_CANCELLED);
            return new BankWire.Cancelled(true);
        } catch (final RuntimeException exception) {
            log.warn("Could not cancel bunq.me tab {}: {}", tabId, exception.toString());
            return new BankWire.Cancelled(false);
        }
    }

    /** Returns the incoming euro payments of one tab. */
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

    /** Returns the latest {@code count} incoming euro payments, newest first. */
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

    /** Returns the payment, or {@code null} if it has no id, no readable time or is not incoming euros. */
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

    /** Returns {@code cents} as a decimal string such as {@code "3.00"}. */
    static String decimal(final int cents) {
        return BigDecimal.valueOf(cents)
                .divide(CENTS_PER_EURO, 2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    static int cents(final String value) {
        return new BigDecimal(value.trim())
                .multiply(CENTS_PER_EURO)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /** Loads the bunq API context once per process. */
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

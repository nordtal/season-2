package eu.nordtal.s2.steward.worker.bunq;

import eu.nordtal.s2.common.payment.Money;

import com.bunq.sdk.context.ApiContext;
import com.bunq.sdk.context.ApiEnvironmentType;
import com.bunq.sdk.context.BunqContext;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabApiObject;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabEntryApiObject;
import com.bunq.sdk.model.generated.endpoint.BunqMeTabResultInquiryApiObject;
import com.bunq.sdk.model.generated.endpoint.PaymentApiObject;
import com.bunq.sdk.model.generated.object.AmountObject;
import eu.nordtal.s2.steward.worker.config.StewardSpec;

import lombok.extern.slf4j.Slf4j;

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

/**
 * Everything this network does at bunq: create a tab, cancel a tab, ask a tab who paid it, and list
 * recent payments on the account.
 *
 * <p><b>This is the only class anywhere that talks to a bank, and it lives here rather than in
 * {@code discord-bot} since steward/109.</b> The reason is not tidiness: the bot is a process with a
 * gateway connection to a third party and a permanent invitation for strangers to press its buttons,
 * and the bunq key was sitting in it. The bot now writes a row saying what it wants and reads back
 * what happened, and holds no credential that moves money.</p>
 *
 * <p>A payment is matched primarily through {@link #paymentsFor(long)} - a bunq.me tab knows the
 * payments that settled it, an exact link with no text parsing. The reference in the description is
 * only the fallback, for money that reaches the account outside a tab.</p>
 *
 * <p>EUR only: another currency is refused rather than converted at a rate nobody agreed on.</p>
 */
@Slf4j
// No HTTP timeout is set here because the SDK's own ApiClient bounds every call at 30 seconds
// (connect, read and write), so a bank that stops answering costs the worker thread half a minute.
public final class BunqGateway {

    private static final String CURRENCY = "EUR";
    private static final String DEFAULT_CONTEXT_FILE = "bunq-config.conf";
    private static final String DEVICE_DESCRIPTION = "nordtal steward worker";

    /** The status string bunq's own API uses to close a tab. */
    private static final String STATUS_CANCELLED = "CANCELLED";

    /**
     * bunq renders timestamps as {@code 2026-08-30 14:21:07.123456} in UTC, with no zone in the
     * string. {@code BunqGsonBuilder} parses them with exactly this pattern.
     */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]");

    private final StewardSpec.BunqSpec config;
    private final long accountId;
    private final boolean configured;

    private boolean contextLoaded;

    /**
     * @param config the loaded {@code steward.yml} bunq block; the account id, if there is one, is
     *               known to be numeric because {@code Configs.steward()} checked it at startup
     */
    public BunqGateway(final StewardSpec.BunqSpec config) {
        this.config = Objects.requireNonNull(config, "config");
        this.configured = isSet(config.apiKey()) && isSet(config.accountId());
        this.accountId = configured ? Long.parseLong(config.accountId().trim()) : 0L;
    }

    /**
     * Whether a credential was filled in at all - the same question {@code discord-bot}'s
     * {@code Configured.isSet} asks, written out here because that class is Discord's and this
     * module has no reason to know about it.
     */
    private static boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Whether there is a bunq account behind this at all.
     *
     * <p>A season without one is a season whose network does everything except take money: the
     * roles, the link codes, the hunger games and the update commands are untouched. The caller
     * decides what to do about it - the poll loop is not started at all - because a gateway that
     * quietly answered "no payments" would look exactly like a bank that had nothing new, which is
     * the one thing this must never be mistaken for.</p>
     *
     * <p>Whichever it is, {@code StewardWorker} says so in one line at startup and writes it into
     * {@code bot_setting} for the bot to repeat. That is not decoration: the two variables are
     * deliberately not {@code :?} in {@code compose.yml}, so an environment file carrying the old
     * {@code NORDTAL_BOT_BUNQ_*} names produces a perfectly healthy stack in which no payment is
     * ever noticed again (steward/101).</p>
     *
     * @return whether an API key and an account id were both configured
     */
    public boolean configured() {
        return configured;
    }

    /**
     * The one line this container says about bunq on every start, and the reason it is not
     * negotiable.
     *
     * <h2>What it is for</h2>
     * The two variables behind {@link StewardSpec.BunqSpec#apiKey()} and
     * {@link StewardSpec.BunqSpec#accountId()} are deliberately <b>not</b> {@code :?} in
     * {@code compose.yml}: a season without a bank account is a valid season. So an environment file
     * that still carries the pre-steward/109 names - {@code NORDTAL_BOT_BUNQ_*} rather than
     * {@code NORDTAL_STEWARD_BUNQ_*} - produces a stack where every container is healthy, every log
     * is quiet, nobody can buy anything and <b>no payment is ever noticed again</b>. There is no
     * error to find, because nothing went wrong; there is only an absence. This line is the whole
     * of the evidence, and steward/101 is the checklist that reads it.
     *
     * <h2>It never contains the key</h2>
     * The account id is in it because an id pointed at the wrong account is the other way this goes
     * wrong quietly. The API key is not, and must never be.
     *
     * @param poll how often the bank will be asked, for the "on" half
     * @return one line, ready to log - at INFO when {@link #configured()}, at WARN when not
     */
    public String startupLine(final Duration poll) {
        if (configured) {
            return "bunq is ON: payments on monetary account " + accountId + " are polled every "
                    + poll.toSeconds() + "s, and this container is the only one that holds the key.";
        }
        return "bunq is OFF: bunq.api-key and bunq.account-id are both empty in steward.yml"
                + " (NORDTAL_STEWARD_BUNQ_API_KEY / NORDTAL_STEWARD_BUNQ_ACCOUNT_ID), so no"
                + " payment link can be created and no payment will ever be noticed. Everything"
                + " else in the network is unaffected. If this deployment used to take money,"
                + " its environment file still says NORDTAL_BOT_BUNQ_* - those are the names"
                + " from before bunq moved into this container.";
    }

    /**
     * Writes {@link #startupLine(Duration)} to this class's own log, at the level that matches which
     * of the two sentences it is.
     *
     * <h2>Why the level is here and not at the call site</h2>
     * Because it is half the message. "bunq is OFF" at INFO is a line nobody reads in a container
     * that prints several hundred of them at startup, and the whole point of the sentence is to be
     * found by somebody who has just renamed two variables and wants to know whether it worked. It
     * is also the one thing about this that can be driven from a test without a bank, a database or
     * a deployment: an appender sees the level and the text, both branches, which is what
     * steward/109 asks for instead of a rollout.
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
     * @param amountCents what it asks for; the payer can edit this on the bunq.me page, which is
     *                    why nothing downstream trusts it
     * @param description what the payer and we both see - the {@code NT-XXXXXX} reference
     * @return the tab id and the URL to send the payer to
     */
    public Tab createTab(final int amountCents, final String description) {
        requireConfigured();
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
     * Closes a tab so it can no longer be paid. A real call to bunq, not a status flip in our own
     * table: a superseded request whose tab stays live is a URL somebody can still pay, and that
     * payment would arrive against a reference the bot refuses to book automatically.
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
     * The most recent payments on the account, newest first - the fallback path's input.
     *
     * @param count how many to ask for
     * @return the payments
     */
    public List<PaymentApiObject> recentPayments(final int count) {
        requireConfigured();
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
     * Loads or creates the bunq API context, once per process. The context file holds credentials
     * and the installed device key, and belongs to one environment - there is only
     * {@link ApiEnvironmentType#PRODUCTION}.
     */
    private synchronized void loadContext() {
        if (contextLoaded) {
            return;
        }
        final Path path = contextPath();
        if (Files.notExists(path)) {
            final ApiContext context = ApiContext.create(ApiEnvironmentType.PRODUCTION, config.apiKey(), DEVICE_DESCRIPTION);
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

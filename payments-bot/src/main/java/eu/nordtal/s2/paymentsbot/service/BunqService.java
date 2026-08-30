package eu.nordtal.s2.paymentsbot.service;

import com.bunq.sdk.context.ApiContext;
import com.bunq.sdk.context.ApiEnvironmentType;
import com.bunq.sdk.context.BunqContext;
import com.bunq.sdk.http.BunqResponse;
import com.bunq.sdk.model.generated.endpoint.*;
import com.bunq.sdk.model.generated.object.AmountObject;
import eu.nordtal.s2.paymentsbot.config.BotSpec;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Slf4j
public class BunqService {

    private static final String API_CONTEXT_FILE = "bunq-config.conf";

    private static boolean loaded = false;

    /**
     * Set once at startup by {@code NordTalPayments}. The credentials used to be read from the
     * environment at each use site, which meant nothing checked they were present until the poll
     * loop happened to need them - a missing account id surfaced as a NumberFormatException
     * minutes into a run.
     */
    private static BotSpec config;

    /**
     * Hands this service its credentials. Must be called before anything else here.
     *
     * @param botConfig the validated bot configuration
     */
    public static void configure(final BotSpec botConfig) {
        config = botConfig;
    }

    private static BotSpec config() {
        if (config == null) {
            throw new IllegalStateException("BunqService.configure(...) was never called.");
        }
        return config;
    }

    /** The configured monetary account. Validated at startup, so this cannot fail here. */
    private static long accountId() {
        return Long.parseLong(config().bunq().accountId().trim());
    }

    public static void acc() {
        assureApiLoaded();
        MonetaryAccountApiObject.list().getValue().stream()
                .filter(acc -> acc.getMonetaryAccountJoint() != null && acc.getMonetaryAccountJoint().getDescription().toLowerCase().contains("nordtal"))
                .findFirst()
                .ifPresentOrElse(acc -> log.info("Nordtal acc found: {}", acc.getMonetaryAccountJoint().getId()), () -> log.info("Nordtal acc not found"));
    }

    public static String balanceStr() {
        assureApiLoaded();
        return MonetaryAccountJointApiObject.get(accountId())
                .getValue()
                .getBalance()
                .getValue();
    }

    public static String createPaymentLink(final String userId, final String receiverId, final int euroAmount) {
        assureApiLoaded();
        final BunqResponse<Long> re = BunqMeTabApiObject.create(new BunqMeTabEntryApiObject(
                new AmountObject(String.valueOf(euroAmount), "EUR"),
                userId + ":" + receiverId
        ), accountId());
        final BunqMeTabApiObject entry = BunqMeTabApiObject.get(re.getValue()).getValue();
        return entry.getBunqmeTabShareUrl();
    }

    public static java.util.List<PaymentApiObject> listPayments(final int count) {
        assureApiLoaded();
        return PaymentApiObject.list(accountId(), Map.of("count", String.valueOf(count))).getValue();
    }

    private static void assureApiLoaded() {
        if (loaded) {
            return;
        }
        final Path apiContextPath = resolveApiContextPath();
        if (Files.notExists(apiContextPath)) {
            initApi(apiContextPath);
        } else {
            loadApi(apiContextPath);
        }
        loaded = true;
    }

    private static Path resolveApiContextPath() {
        final String configuredPath = config().bunq().contextPath();
        if (configuredPath == null || configuredPath.isBlank()) {
            return Paths.get(API_CONTEXT_FILE);
        }
        return Paths.get(configuredPath);
    }

    private static void loadApi(final Path apiContextPath) {
        BunqContext.loadApiContext(ApiContext.restore(apiContextPath.toString()));
    }

    private static void initApi(final Path apiContextPath) {
        ApiContext apiContext = ApiContext.create(
                ApiEnvironmentType.PRODUCTION,
                config().bunq().apiKey(),
                "nordtal payments app"
        );
        ensureDirectoryExists(apiContextPath);
        apiContext.save(apiContextPath.toString());
        BunqContext.loadApiContext(apiContext);
    }

    private static void ensureDirectoryExists(final Path apiContextPath) {
        final Path parent = apiContextPath.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create bunq config directory: " + parent, e);
        }
    }

}

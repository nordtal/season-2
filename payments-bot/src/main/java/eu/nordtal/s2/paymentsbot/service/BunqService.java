package eu.nordtal.s2.paymentsbot.service;

import com.bunq.sdk.context.ApiContext;
import com.bunq.sdk.context.ApiEnvironmentType;
import com.bunq.sdk.context.BunqContext;
import com.bunq.sdk.http.BunqResponse;
import com.bunq.sdk.model.generated.endpoint.*;
import com.bunq.sdk.model.generated.object.AmountObject;
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

    public static void acc() {
        assureApiLoaded();
        MonetaryAccountApiObject.list().getValue().stream()
                .filter(acc -> acc.getMonetaryAccountJoint() != null && acc.getMonetaryAccountJoint().getDescription().toLowerCase().contains("nordtal"))
                .findFirst()
                .ifPresentOrElse(acc -> log.info("Nordtal acc found: {}", acc.getMonetaryAccountJoint().getId()), () -> log.info("Nordtal acc not found"));
    }

    public static String balanceStr() {
        assureApiLoaded();
        return MonetaryAccountJointApiObject.get(Long.parseLong(System.getenv("BUNQ_ACCOUNT_ID")))
                .getValue()
                .getBalance()
                .getValue();
    }

    public static String createPaymentLink(final String userId, final String receiverId, final int euroAmount) {
        assureApiLoaded();
        final BunqResponse<Long> re = BunqMeTabApiObject.create(new BunqMeTabEntryApiObject(
                new AmountObject(String.valueOf(euroAmount), "EUR"),
                userId + ":" + receiverId
        ), Long.parseLong(System.getenv("BUNQ_ACCOUNT_ID")));
        final BunqMeTabApiObject entry = BunqMeTabApiObject.get(re.getValue()).getValue();
        return entry.getBunqmeTabShareUrl();
    }

    public static java.util.List<PaymentApiObject> listPayments(final int count) {
        assureApiLoaded();
        return PaymentApiObject.list(Long.parseLong(System.getenv("BUNQ_ACCOUNT_ID")), Map.of("count", String.valueOf(count))).getValue();
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
        final String configuredPath = System.getenv("BUNQ_CONFIG_PATH");
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
                System.getenv("BUNQ_API_KEY"),
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

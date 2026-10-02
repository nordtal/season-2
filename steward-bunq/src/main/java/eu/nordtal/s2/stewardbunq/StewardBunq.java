package eu.nordtal.s2.stewardbunq;

import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.internalapi.BankWire;
import eu.nordtal.s2.internalapi.InternalServer;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only process that holds the bank key, answering steward's questions about the bank and nothing else.
 *
 * It decides nothing: which tab to open, what a payment pays for and when to ask are steward's.
 */
public final class StewardBunq {

    private static final Logger log = LoggerFactory.getLogger(StewardBunq.class);

    /** Where bunq's session for this device lives; the image mounts the context volume there. */
    private static final String DEFAULT_CONTEXT = "/app/bunq/bunq-config.conf";

    /** The most payments one question may ask the bank for. */
    private static final int MAX_RECENT = 200;

    private StewardBunq() {}

    public static void main(final String[] args) {
        final InternalServer server = new InternalServer(BankWire.SERVICE, System::getenv);
        final BunqGateway bank = new BunqGateway(
                server.setting("API_KEY", ""),
                server.setting("ACCOUNT_ID", ""),
                Path.of(server.setting("CONTEXT_PATH", DEFAULT_CONTEXT)));
        // Logged on both branches, so a renamed key cannot turn payments off silently.
        bank.logStartupLine();
        server.serve(BankWire.PORT, NetworkTime.clock(), config -> routes(config, bank));
    }

    /** Every route but health, which the server adds itself. */
    static void routes(final JavalinConfig config, final BunqGateway bank) {
        config.routes.get(BankWire.ACCOUNT, ctx -> ctx.json(bank.account()));
        config.routes.post(BankWire.TABS, ctx -> {
            final BankWire.NewTab asked = InternalServer.body(ctx, BankWire.NewTab.class);
            if (asked == null || asked.amountCents() <= 0 || asked.description() == null) {
                throw new BadRequestResponse("a tab needs a positive amountCents and a description");
            }
            answer(ctx, () -> bank.createTab(asked.amountCents(), asked.description()));
        });
        config.routes.post(BankWire.CANCEL, ctx -> answer(ctx, () -> bank.cancelTab(id(ctx))));
        config.routes.get(BankWire.TAB_PAYMENTS, ctx -> answer(ctx, () -> bank.paymentsFor(id(ctx))));
        config.routes.get(BankWire.RECENT, ctx -> {
            final int count = ctx.queryParamAsClass("count", Integer.class)
                    .check(n -> n > 0 && n <= MAX_RECENT, "between 1 and " + MAX_RECENT)
                    .getOrDefault(50);
            answer(ctx, () -> bank.recentPayments(count));
        });
    }

    /**
     * Answers what the bank said, or what went wrong as plain text.
     *
     * bunq's own words are what steward shows the person waiting, so they travel as the whole body.
     */
    private static void answer(final Context ctx, final Supplier<Object> question) {
        try {
            ctx.json(question.get());
        } catch (final BunqGateway.NotConfigured off) {
            ctx.status(503).result(off.getMessage());
        } catch (final RuntimeException refused) {
            log.warn("bunq refused {} {}: {}", ctx.method(), ctx.path(), refused.toString());
            final String message = refused.getMessage();
            ctx.status(502)
                    .result(
                            message == null || message.isBlank()
                                    ? refused.getClass().getSimpleName()
                                    : message.strip());
        }
    }

    private static long id(final Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}

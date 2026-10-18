package eu.nordtal.season.stewardbunq;

import com.bunq.sdk.exception.ApiException;
import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.common.time.ProcessScheduler;
import eu.nordtal.season.internalapi.BankWire;
import eu.nordtal.season.internalapi.InternalServer;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serves steward's questions about the bank. It decides nothing. */
public final class StewardBunq {

    private static final Logger log = LoggerFactory.getLogger(StewardBunq.class);

    private static final String DEFAULT_CONTEXT = "/app/bunq/bunq-config.conf";

    private static final int MAX_RECENT = 200;

    private StewardBunq() {}

    public static void main(final String[] args) {
        final InternalServer server = new InternalServer(BankWire.SERVICE, System::getenv);
        final BunqGateway bank = new BunqGateway(
                server.setting("API_KEY", ""),
                server.setting("ACCOUNT_ID", ""),
                Path.of(server.setting("CONTEXT_PATH", DEFAULT_CONTEXT)));
        bank.logStartupLine();
        final ProcessScheduler scheduler =
                new ProcessScheduler(BankWire.SERVICE, failure -> log.error("A task of steward-bunq failed", failure));
        server.serve(BankWire.PORT, NetworkTime.clock(), scheduler, config -> routes(config, bank));
    }

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

    private static void answer(final Context ctx, final Supplier<Object> question) {
        try {
            ctx.json(question.get());
        } catch (final BunqGateway.NotConfigured off) {
            ctx.status(503).json(refused(off));
        } catch (final RuntimeException refused) {
            log.warn("bunq refused {} {}: {}", ctx.method(), ctx.path(), refused.toString());
            ctx.status(502).json(refused(refused));
        }
    }

    static BankWire.Refused refused(final RuntimeException failure) {
        final String message = failure.getMessage();
        return new BankWire.Refused(
                failure instanceof ApiException answered ? answered.getResponseCode() : null,
                message == null || message.isBlank() ? failure.getClass().getSimpleName() : message.strip());
    }

    private static long id(final Context ctx) {
        return ctx.pathParamAsClass("id", Long.class).get();
    }
}

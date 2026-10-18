package eu.nordtal.season.steward.auth;

import com.google.gson.JsonElement;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.access.AdminTree;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Grants and revokes admin in the {@link AdminTree} and journals each change with the acting admin. */
public final class AdminApi {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final Logger log = LoggerFactory.getLogger(AdminApi.class);

    private final AdminTree tree;
    private final Function<Context, DiscordAuth.Account> accounts;

    public AdminApi(final AdminTree tree, final Function<Context, DiscordAuth.Account> accounts) {
        this.tree = tree;
        this.accounts = accounts;
    }

    public void grant(final Context ctx) {
        final String target = discordId(ctx).value();
        final DiscordAuth.Account who = accounts.apply(ctx);
        final AdminTree.Grant outcome = tree.grant(who.id(), target);
        switch (outcome) {
            case GRANTED -> {
                Journaling.line(ctx, DiscordId.of(target), null);
                log.info("{} made {} an admin", who.name(), target);
                ctx.json(new Granted(outcome));
            }
            case ACTOR_NOT_ADMIN -> throw new RequestRefused(403, ANSWER.notAdmin());
            case ALREADY_ADMIN -> throw new RequestRefused(409, ANSWER.alreadyAdmin());
            case NOT_A_MEMBER -> throw new RequestRefused(409, ANSWER.notAMember());
            case RATE_LIMITED -> throw new RequestRefused(429, ANSWER.grantsPerHour(AdminTree.GRANTS_PER_HOUR));
        }
    }

    /** The revocation includes every admin below the target. */
    public void revoke(final Context ctx) {
        final String target = discordId(ctx).value();
        final DiscordAuth.Account who = accounts.apply(ctx);
        final AdminTree.Revocation revocation = tree.revoke(who.id(), target);
        switch (revocation.outcome()) {
            case REVOKED -> {
                final List<String> below =
                        revocation.removed().subList(1, revocation.removed().size());
                Journaling.line(ctx, DiscordId.of(target), new JournalDetail.AdminRevoked(below));
                log.info("{} revoked admin from {}", who.name(), revocation.removed());
                ctx.json(revocation);
            }
            case ACTOR_NOT_ADMIN -> throw new RequestRefused(403, ANSWER.notAdmin());
            case SELF -> throw new RequestRefused(409, ANSWER.self());
            case NOT_BELOW -> throw new RequestRefused(403, ANSWER.notBelow());
        }
    }

    private static DiscordId discordId(final Context ctx) {
        final JsonElement value;
        try {
            final JsonElement body = Json.tree(ctx.body());
            value = body.isJsonObject() ? body.getAsJsonObject().get("discordId") : null;
        } catch (final RuntimeException malformed) {
            throw new RequestRefused(400, ANSWER.notJson());
        }
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsString().trim().matches("\\d{1,32}")) {
            throw new BadRequestResponse("discordId is whose admin this is");
        }
        return DiscordId.of(value.getAsString().trim());
    }

    public record Granted(AdminTree.Grant outcome) {}
}

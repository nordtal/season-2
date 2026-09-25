package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.common.access.AdminTree;
import eu.nordtal.s2.common.audit.AuditDirectory;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

/**
 * Granting and revoking admin, the only door there is for either.
 *
 * <p><b>Written here, not asked of the bot</b>, unlike access: the tree is one table and nothing
 * else has to happen for a grant to be true. The Discord admin role follows on its own - the grant
 * notifies {@code nordtal_admin}, and the bot's reconcile adds or removes the role to match.</p>
 *
 * <p>So this side journals, because nobody else will: one {@code GRANT_ADMIN} or
 * {@code REVOKE_ADMIN} line naming the admin who clicked.</p>
 */
final class AdminApi {

    private static final Logger log = LoggerFactory.getLogger(AdminApi.class);

    private final AdminTree tree;
    private final AuditDirectory audit;
    private final Function<Context, DiscordAuth.Account> accounts;

    AdminApi(final @NotNull AdminTree tree, final @NotNull AuditDirectory audit,
             final @NotNull Function<Context, DiscordAuth.Account> accounts) {
        this.tree = tree;
        this.audit = audit;
        this.accounts = accounts;
    }

    /** {@code POST /api/admins/grant} - {@code {discordId}}. */
    void grant(final @NotNull Context ctx) {
        final String target = discordId(ctx);
        final DiscordAuth.Account who = accounts.apply(ctx);
        final AdminTree.Grant outcome = tree.grant(who.id(), target);
        switch (outcome) {
            case GRANTED -> {
                audit.record("GRANT_ADMIN", who.id(), target, null, null);
                log.info("{} made {} an admin", who.name(), target);
                ctx.json(Map.of("outcome", outcome.name()));
            }
            case ACTOR_NOT_ADMIN -> ctx.status(403).json(Map.of("error", "You are not an admin any more."));
            case ALREADY_ADMIN -> ctx.status(409).json(Map.of("error", "They are an admin already."));
            case NOT_A_MEMBER -> ctx.status(409).json(Map.of("error",
                    "Only a member of the Discord server can be made an admin."));
            case RATE_LIMITED -> ctx.status(429).json(Map.of("error", AdminTree.GRANTS_PER_HOUR
                    + " admins were granted in the last hour, by all admins together. Try again later."));
        }
    }

    /** {@code POST /api/admins/revoke} - {@code {discordId}}; takes everybody below them too. */
    void revoke(final @NotNull Context ctx) {
        final String target = discordId(ctx);
        final DiscordAuth.Account who = accounts.apply(ctx);
        final AdminTree.Revocation revocation = tree.revoke(who.id(), target);
        switch (revocation.outcome()) {
            case REVOKED -> {
                final String below = revocation.removed().size() > 1
                        ? "with " + String.join(", ", revocation.removed().subList(1, revocation.removed().size()))
                        : null;
                audit.record("REVOKE_ADMIN", who.id(), target, null, below);
                log.info("{} revoked admin from {}", who.name(), revocation.removed());
                ctx.json(Map.of("outcome", revocation.outcome().name(), "removed", revocation.removed()));
            }
            case ACTOR_NOT_ADMIN -> ctx.status(403).json(Map.of("error", "You are not an admin any more."));
            case SELF -> ctx.status(409).json(Map.of("error", "Nobody can revoke their own admin."));
            case NOT_BELOW -> ctx.status(403).json(Map.of("error",
                    "You can only revoke admins you granted, or who were granted below you."));
        }
    }

    private static String discordId(final Context ctx) {
        final JsonElement value;
        try {
            final JsonElement body = JsonParser.parseString(ctx.body());
            value = body.isJsonObject() ? body.getAsJsonObject().get("discordId") : null;
        } catch (final RuntimeException malformed) {
            throw new BadRequestResponse("The body is not the JSON this endpoint takes.");
        }
        if (value == null || !value.isJsonPrimitive() || !value.getAsString().trim().matches("\\d{1,32}")) {
            throw new BadRequestResponse("discordId is whose admin this is");
        }
        return value.getAsString().trim();
    }
}

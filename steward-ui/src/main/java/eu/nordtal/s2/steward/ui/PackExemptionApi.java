package eu.nordtal.s2.steward.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import eu.nordtal.s2.database.access.PackExemptions;
import eu.nordtal.s2.database.audit.AuditDirectory;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Letting one player through without the resource pack, and taking that back.
 *
 * A change takes effect at the player's next login and is journalled with the admin who clicked.
 */
final class PackExemptionApi {

    private static final Logger log = LoggerFactory.getLogger(PackExemptionApi.class);

    private final PackExemptions exemptions;
    private final AuditDirectory audit;
    private final Function<Context, DiscordAuth.Account> accounts;

    PackExemptionApi(
            final PackExemptions exemptions,
            final AuditDirectory audit,
            final Function<Context, DiscordAuth.Account> accounts) {
        this.exemptions = exemptions;
        this.audit = audit;
        this.accounts = accounts;
    }

    /** {@code POST /api/pack-exemptions/exempt} with {@code {discordId}}. */
    void exempt(final Context ctx) {
        final String target = discordId(ctx);
        final DiscordAuth.Account who = accounts.apply(ctx);
        answer(ctx, exemptions.exempt(who.id(), target), "EXEMPT_PACK", who, target, "They play without it already.");
    }

    /** {@code POST /api/pack-exemptions/enforce} with {@code {discordId}}. */
    void enforce(final Context ctx) {
        final String target = discordId(ctx);
        final DiscordAuth.Account who = accounts.apply(ctx);
        answer(ctx, exemptions.enforce(who.id(), target), "ENFORCE_PACK", who, target, "They get it already.");
    }

    private void answer(
            final Context ctx,
            final PackExemptions.Outcome outcome,
            final String action,
            final DiscordAuth.Account who,
            final String target,
            final String unchanged) {
        switch (outcome) {
            case CHANGED -> {
                audit.record(action, who.id(), target, null, null);
                log.info("{} {} for {}", who.name(), action, target);
                ctx.json(Map.of("outcome", outcome.name()));
            }
            case UNCHANGED -> ctx.status(409).json(Map.of("error", unchanged));
            case ACTOR_NOT_ADMIN -> ctx.status(403).json(Map.of("error", "You are not an admin any more."));
            case UNKNOWN -> ctx.status(404).json(Map.of("error", "Nobody by that Discord id is known."));
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
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsString().trim().matches("\\d{1,32}")) {
            throw new BadRequestResponse("discordId is whose resource pack this is");
        }
        return value.getAsString().trim();
    }
}

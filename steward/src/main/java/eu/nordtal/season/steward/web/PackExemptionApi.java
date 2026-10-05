package eu.nordtal.season.steward.web;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import com.google.gson.JsonElement;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.access.PackExemptions;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Letting one player through without the resource pack, and taking that back.
 *
 * A change takes effect at the player's next login and is journalled with the admin who clicked.
 */
final class PackExemptionApi {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

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
        final String target = discordId(ctx).value();
        final DiscordAuth.Account who = accounts.apply(ctx);
        answer(
                ctx,
                exemptions.exempt(who.id(), target),
                JournalAction.EXEMPT_PACK,
                who,
                target,
                ANSWER.exemptAlready());
    }

    /** {@code POST /api/pack-exemptions/enforce} with {@code {discordId}}. */
    void enforce(final Context ctx) {
        final String target = discordId(ctx).value();
        final DiscordAuth.Account who = accounts.apply(ctx);
        answer(
                ctx,
                exemptions.enforce(who.id(), target),
                JournalAction.ENFORCE_PACK,
                who,
                target,
                ANSWER.enforcedAlready());
    }

    private void answer(
            final Context ctx,
            final PackExemptions.Outcome outcome,
            final JournalAction action,
            final DiscordAuth.Account who,
            final String target,
            final MessageRef unchanged) {
        switch (outcome) {
            case CHANGED -> {
                audit.record(AuditLine.about(
                        action,
                        who.actor(),
                        DiscordId.of(target),
                        action == JournalAction.EXEMPT_PACK
                                ? TEXTS.journal().exemptPack()
                                : TEXTS.journal().enforcePack()));
                log.info("{} {} for {}", who.name(), action, target);
                ctx.json(new Exempted(outcome));
            }
            case UNCHANGED -> throw new RequestRefused(409, unchanged);
            case ACTOR_NOT_ADMIN -> throw new RequestRefused(403, ANSWER.notAdmin());
            case UNKNOWN -> throw new RequestRefused(404, ANSWER.unknownPerson());
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
            throw new BadRequestResponse("discordId is whose resource pack this is");
        }
        return DiscordId.of(value.getAsString().trim());
    }

    /** An exemption or its end that went through; every other outcome answers an error. */
    public record Exempted(PackExemptions.Outcome outcome) {}
}

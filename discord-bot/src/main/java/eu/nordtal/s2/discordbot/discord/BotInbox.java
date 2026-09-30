package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Outcome;
import eu.nordtal.s2.database.inbox.Request;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;

/**
 * Carries out what the bot's inbox holds, since only this process holds a JDA session.
 * The answer is a flat object of strings, which Steward shows; a request that throws is failed by the inbox.
 */
public final class BotInbox implements Inbox.Handler<BotRequest> {

    private final AccessChanges effects;
    private final BiPredicate<String, String> announce;

    /**
     * @param announce posts a text into one language's announcement channel and answers whether it went out
     */
    public BotInbox(final AccessChanges effects, final BiPredicate<String, String> announce) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.announce = Objects.requireNonNull(announce, "announce");
    }

    @Override
    public Outcome handle(final Request<BotRequest> request) {
        final Actor by = Actor.asked(request.actor());
        return switch (request.payload()) {
            case BotRequest.Grant grant -> {
                final Instant until = effects.grant(grant.person(), grant.days(), by);
                yield done("until", until.toString());
            }
            case BotRequest.Revoke revoke -> done("revoked", String.valueOf(effects.revoke(revoke.person(), by)));
            case BotRequest.Unlink unlink -> done("unlinked", String.valueOf(effects.unlink(unlink.person(), by)));
            case BotRequest.Settle settle -> {
                final AccessChanges.Settled settled = effects.settle(settle.reference(), by);
                // `until` is null for both refusals; a surface must tell "booked" from "nothing to book".
                yield done(
                        "outcome",
                        settled.outcome().name(),
                        "days",
                        String.valueOf(settled.days()),
                        "until",
                        settled.until() == null ? null : settled.until().toString(),
                        "was",
                        settled.status());
            }
            case BotRequest.SetPlaytime playtime -> {
                effects.setPlaytime(playtime.person(), playtime.seconds(), by);
                yield done("seconds", String.valueOf(playtime.seconds()));
            }
            case BotRequest.ReloadMessages reload -> {
                if (!effects.reloadMessages()) {
                    // A bundle that no longer parses keeps the running one; the surface must say "not applied".
                    throw new IllegalStateException(
                            "the message bundles could not be re-read; the running ones are unchanged");
                }
                // Names the override keys no bundle declares, which would otherwise do nothing silently.
                yield done("unknown", String.join(",", effects.unknownOverrideKeys()));
            }
            case BotRequest.Announce announcement -> Outcome.done(post(announcement));
        };
    }

    /** Posts every language's text and answers, per language, whether it went out; a missing channel is no fault. */
    private Map<String, String> post(final BotRequest.Announce announcement) {
        final Map<String, String> posted = new LinkedHashMap<>();
        announcement
                .texts()
                .forEach((tag, text) -> posted.put(
                        tag, announce.test(tag, text) ? BotRequest.Announce.POSTED : BotRequest.Announce.NOT_POSTED));
        return posted;
    }

    /** Returns a done outcome whose answer is the flat object {@code key, value, key, value}. */
    private static Outcome done(final @Nullable String... pairs) {
        final Map<String, @Nullable String> answer = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            answer.put(Objects.requireNonNull(pairs[at], "key"), pairs[at + 1]);
        }
        return Outcome.done(answer);
    }
}

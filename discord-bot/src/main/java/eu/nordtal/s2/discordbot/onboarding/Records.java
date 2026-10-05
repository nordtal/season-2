package eu.nordtal.s2.discordbot.onboarding;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/** Reads the language and the zone the record of each member holds, which the roles are compared with. */
final class Records {

    /** One member's record as stored: {@code null} is the network's. */
    record Recorded(@Nullable String language, @Nullable String zone) {

        /** The record of somebody who has none yet. */
        static final Recorded NONE = new Recorded(null, null);

        @Nullable
        String of(final Choices.Kind kind) {
            return kind == Choices.Kind.LANGUAGE ? language : zone;
        }
    }

    private static final String SELECT = "SELECT discord_id, locale, time_zone FROM discord_user";

    private final Jdbi jdbi;

    Records(final Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** Returns every record by Discord id. */
    Map<String, Recorded> all() {
        return jdbi
                .withHandle(handle -> handle.createQuery(SELECT)
                        .map((rows, context) -> Map.entry(
                                rows.getString("discord_id"),
                                new Recorded(rows.getString("locale"), rows.getString("time_zone"))))
                        .list())
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Returns one member's record, or {@link Recorded#NONE}. */
    Recorded of(final String discordId) {
        final Optional<Recorded> found =
                jdbi.withHandle(handle -> handle.createQuery(SELECT + " WHERE discord_id = :id")
                        .bind("id", discordId)
                        .map((rows, context) -> new Recorded(rows.getString("locale"), rows.getString("time_zone")))
                        .findOne());
        return found.orElse(Recorded.NONE);
    }
}

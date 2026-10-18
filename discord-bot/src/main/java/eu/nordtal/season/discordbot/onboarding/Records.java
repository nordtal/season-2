package eu.nordtal.season.discordbot.onboarding;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/** Reads the language and the time zone of each member's record. */
final class Records {

    /** The stored record of one member. {@code null} means the network's value. */
    record Recorded(@Nullable String language, @Nullable String zone) {

        /** The record of a member who has none. */
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

    Recorded of(final String discordId) {
        final Optional<Recorded> found =
                jdbi.withHandle(handle -> handle.createQuery(SELECT + " WHERE discord_id = :id")
                        .bind("id", discordId)
                        .map((rows, context) -> new Recorded(rows.getString("locale"), rows.getString("time_zone")))
                        .findOne());
        return found.orElse(Recorded.NONE);
    }
}

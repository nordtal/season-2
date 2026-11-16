package eu.nordtal.season.discordbot.onboarding;

import java.util.List;
import org.jdbi.v3.core.Jdbi;

/** Remembers every channel and role denial until its removal succeeds. */
final class LanguageDenials {
    record Denial(String language, String category, String channel, String role) {}

    private final Jdbi jdbi;

    LanguageDenials(final Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    List<Denial> all() {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT language, category, channel, role FROM discord_language_category ORDER BY language, category, channel, role")
                .map((row, context) -> new Denial(
                        row.getString("language"),
                        row.getString("category"),
                        row.getString("channel"),
                        row.getString("role")))
                .list());
    }

    void remember(final Denial denial) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO discord_language_category (language, category, channel, role) VALUES (:language, :category, :channel, :role) ON CONFLICT DO NOTHING")
                .bind("language", denial.language())
                .bind("category", denial.category())
                .bind("channel", denial.channel())
                .bind("role", denial.role())
                .execute());
    }

    void forget(final Denial denial) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "DELETE FROM discord_language_category WHERE language = :language AND category = :category AND channel = :channel AND role = :role")
                .bind("language", denial.language())
                .bind("category", denial.category())
                .bind("channel", denial.channel())
                .bind("role", denial.role())
                .execute());
    }
}

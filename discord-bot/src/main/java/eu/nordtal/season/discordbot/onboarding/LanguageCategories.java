package eu.nordtal.season.discordbot.onboarding;

import java.util.List;
import org.jdbi.v3.core.Jdbi;

/** Remembers every category whose denials must be kept or removed, including interrupted changes. */
final class LanguageCategories {
    record Category(String language, String category) {}

    private final Jdbi jdbi;

    LanguageCategories(final Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    List<Category> all() {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT language, category FROM discord_language_category ORDER BY language, category")
                .map((row, context) -> new Category(row.getString("language"), row.getString("category")))
                .list());
    }

    void remember(final Category category) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO discord_language_category (language, category) VALUES (:language, :category) ON CONFLICT DO NOTHING")
                .bind("language", category.language())
                .bind("category", category.category())
                .execute());
    }

    void forget(final Category category) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "DELETE FROM discord_language_category WHERE language = :language AND category = :category")
                .bind("language", category.language())
                .bind("category", category.category())
                .execute());
    }
}

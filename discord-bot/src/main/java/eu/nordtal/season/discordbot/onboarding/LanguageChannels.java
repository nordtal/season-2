package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.discordbot.AlertOnce;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import org.jdbi.v3.core.Jdbi;

/** Keeps other languages out of each language's category and removes denials from its former category. */
final class LanguageChannels {
    private final Jdbi jdbi;
    private final AlertOnce alerts;

    LanguageChannels(final Jdbi jdbi, final AlertOnce alerts) {
        this.jdbi = jdbi;
        this.alerts = alerts;
    }

    void keep(final Guild guild, final GuildLanguages languages, final GuildRoles roles) {
        final Map<String, String> previous = jdbi
                .withHandle(handle -> handle.createQuery("SELECT language, category FROM discord_language_category")
                        .mapToMap()
                        .list())
                .stream()
                .collect(Collectors.toMap(row -> (String) row.get("language"), row -> (String) row.get("category")));
        final List<JournalDetail.ChannelFailure> failed = new ArrayList<>();
        for (final var old : new ArrayList<>(previous.entrySet())) {
            final String current = languages
                    .byTag(old.getKey())
                    .map(GuildLanguages.Language::categoryId)
                    .orElse("");
            if (!old.getValue().equals(current)
                    && update(guild, old.getKey(), old.getValue(), languages, roles, false, failed)) {
                jdbi.useHandle(handle -> handle.createUpdate(
                                "DELETE FROM discord_language_category WHERE language = :language")
                        .bind("language", old.getKey())
                        .execute());
                previous.remove(old.getKey());
            }
        }
        for (final GuildLanguages.Language language : languages.all()) {
            if (language.categoryId().isBlank()
                    || (previous.containsKey(language.tag())
                            && !Objects.equals(previous.get(language.tag()), language.categoryId()))) {
                continue;
            }
            if (update(guild, language.tag(), language.categoryId(), languages, roles, true, failed)) {
                jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO discord_language_category (language, category) VALUES (:language, :category)
                        ON CONFLICT (language) DO UPDATE SET category = EXCLUDED.category
                        """)
                        .bind("language", language.tag())
                        .bind("category", language.categoryId())
                        .execute());
            }
        }
        if (failed.isEmpty()) {
            alerts.clear("language channels");
        } else {
            alerts.raise(
                    "language channels",
                    new Alert(
                            JournalAction.LANGUAGE_NOT_KEPT,
                            Alert.Level.WARN,
                            new JournalDetail.ChannelsNotDenied(
                                    failed.stream().limit(10).toList())));
        }
    }

    private static boolean update(
            final Guild guild,
            final String tag,
            final String category,
            final GuildLanguages languages,
            final GuildRoles roles,
            final boolean deny,
            final List<JournalDetail.ChannelFailure> failed) {
        if (deny && guild.getCategoryById(category) == null) {
            failed.add(new JournalDetail.ChannelFailure(category, "Category missing"));
            return false;
        }
        final int before = failed.size();
        for (final var channel : guild.getChannels(true)) {
            if (!(channel instanceof IPermissionContainer permissions)
                    || !belongs(
                            channel.getId(),
                            channel instanceof ICategorizableChannel child ? child.getParentCategoryId() : null,
                            category)) {
                continue;
            }
            for (final GuildLanguages.Language other : languages.all()) {
                if (other.tag().equals(tag)) {
                    continue;
                }
                final Role role =
                        roles.role(guild, GuildRoles.language(other.tag())).orElse(null);
                if (role == null) {
                    failed.add(new JournalDetail.ChannelFailure(channel.getName(), "Language role missing"));
                    continue;
                }
                try {
                    final var current = permissions.getPermissionOverride(role);
                    if (deny && (current == null || !current.getDenied().contains(Permission.VIEW_CHANNEL))) {
                        permissions
                                .upsertPermissionOverride(role)
                                .deny(Permission.VIEW_CHANNEL)
                                .complete();
                    } else if (!deny && current != null && current.getDenied().contains(Permission.VIEW_CHANNEL)) {
                        permissions
                                .upsertPermissionOverride(role)
                                .clear(Permission.VIEW_CHANNEL)
                                .complete();
                    }
                } catch (final RuntimeException failure) {
                    failed.add(
                            new JournalDetail.ChannelFailure(channel.getName(), String.valueOf(failure.getMessage())));
                }
            }
        }
        return failed.size() == before;
    }

    static boolean belongs(
            final String channel, final @org.jspecify.annotations.Nullable String parent, final String category) {
        return channel.equals(category) || category.equals(parent);
    }
}

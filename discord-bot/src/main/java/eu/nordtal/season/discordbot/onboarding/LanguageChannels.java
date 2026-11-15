package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.discordbot.AlertOnce;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import org.jdbi.v3.core.Jdbi;

/** Keeps other languages out of each language's category and removes denials from its former category. */
final class LanguageChannels {
    private final LanguageCategories categories;
    private final AlertOnce alerts;

    LanguageChannels(final Jdbi jdbi, final AlertOnce alerts) {
        this.categories = new LanguageCategories(jdbi);
        this.alerts = alerts;
    }

    void keep(final Guild guild, final GuildLanguages languages, final GuildRoles roles) {
        final List<JournalDetail.ChannelFailure> failed = new ArrayList<>();
        for (final LanguageCategories.Category old : categories.all()) {
            final String current = languages
                    .byTag(old.language())
                    .map(GuildLanguages.Language::categoryId)
                    .orElse("");
            if (!old.category().equals(current)
                    && update(guild, old.language(), old.category(), languages, roles, false, failed)) {
                categories.forget(old);
            }
        }
        for (final GuildLanguages.Language language : languages.all()) {
            if (language.categoryId().isBlank()) {
                continue;
            }
            categories.remember(new LanguageCategories.Category(language.tag(), language.categoryId()));
            update(guild, language.tag(), language.categoryId(), languages, roles, true, failed);
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

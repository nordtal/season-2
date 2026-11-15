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
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import org.jdbi.v3.core.Jdbi;

/** Keeps other languages out of each language's category and removes denials from its former category. */
final class LanguageChannels {
    private final LanguageDenials denials;
    private final AlertOnce alerts;

    LanguageChannels(final Jdbi jdbi, final AlertOnce alerts) {
        this.denials = new LanguageDenials(jdbi);
        this.alerts = alerts;
    }

    void keep(final Guild guild, final GuildLanguages languages, final GuildRoles roles) {
        final List<JournalDetail.ChannelFailure> failed = new ArrayList<>();
        for (final LanguageDenials.Denial old : denials.all()) {
            final var owner = languages.byTag(old.language());
            final var channel = guild.getGuildChannelById(old.channel());
            final Role role = guild.getRoleById(old.role());
            final boolean wanted = owner.isPresent()
                    && old.category().equals(owner.orElseThrow().categoryId())
                    && channel != null
                    && belongs(
                            channel.getId(),
                            channel instanceof ICategorizableChannel child ? child.getParentCategoryId() : null,
                            old.category())
                    && languages.all().stream()
                            .anyMatch(other -> !other.tag().equals(old.language())
                                    && roles.role(guild, GuildRoles.language(other.tag()))
                                            .map(Role::getId)
                                            .filter(old.role()::equals)
                                            .isPresent());
            if (wanted) {
                continue;
            }
            try {
                if (channel instanceof IPermissionContainer permissions && role != null) {
                    final var current = permissions.getPermissionOverride(role);
                    if (current != null && current.getDenied().contains(Permission.VIEW_CHANNEL)) {
                        permissions
                                .upsertPermissionOverride(role)
                                .clear(Permission.VIEW_CHANNEL)
                                .complete(true);
                    }
                }
                denials.forget(old);
            } catch (final RuntimeException | RateLimitedException failure) {
                failed.add(new JournalDetail.ChannelFailure(old.channel(), String.valueOf(failure.getMessage())));
            }
        }
        for (final GuildLanguages.Language language : languages.all()) {
            if (!language.categoryId().isBlank()) {
                update(guild, language.tag(), language.categoryId(), languages, roles, failed);
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

    private void update(
            final Guild guild,
            final String tag,
            final String category,
            final GuildLanguages languages,
            final GuildRoles roles,
            final List<JournalDetail.ChannelFailure> failed) {
        if (guild.getCategoryById(category) == null) {
            failed.add(new JournalDetail.ChannelFailure(category, "Category missing"));
            return;
        }
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
                    if (current == null || !current.getDenied().contains(Permission.VIEW_CHANNEL)) {
                        denials.remember(new LanguageDenials.Denial(tag, category, channel.getId(), role.getId()));
                        // Discord copies category overwrites to synced children before the next sweep.
                        if (channel.getId().equals(category)) {
                            for (final var candidate : guild.getChannels(true)) {
                                if (candidate instanceof ICategorizableChannel child
                                        && category.equals(child.getParentCategoryId())
                                        && child.isSynced()
                                        && candidate instanceof IPermissionContainer childPermissions) {
                                    final var existing = childPermissions.getPermissionOverride(role);
                                    if (existing == null
                                            || !existing.getDenied().contains(Permission.VIEW_CHANNEL)) {
                                        denials.remember(new LanguageDenials.Denial(
                                                tag, category, candidate.getId(), role.getId()));
                                    }
                                }
                            }
                        }
                        permissions
                                .upsertPermissionOverride(role)
                                .deny(Permission.VIEW_CHANNEL)
                                .complete(true);
                    }
                } catch (final RuntimeException | RateLimitedException failure) {
                    failed.add(
                            new JournalDetail.ChannelFailure(channel.getName(), String.valueOf(failure.getMessage())));
                }
            }
        }
    }

    static boolean belongs(
            final String channel, final @org.jspecify.annotations.Nullable String parent, final String category) {
        return channel.equals(category) || category.equals(parent);
    }
}

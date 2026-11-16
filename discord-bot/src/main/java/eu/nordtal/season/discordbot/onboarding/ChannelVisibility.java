package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.discordbot.AlertOnce;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import java.util.HashSet;
import java.util.Set;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;

/** Reports explicit allows that let protected members see a channel. */
final class ChannelVisibility {
    private final AlertOnce alerts;
    private Set<String> raised = Set.of();

    ChannelVisibility(final AlertOnce alerts) {
        this.alerts = alerts;
    }

    void sweep(
            final Guild guild,
            final GuildLanguages languages,
            final GuildRoles roles,
            final String start,
            final boolean locking) {
        final Set<String> seen = new HashSet<>();
        final Role lock = roles.role(guild, GuildRoles.LOCK).orElse(null);
        for (final var channel : guild.getChannels(true)) {
            if (!(channel instanceof IPermissionContainer permissions)) {
                continue;
            }
            final String parent = channel instanceof ICategorizableChannel child ? child.getParentCategoryId() : null;
            final Set<String> blocked = blocked(
                    guild, languages, roles, lock, locking && !channel.getId().equals(start), channel.getId(), parent);
            for (final var overwrite : permissions.getPermissionOverrides()) {
                if (!overwrite.getAllowed().contains(Permission.VIEW_CHANNEL)) {
                    continue;
                }
                final Role role = overwrite.getRole();
                final Member member = overwrite.getMember();
                final Set<String> holders = role != null
                        ? guild.getMembersWithRoles(role).stream()
                                .filter(candidate -> candidate.hasPermission(permissions, Permission.VIEW_CHANNEL))
                                .map(Member::getId)
                                .collect(java.util.stream.Collectors.toSet())
                        : member == null ? Set.of() : Set.of(member.getId());
                if (!leaks(blocked, holders, role != null && role.equals(guild.getPublicRole()))) {
                    continue;
                }
                final String key = "channel seen/" + channel.getId() + "/" + overwrite.getId();
                seen.add(key);
                alerts.raise(
                        key,
                        new Alert(
                                JournalAction.CHANNEL_SEEN,
                                Alert.Level.WARN,
                                new JournalDetail.ChannelSeen(
                                        "#" + channel.getName(),
                                        role != null ? "@" + role.getName() : "@" + member.getEffectiveName())));
            }
        }
        raised.stream().filter(key -> !seen.contains(key)).forEach(alerts::clear);
        raised = Set.copyOf(seen);
    }

    static boolean leaks(final Set<String> blocked, final Set<String> holders, final boolean everyone) {
        return !everyone && !java.util.Collections.disjoint(blocked, holders);
    }

    private static Set<String> blocked(
            final Guild guild,
            final GuildLanguages languages,
            final GuildRoles roles,
            final @org.jspecify.annotations.Nullable Role lock,
            final boolean locking,
            final String channel,
            final @org.jspecify.annotations.Nullable String parent) {
        final Set<String> blocked = new HashSet<>();
        for (final Member member : guild.getMemberCache().asList()) {
            if (member.getUser().isBot() || member.hasPermission(Permission.ADMINISTRATOR)) {
                continue;
            }
            if (locking && lock != null && member.getRoles().contains(lock)) {
                blocked.add(member.getId());
            }
            for (final GuildLanguages.Language language : languages.all()) {
                if (language.categoryId().isBlank()
                        || !LanguageChannels.belongs(channel, parent, language.categoryId())) {
                    continue;
                }
                for (final GuildLanguages.Language other : languages.all()) {
                    if (!language.tag().equals(other.tag())
                            && roles.role(guild, GuildRoles.language(other.tag()))
                                    .filter(member.getRoles()::contains)
                                    .isPresent()) {
                        blocked.add(member.getId());
                    }
                }
            }
        }
        return blocked;
    }
}

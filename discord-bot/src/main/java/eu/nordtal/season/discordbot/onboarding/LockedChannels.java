package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.database.JournalDetail.ChannelFailure;
import eu.nordtal.season.database.JournalDetail.ChannelsNotClosed;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.discordbot.AlertOnce;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IPermissionHolder;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.jspecify.annotations.Nullable;

/**
 * Sets the permission overwrites so that the lock role sees only the onboarding channel and no other channel.
 *
 * The overwrites of the categories are set before those of the channels.
 */
@Slf4j
final class LockedChannels {

    /** The holders of an overwrite, in the order the overwrites are set. */
    enum Holder {
        BOT,
        LOCK,
        EVERYONE
    }

    enum View {
        OPEN,
        CLOSED
    }

    record Overwrite(Holder holder, View view) {}

    /** The permissions of the lock role in the onboarding channel. */
    private static final EnumSet<Permission> LOCK_OPEN =
            EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);

    /** The permissions of the bot in the onboarding channel. */
    private static final EnumSet<Permission> BOT_OPEN = EnumSet.of(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_ATTACH_FILES,
            Permission.MESSAGE_HISTORY);

    /** The number of channels that the alert names. */
    private static final int NAMED = 10;

    private static final String KEY = "lock channels";

    private final AlertOnce alerts;

    LockedChannels(final AlertOnce alerts) {
        this.alerts = alerts;
    }

    /**
     * Returns the overwrites of a channel, in the order they are set.
     *
     * @param onboarding whether the channel is the onboarding channel
     */
    static List<Overwrite> wanted(final boolean onboarding) {
        if (onboarding) {
            return List.of(
                    new Overwrite(Holder.BOT, View.OPEN),
                    new Overwrite(Holder.LOCK, View.OPEN),
                    new Overwrite(Holder.EVERYONE, View.CLOSED));
        }
        return List.of(new Overwrite(Holder.LOCK, View.CLOSED));
    }

    /** Sets the overwrites of every channel of the guild and raises one alert that names the failed channels. */
    void keep(final Guild guild, final Role lock, final String onboardingChannelId) {
        final List<ChannelFailure> failed = new ArrayList<>();
        guild.getChannels(true).stream()
                .filter(IPermissionContainer.class::isInstance)
                .map(IPermissionContainer.class::cast)
                .sorted(Comparator.comparing(channel -> channel.getType() != ChannelType.CATEGORY))
                .forEach(channel -> keep(channel, lock, onboardingChannelId).ifPresent(failed::add));
        if (failed.isEmpty()) {
            alerts.clear(KEY);
            return;
        }
        alerts.raise(KEY, alert(failed));
    }

    /** Returns an alert that names the first of the failed channels. */
    private static Alert alert(final List<ChannelFailure> failed) {
        return new Alert(
                JournalAction.LOCK_NOT_KEPT,
                Alert.Level.WARN,
                new ChannelsNotClosed(List.copyOf(failed.subList(0, Math.min(NAMED, failed.size())))));
    }

    /** Sets the overwrites of a new channel and raises an alert when that fails. */
    void keepNew(final IPermissionContainer channel, final Role lock, final String onboardingChannelId) {
        keep(channel, lock, onboardingChannelId).ifPresent(failed -> alerts.raise(KEY, alert(List.of(failed))));
    }

    /** Sets the overwrites of one channel in order. Returns the first failure and sets no further overwrite. */
    private Optional<ChannelFailure> keep(
            final IPermissionContainer channel, final Role lock, final String onboardingChannelId) {
        final Guild guild = channel.getGuild();
        for (final Overwrite overwrite : wanted(channel.getId().equals(onboardingChannelId))) {
            final IPermissionHolder holder = switch (overwrite.holder()) {
                case BOT -> guild.getSelfMember();
                case LOCK -> lock;
                case EVERYONE -> guild.getPublicRole();
            };
            final Optional<ChannelFailure> failed = keep(channel, holder, overwrite);
            if (failed.isPresent()) {
                return failed;
            }
        }
        return Optional.empty();
    }

    private static Optional<ChannelFailure> keep(
            final IPermissionContainer channel, final IPermissionHolder holder, final Overwrite overwrite) {
        final EnumSet<Permission> open = overwrite.holder() == Holder.BOT ? BOT_OPEN : LOCK_OPEN;
        final boolean opening = overwrite.view() == View.OPEN;
        final @Nullable PermissionOverride current = channel.getPermissionOverride(holder);
        if (opening ? isOpen(current, open) : isClosed(current)) {
            return Optional.empty();
        }
        final String whom = overwrite.holder().name().toLowerCase(Locale.ROOT);
        try {
            final var _ = (opening
                            ? channel.upsertPermissionOverride(holder).grant(open)
                            : channel.upsertPermissionOverride(holder).deny(Permission.VIEW_CHANNEL))
                    .complete();
            log.info("{} the channel {} to {}", opening ? "Opened" : "Closed", name(channel), whom);
            return Optional.empty();
        } catch (final RuntimeException failure) {
            log.warn("Could not keep the overwrite of {} in {}: {}", whom, name(channel), failure.toString());
            return Optional.of(new ChannelFailure(name(channel), String.valueOf(failure.getMessage())));
        }
    }

    private static boolean isOpen(final @Nullable PermissionOverride current, final EnumSet<Permission> open) {
        return current != null && current.getAllowed().containsAll(open);
    }

    private static boolean isClosed(final @Nullable PermissionOverride current) {
        return current != null
                && current.getDenied().contains(Permission.VIEW_CHANNEL)
                && !current.getAllowed().contains(Permission.VIEW_CHANNEL);
    }

    private static String name(final GuildChannel channel) {
        return "#" + channel.getName();
    }
}

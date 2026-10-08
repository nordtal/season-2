package eu.nordtal.season.discordbot.onboarding;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.discordbot.AlertOnce;
import eu.nordtal.season.messages.MessageRef;
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
 * Keeps the onboarding channel the lock role's alone, and every other category and channel shut to it while it locks.
 *
 * Categories go first and each channel gets the same overwrite, so a channel synced with its category stays synced.
 */
@Slf4j
final class LockedChannels {

    /** Who an overwrite is for, in the order they are set: the bot never shuts itself out of a channel. */
    enum Holder {
        BOT,
        LOCK,
        EVERYONE
    }

    /** Whether an overwrite lets its holder see the channel. */
    enum View {
        OPEN,
        CLOSED
    }

    /** One overwrite the bot keeps on a channel. */
    record Overwrite(Holder holder, View view) {}

    /** What the lock role may do in the onboarding channel: see it and read the message there. */
    private static final EnumSet<Permission> LOCK_OPEN =
            EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);

    /** What the bot needs in the onboarding channel once everyone else is shut out: keep its message there. */
    private static final EnumSet<Permission> BOT_OPEN = EnumSet.of(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_ATTACH_FILES,
            Permission.MESSAGE_HISTORY);

    /** At most this many channels are named in the alert; its title counts them all. */
    private static final int NAMED = 10;

    private static final String KEY = "lock channels";

    private final AlertOnce alerts;

    LockedChannels(final AlertOnce alerts) {
        this.alerts = alerts;
    }

    /**
     * Returns the overwrites a channel is kept at, in the order they are set.
     *
     * @param onboarding whether it is the onboarding channel, which only the lock role sees, the lock on or off
     * @param locking whether the lock role is shut out of every other channel now
     */
    static List<Overwrite> wanted(final boolean onboarding, final boolean locking) {
        if (onboarding) {
            return List.of(
                    new Overwrite(Holder.BOT, View.OPEN),
                    new Overwrite(Holder.LOCK, View.OPEN),
                    new Overwrite(Holder.EVERYONE, View.CLOSED));
        }
        return locking ? List.of(new Overwrite(Holder.LOCK, View.CLOSED)) : List.of();
    }

    /** Keeps every channel of the guild, and says once in the admin channel which ones it could not. */
    void keep(final Guild guild, final @Nullable Role lock, final String onboardingChannelId, final boolean locking) {
        final List<MessageRef> failed = new ArrayList<>();
        guild.getChannels(true).stream()
                .filter(IPermissionContainer.class::isInstance)
                .map(IPermissionContainer.class::cast)
                .sorted(Comparator.comparing(channel -> channel.getType() != ChannelType.CATEGORY))
                .forEach(channel ->
                        keep(channel, lock, onboardingChannelId, locking).ifPresent(failed::add));
        if (failed.isEmpty()) {
            alerts.clear(KEY);
            return;
        }
        alerts.raise(KEY, alert(failed));
    }

    /** Counts every channel that failed and names the first few. */
    private static Alert alert(final List<MessageRef> failed) {
        return new Alert(
                AlertType.BOT,
                Alert.Level.WARN,
                KEY,
                TEXTS.alert().lockNotKept(failed.size()),
                List.copyOf(failed.subList(0, Math.min(NAMED, failed.size()))),
                Onboarding.PAGE);
    }

    /** Shuts a channel just created to the lock role, and says so in the admin channel when it cannot. */
    void keepNew(final IPermissionContainer channel, final Role lock, final String onboardingChannelId) {
        keep(channel, lock, onboardingChannelId, true).ifPresent(failed -> alerts.raise(KEY, alert(List.of(failed))));
    }

    /** Keeps one channel's overwrites in order and returns what failed, stopping at the first failure. */
    private Optional<MessageRef> keep(
            final IPermissionContainer channel,
            final @Nullable Role lock,
            final String onboardingChannelId,
            final boolean locking) {
        final Guild guild = channel.getGuild();
        for (final Overwrite overwrite : wanted(channel.getId().equals(onboardingChannelId), locking)) {
            final @Nullable IPermissionHolder holder = switch (overwrite.holder()) {
                case BOT -> guild.getSelfMember();
                case LOCK -> lock;
                case EVERYONE -> guild.getPublicRole();
            };
            if (holder == null) {
                continue;
            }
            final Optional<MessageRef> failed = keep(channel, holder, overwrite);
            if (failed.isPresent()) {
                return failed;
            }
        }
        return Optional.empty();
    }

    /** Keeps one overwrite and returns what failed, if it did. */
    private static Optional<MessageRef> keep(
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
            return Optional.of(TEXTS.alert().failedIn(name(channel), String.valueOf(failure.getMessage())));
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

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
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the lock role out of every category and channel but the onboarding channel, and in that one.
 *
 * Categories go first and each channel gets the same overwrite, so a channel synced with its category stays synced.
 */
@Slf4j
final class LockedChannels {

    /** What the lock role may do in the onboarding channel: see it and read the message there. */
    private static final EnumSet<Permission> OPEN = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);

    /** At most this many channels are named in the alert; its title counts them all. */
    private static final int NAMED = 10;

    private static final String KEY = "lock channels";

    private final AlertOnce alerts;

    LockedChannels(final AlertOnce alerts) {
        this.alerts = alerts;
    }

    /** Keeps every channel of the guild, and says once in the admin channel which ones it could not. */
    void keep(final Guild guild, final Role lock, final String onboardingChannelId) {
        final List<MessageRef> failed = new ArrayList<>();
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

    /** Keeps a channel just created, and says so in the admin channel when it cannot. */
    void keepNew(final IPermissionContainer channel, final Role lock, final String onboardingChannelId) {
        keep(channel, lock, onboardingChannelId).ifPresent(failed -> alerts.raise(KEY, alert(List.of(failed))));
    }

    /** Keeps one channel and returns what failed, if it did. */
    private Optional<MessageRef> keep(
            final IPermissionContainer channel, final Role lock, final String onboardingChannelId) {
        final boolean open = channel.getId().equals(onboardingChannelId);
        final @Nullable PermissionOverride current = channel.getPermissionOverride(lock);
        if (open ? isOpen(current) : isClosed(current)) {
            return Optional.empty();
        }
        try {
            final var _ = (open
                            ? channel.upsertPermissionOverride(lock).grant(OPEN)
                            : channel.upsertPermissionOverride(lock).deny(Permission.VIEW_CHANNEL))
                    .complete();
            log.info("{} the channel {} to the lock role", open ? "Opened" : "Closed", name(channel));
            return Optional.empty();
        } catch (final RuntimeException failure) {
            log.warn("Could not keep the lock role's overwrite in {}: {}", name(channel), failure.toString());
            return Optional.of(TEXTS.alert().failedIn(name(channel), String.valueOf(failure.getMessage())));
        }
    }

    private static boolean isOpen(final @Nullable PermissionOverride current) {
        return current != null && current.getAllowed().containsAll(OPEN);
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

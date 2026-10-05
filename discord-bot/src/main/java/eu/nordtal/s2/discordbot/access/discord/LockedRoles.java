package eu.nordtal.s2.discordbot.access.discord;

import eu.nordtal.s2.discordbot.roles.GuildRoles;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Takes the access and donor roles when the lock role comes, and gives back what the member is owed when it goes.
 *
 * One serial lane keeps a lock and its lifting in the order they happened.
 */
public final class LockedRoles extends ListenerAdapter {

    private final String guildId;
    private final GuildRoles roles;
    private final AccessRoles access;
    private final Executor lane;

    /**
     * Creates it.
     *
     * @param lane the serial executor every change runs on, off the gateway thread since it reads the database
     */
    public LockedRoles(final String guildId, final GuildRoles roles, final AccessRoles access, final Executor lane) {
        this.guildId = guildId;
        this.roles = roles;
        this.access = access;
        this.lane = lane;
    }

    @Override
    public void onGuildMemberRoleAdd(final GuildMemberRoleAddEvent event) {
        lockChanged(event.getGuild(), event.getMember(), event.getRoles());
    }

    @Override
    public void onGuildMemberRoleRemove(final GuildMemberRoleRemoveEvent event) {
        lockChanged(event.getGuild(), event.getMember(), event.getRoles());
    }

    private void lockChanged(final Guild guild, final Member member, final List<Role> changed) {
        final Optional<String> lock = roles.id(GuildRoles.LOCK);
        if (!guildId.equals(guild.getId())
                || member.getUser().isBot()
                || lock.isEmpty()
                || changed.stream().noneMatch(role -> role.getId().equals(lock.get()))) {
            return;
        }
        final String memberId = member.getId();
        lane.execute(() -> {
            // Read again, so the lane acts on what the member holds by now.
            final Member current = guild.getMemberById(memberId);
            if (current != null) {
                access.applyEntitlement(current);
            }
        });
    }
}

package eu.nordtal.season.discordbot.onboarding;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.DiscordRole;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.discordbot.AlertOnce;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.Configured;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.config.OnboardingSpec;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import eu.nordtal.season.discordbot.roles.Withholding;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.SettingsException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the language and region roles of each member, their record and the lock consistent.
 * A member's change, a new channel, a deleted role, the sweep and a changed setting all run on one lane.
 */
@Slf4j
public final class Onboarding extends ListenerAdapter implements Withholding {

    private static final String WITHOUT_CHANNEL = "onboarding channel";

    private final JDA jda;
    private final String guildId;
    private final GuildLanguages languages;
    private final List<GuildRoles.Wanted> fixed;
    private final Setting<OnboardingSpec> setting;
    private final GuildRoles roles;
    private final AccessDirectory access;
    private final Records records;
    private final AlertOnce alerts;
    private final LockedChannels channels;
    private final OnboardingMessage message;
    private final ManagedMessage managed;
    private final Executor lane;
    private volatile Choices choices;

    /**
     * The lock state.
     *
     * @param role the lock role
     * @param locking whether a member without both choices holds the role
     * @param withoutChannel whether the lock is on and the onboarding channel is not set
     */
    private record Lock(@Nullable Role role, boolean locking, boolean withoutChannel) {}

    /**
     * Creates the onboarding. It uses Discord only after {@link #resolveRoles()}.
     *
     * @param fixed the roles that the bot uses besides the choices
     * @param lane the serial executor that runs every change
     */
    public Onboarding(
            final JDA jda,
            final String guildId,
            final GuildLanguages languages,
            final List<GuildRoles.Wanted> fixed,
            final Setting<OnboardingSpec> setting,
            final GuildRoles roles,
            final AccessDirectory access,
            final Jdbi jdbi,
            final Consumer<Alert> alerts,
            final DiscordRenderer messages,
            final StoredDocuments documents,
            final Executor lane) {
        this.jda = jda;
        this.guildId = guildId;
        this.languages = languages;
        this.fixed = List.copyOf(fixed);
        this.setting = setting;
        this.roles = roles;
        this.access = access;
        this.records = new Records(jdbi);
        this.alerts = new AlertOnce(alerts);
        this.channels = new LockedChannels(this.alerts);
        this.message = new OnboardingMessage(languages, messages, documents);
        this.managed = new ManagedMessage(jda, jdbi);
        this.lane = lane;
        this.choices = Choices.of(languages, setting.get());
    }

    Choices choices() {
        return choices;
    }

    /** Finds, adopts or creates every role the bot uses. It runs once at start, before any code reads a role. */
    public void resolveRoles() {
        final Guild guild = guild();
        if (guild != null) {
            roles.resolve(guild, wanted());
        }
    }

    /** Publishes the messages where members choose and change, then runs the sweep. */
    public void start() {
        lane.execute(() -> {
            publish();
            sweep();
        });
    }

    public void reconcile() {
        lane.execute(this::sweep);
    }

    /** Reloads the onboarding group after a change in Steward. A refused change keeps the loaded values. */
    public void settingsChanged() {
        lane.execute(() -> {
            final boolean wasLocked = setting.get().lock();
            try {
                setting.reload();
            } catch (final SettingsException refused) {
                log.warn(
                        "The onboarding settings were changed but refused, so the last ones stay: {}",
                        refused.getMessage());
                return;
            }
            choices = Choices.of(languages, setting.get());
            publish();
            if (wasLocked && !setting.get().lock()) {
                release();
            }
            sweep();
        });
    }

    private void release() {
        final Guild guild = guild();
        if (guild != null) {
            roles.role(guild, GuildRoles.LOCK)
                    .ifPresent(role -> guild.getMembersWithRoles(role)
                            .forEach(member -> change(guild, member.getId(), role.getId(), false)));
        }
    }

    private void publish() {
        final OnboardingSpec spec = setting.get();
        message.publish(managed, spec.channel(), spec.changeChannel());
    }

    @Override
    public void onGuildMemberJoin(final GuildMemberJoinEvent event) {
        settleLater(event.getGuild(), event.getMember(), List.of());
    }

    @Override
    public void onGuildMemberRoleAdd(final GuildMemberRoleAddEvent event) {
        settleLater(event.getGuild(), event.getMember(), event.getRoles());
    }

    @Override
    public void onGuildMemberRoleRemove(final GuildMemberRoleRemoveEvent event) {
        settleLater(event.getGuild(), event.getMember(), List.of());
    }

    @Override
    public void onRoleDelete(final RoleDeleteEvent event) {
        if (ours(event.getGuild()) && roles.isStored(event.getRole().getId())) {
            log.warn(
                    "The role {} the bot uses was deleted; it is found or created again",
                    event.getRole().getName());
            lane.execute(this::sweep);
        }
    }

    @Override
    public void onChannelCreate(final ChannelCreateEvent event) {
        if (event.isFromGuild() && ours(event.getGuild()) && event.getChannel() instanceof IPermissionContainer) {
            final String channelId = event.getChannel().getId();
            lane.execute(() -> closeNew(channelId));
        }
    }

    /** Reads the roles of the member on the gateway thread, then settles the member on the lane. */
    private void settleLater(final Guild guild, final Member member, final Collection<Role> chosen) {
        if (!ours(guild) || member.getUser().isBot()) {
            return;
        }
        final String memberId = member.getId();
        final Set<String> held = ids(member.getRoles());
        final Set<String> given = ids(chosen);
        lane.execute(() -> {
            final Guild current = guild();
            if (current != null) {
                final var _ = settle(current, lock(current), memberId, held, given, records.of(memberId));
            }
        });
    }

    private void sweep() {
        final Guild guild = guild();
        if (guild == null) {
            return;
        }
        roles.resolve(guild, wanted());
        final Lock lock = lock(guild);
        final String onboardingChannel = setting.get().channel();
        final Role lockRole = lock.role();
        if (lockRole != null
                && Configured.isSet(onboardingChannel)
                && guild.getGuildChannelById(onboardingChannel) != null) {
            channels.keep(guild, lockRole, onboardingChannel);
        }
        final Map<String, Records.Recorded> recorded = records.all();
        int settled = 0;
        for (final Member member : guild.getMemberCache().asList()) {
            if (!member.getUser().isBot()
                    && settle(
                            guild,
                            lock,
                            member.getId(),
                            ids(member.getRoles()),
                            Set.of(),
                            recorded.getOrDefault(member.getId(), Records.Recorded.NONE))) {
                settled++;
            }
        }
        if (settled > 0) {
            log.info("Settled the language, region or lock of {} member(s)", settled);
        }
    }

    private void closeNew(final String channelId) {
        final Guild guild = guild();
        if (guild == null) {
            return;
        }
        final Lock lock = lock(guild);
        final Role lockRole = lock.role();
        if (lockRole != null && guild.getGuildChannelById(channelId) instanceof IPermissionContainer channel) {
            channels.keepNew(channel, lockRole, setting.get().channel());
        }
    }

    /** Settles one member and returns whether anything changed. */
    private boolean settle(
            final Guild guild,
            final Lock lock,
            final String memberId,
            final Set<String> held,
            final Set<String> chosen,
            final Records.Recorded recorded) {
        final Role lockRole = lock.role();
        final Settlement settlement = Settlement.of(
                choices,
                idOf(guild),
                held,
                chosen,
                recorded,
                lockRole == null ? null : lockRole.getId(),
                lock.locking());
        if (settlement.isEmpty()) {
            return false;
        }
        settlement.add().forEach(roleId -> change(guild, memberId, roleId, true));
        settlement.remove().forEach(roleId -> change(guild, memberId, roleId, false));
        settlement.record().forEach(change -> write(DiscordId.of(memberId), change));
        return true;
    }

    private void change(final Guild guild, final String memberId, final String roleId, final boolean given) {
        final Role role = guild.getRoleById(roleId);
        if (role == null) {
            return;
        }
        final DiscordRole kind = kindOf(guild, roleId);
        try {
            (given
                            ? guild.addRoleToMember(UserSnowflake.fromId(memberId), role)
                            : guild.removeRoleFromMember(UserSnowflake.fromId(memberId), role))
                    .complete();
            alerts.clear(kind.name());
            log.info("{} the role {} {} {}", given ? "Gave" : "Took", role.getName(), given ? "to" : "from", memberId);
        } catch (final RuntimeException failure) {
            log.warn("Could not change the role {} of {}: {}", role.getName(), memberId, failure.toString());
            alerts.raise(kind.name(), GuildRoles.notChanged(kind, given, DiscordId.of(memberId), failure));
        }
    }

    private void write(final DiscordId member, final Settlement.Change change) {
        final String value = change.value();
        if (change.kind() == Choices.Kind.LANGUAGE) {
            access.setLocale(member, value == null ? null : Locales.parse(value));
        } else {
            access.setTimeZone(member, value == null ? null : ZoneId.of(value));
        }
    }

    /** Returns whether the member holds the lock role, whether the lock is on or off. */
    @Override
    public boolean withholds(final Member member) {
        if (!ours(member.getGuild()) || member.getUser().isBot()) {
            return false;
        }
        final Role role = roles.role(member.getGuild(), GuildRoles.LOCK).orElse(null);
        return role != null && member.getRoles().contains(role);
    }

    /** Returns the lock state and raises an alert while the lock is on without an onboarding channel. */
    private Lock lock(final Guild guild) {
        final Lock lock = state(guild);
        if (lock.withoutChannel()) {
            alerts.raise(WITHOUT_CHANNEL, new Alert(JournalAction.LOCK_WITHOUT_CHANNEL, Alert.Level.WARN, List.of()));
        } else {
            alerts.clear(WITHOUT_CHANNEL);
        }
        return lock;
    }

    /** Returns the lock state without raising an alert. Any thread may call it. */
    private Lock state(final Guild guild) {
        final OnboardingSpec spec = setting.get();
        final Role role = roles.role(guild, GuildRoles.LOCK).orElse(null);
        if (!spec.lock()) {
            return new Lock(role, false, false);
        }
        if (!Configured.isSet(spec.channel()) || guild.getGuildChannelById(spec.channel()) == null) {
            return new Lock(role, false, true);
        }
        final Function<String, Optional<String>> idOf = idOf(guild);
        return new Lock(
                role,
                role != null && choices.ready(Choices.Kind.LANGUAGE, idOf) && choices.ready(Choices.Kind.REGION, idOf),
                false);
    }

    private DiscordRole kindOf(final Guild guild, final String roleId) {
        if (roles.id(GuildRoles.LOCK).filter(roleId::equals).isPresent()) {
            return DiscordRole.LOCK;
        }
        return choices.among(Choices.Kind.REGION, Set.of(roleId), idOf(guild)).isEmpty()
                ? DiscordRole.LANGUAGE
                : DiscordRole.REGION;
    }

    /** Returns the fixed roles, then the roles of the choices and the lock role. */
    private List<GuildRoles.Wanted> wanted() {
        final List<GuildRoles.Wanted> wanted = new ArrayList<>(fixed);
        wanted.addAll(choices.wanted());
        return wanted;
    }

    private Function<String, Optional<String>> idOf(final Guild guild) {
        return key -> roles.role(guild, key).map(Role::getId);
    }

    private static Set<String> ids(final Collection<Role> held) {
        return held.stream().map(Role::getId).collect(Collectors.toUnmodifiableSet());
    }

    private boolean ours(final Guild guild) {
        return guildId.equals(guild.getId());
    }

    private @Nullable Guild guild() {
        final Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            log.error("Guild {} is not available; the onboarding was not kept", guildId);
        }
        return guild;
    }
}

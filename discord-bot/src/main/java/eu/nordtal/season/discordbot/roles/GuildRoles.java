package eu.nordtal.season.discordbot.roles;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.DiscordRole;
import eu.nordtal.season.discordbot.AlertOnce;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.value.Mention;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.jdbi.v3.core.Jdbi;

/**
 * Finds or creates every Discord role the bot uses, and then follows the role by its stored id.
 *
 * The bot looks for a role by name again only when the role is gone. When two roles have the name, it takes neither.
 */
@Slf4j
public final class GuildRoles {

    public static final String ACCESS = "access";

    public static final String DONOR = "donor";

    public static final String ADMIN = "admin";

    /** The role of a member who has not chosen a language and a region. */
    public static final String LOCK = "lock";

    /** The page in Steward that a role alert opens. */
    private static final String PAGE = "/access";

    /** A role the bot uses, with the key it is stored under and the name it is found by. */
    public record Wanted(String key, String name) {}

    /** The roles of the guild. The bot uses JDA's guild and a test uses a map. */
    interface RoleList {

        boolean has(String roleId);

        /** Returns the ids of the roles with exactly this name that a member can be given, in the guild's order. */
        List<String> named(String name);

        /** Creates a role without permissions and returns its id. */
        String create(String name);
    }

    private final DiscordRoleDao dao;
    private final AlertOnce alerts;
    private final Map<String, String> stored = new ConcurrentHashMap<>();

    GuildRoles(final DiscordRoleDao dao, final Consumer<Alert> alerts) {
        this.dao = dao;
        this.alerts = new AlertOnce(alerts);
        stored.putAll(dao.all());
    }

    public static GuildRoles stored(final Jdbi jdbi, final Consumer<Alert> alerts) {
        return new GuildRoles(jdbi.onDemand(DiscordRoleDao.class), alerts);
    }

    public static String language(final String tag) {
        return "language/" + tag;
    }

    /** Returns the key of the role of a region. The key holds the zone, so renaming the region keeps the role. */
    public static String region(final String zone) {
        return "region/" + zone;
    }

    /**
     * Finds, adopts or creates every wanted role that the guild does not have.
     *
     * It waits for Discord, so it must not run on a gateway thread.
     */
    public void resolve(final Guild guild, final Collection<Wanted> wanted) {
        resolve(new JdaRoles(guild), wanted);
    }

    synchronized void resolve(final RoleList guild, final Collection<Wanted> wanted) {
        for (final Wanted role : wanted) {
            resolve(guild, role);
        }
    }

    private void resolve(final RoleList guild, final Wanted wanted) {
        final String current = stored.get(wanted.key());
        if (current != null && guild.has(current)) {
            alerts.clear(wanted.key());
            return;
        }
        // A role that another key stores belongs to that key.
        final Set<String> taken = new HashSet<>(stored.values());
        final List<String> named = guild.named(wanted.name()).stream()
                .filter(id -> !taken.contains(id))
                .toList();
        if (named.size() > 1) {
            alertOnce(
                    wanted.key(),
                    TEXTS.alert().roleAmbiguous(wanted.name()),
                    List.of(TEXTS.alert().noneAdopted()));
            return;
        }
        if (named.size() == 1) {
            store(wanted.key(), named.getFirst());
            log.info("Took the role {} ({}) for {}", wanted.name(), named.getFirst(), wanted.key());
            return;
        }
        final String created;
        try {
            created = guild.create(wanted.name());
        } catch (final RuntimeException failure) {
            alertOnce(
                    wanted.key(),
                    TEXTS.alert().roleNotCreated(wanted.name()),
                    List.of(TEXTS.alert().words(String.valueOf(failure.getMessage()))));
            return;
        }
        store(wanted.key(), created);
        log.info("Created the role {} ({}) for {}", wanted.name(), created, wanted.key());
    }

    private void store(final String key, final String roleId) {
        dao.store(key, roleId);
        stored.put(key, roleId);
        alerts.clear(key);
    }

    /** Returns the role stored for {@code key} if the guild has it. It reads only the cache. */
    public Optional<Role> role(final Guild guild, final String key) {
        final String id = stored.get(key);
        return id == null ? Optional.empty() : Optional.ofNullable(guild.getRoleById(id));
    }

    /** Returns the stored role id of {@code key}, also when the guild no longer has the role. */
    public Optional<String> id(final String key) {
        return Optional.ofNullable(stored.get(key));
    }

    public boolean isStored(final String roleId) {
        return stored.containsValue(roleId);
    }

    /** Returns the alert for a role that Discord did not give to a member or take from a member. */
    public static Alert notChanged(
            final DiscordRole role, final boolean given, final DiscordId member, final Throwable failure) {
        return new Alert(
                AlertType.BOT,
                Alert.Level.WARN,
                "role",
                TEXTS.alert().roleNotChanged(role, given),
                List.of(TEXTS.alert().failedFor(Mention.of(member), String.valueOf(failure.getMessage()))),
                PAGE);
    }

    private void alertOnce(final String key, final MessageRef title, final List<MessageRef> detail) {
        if (!alerts.raise(key, new Alert(AlertType.BOT, Alert.Level.WARN, "role " + key, title, detail, PAGE))) {
            log.debug("Still no role for {}", key);
        }
    }

    private record JdaRoles(Guild guild) implements RoleList {

        @Override
        public boolean has(final String roleId) {
            return guild.getRoleById(roleId) != null;
        }

        @Override
        public List<String> named(final String name) {
            return guild.getRolesByName(name, false).stream()
                    .filter(role -> !role.isManaged() && !role.isPublicRole())
                    .map(Role::getId)
                    .toList();
        }

        @Override
        public String create(final String name) {
            return guild.createRole()
                    .setName(name)
                    .setPermissions(0L)
                    .setMentionable(false)
                    .complete()
                    .getId();
        }
    }
}

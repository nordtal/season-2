package eu.nordtal.s2.discordbot.roles;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.alert.DiscordRole;
import eu.nordtal.s2.discordbot.AlertOnce;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.value.Mention;
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
 * Every Discord role the bot uses: taken by its name from Steward, or created, and then followed by its stored id.
 *
 * Only a role that is gone is looked up by name again; two roles of the name are an admin's question, so neither is.
 */
@Slf4j
public final class GuildRoles {

    /** The role a grant of access gives. */
    public static final String ACCESS = "access";

    /** The role a donation gives. */
    public static final String DONOR = "donor";

    /** The role every admin carries. */
    public static final String ADMIN = "admin";

    /** The role a member holds until they chose a language and a region. */
    public static final String LOCK = "lock";

    /** The page in Steward an alert about a role opens. */
    private static final String PAGE = "/access";

    /** One role the bot uses: the key it is stored under and the name it is found by. */
    public record Wanted(String key, String name) {}

    /** The guild's roles as this reads and makes them: JDA's guild in the bot, a map in a test. */
    interface RoleList {

        boolean has(String roleId);

        /** Returns every role of exactly this name a member can be given, in the guild's order. */
        List<String> named(String name);

        /** Creates a role of this name that grants nothing and returns its id. */
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

    /** Returns the roles stored in the database behind {@code jdbi}, raising its alerts through {@code alerts}. */
    public static GuildRoles stored(final Jdbi jdbi, final Consumer<Alert> alerts) {
        return new GuildRoles(jdbi.onDemand(DiscordRoleDao.class), alerts);
    }

    /** Returns the key of a language's role. */
    public static String language(final String tag) {
        return "language/" + tag;
    }

    /** Returns the key of a region's role, which is its zone, so renaming the region keeps its role. */
    public static String region(final String zone) {
        return "region/" + zone;
    }

    /**
     * Finds, adopts or creates every wanted role that has none in the guild.
     *
     * It may wait for Discord, so it runs on the scheduler, never on a gateway thread.
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
        // A role another key already stands for is that key's, whatever it is called.
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

    /** Returns whether a role is stored for {@code key}, whether or not the guild still has it. */
    public boolean stores(final String key) {
        return stored.containsKey(key);
    }

    /** Stores {@code roleId} for {@code key} without looking at its name. */
    public synchronized void adopt(final String key, final String roleId) {
        store(key, roleId);
    }

    private void store(final String key, final String roleId) {
        dao.store(key, roleId);
        stored.put(key, roleId);
        alerts.clear(key);
    }

    /** Returns the role stored for {@code key} if the guild has it; reads the cache only. */
    public Optional<Role> role(final Guild guild, final String key) {
        final String id = stored.get(key);
        return id == null ? Optional.empty() : Optional.ofNullable(guild.getRoleById(id));
    }

    /** Returns the stored role id of {@code key}, whether or not the guild still has it. */
    public Optional<String> id(final String key) {
        return Optional.ofNullable(stored.get(key));
    }

    /** Returns whether {@code roleId} is a role this stores, so its deletion means looking again. */
    public boolean isStored(final String roleId) {
        return stored.containsValue(roleId);
    }

    /** Returns the alert for a role Discord did not give a member or take from them. */
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

    /** The roles of a guild JDA sees. */
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

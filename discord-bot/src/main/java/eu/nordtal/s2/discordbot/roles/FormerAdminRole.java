package eu.nordtal.s2.discordbot.roles;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Guild;
import org.jspecify.annotations.Nullable;

/**
 * The one-time adoption of the admin role configured before the bot found its roles by name.
 *
 * A start that stores no admin role takes the one {@value #VARIABLE} names; it goes in the release after.
 */
@Slf4j
public final class FormerAdminRole {

    /** Where the admin role's id came from. */
    static final String VARIABLE = "NORDTAL_ACCESS_ROLES_ADMIN";

    private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{1,20}");

    private FormerAdminRole() {}

    /** Stores the configured admin role as the bot's, once, if the guild has it and no admin role is stored. */
    public static void adopt(final Guild guild, final GuildRoles roles) {
        toAdopt(
                        roles.stores(GuildRoles.ADMIN),
                        System.getenv(VARIABLE),
                        id -> guild.getRoleById(id) != null && !roles.isStored(id))
                .ifPresent(id -> {
                    roles.adopt(GuildRoles.ADMIN, id);
                    log.info("Took the admin role {} from {}; it is followed by its id from now on", id, VARIABLE);
                });
    }

    /** Returns the id to take: a stored admin role, an unset variable or a role that is no option take nothing. */
    static Optional<String> toAdopt(
            final boolean stored, final @Nullable String configured, final Predicate<String> exists) {
        if (stored
                || configured == null
                || !SNOWFLAKE.matcher(configured.strip()).matches()) {
            return Optional.empty();
        }
        final String id = configured.strip();
        return exists.test(id) ? Optional.of(id) : Optional.empty();
    }
}

package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.discordbot.AlertOnce;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.roles.DiscordRoleDao;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.PermissionOverrideAction;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

class LanguageChannelsIntegrationTest {
    private final Jdbi jdbi = Jdbis.over(TestDatabase.fresh().dataSourceAs(DatabaseRole.DISCORD_BOT));
    private final Map<String, Set<String>> denied = new HashMap<>();
    private String parent = "10";
    private boolean interrupted;
    private boolean rateLimited;
    private int completed;
    private final Role english = proxy(Role.class, (name, args) -> name.equals("getId") ? "2" : null);
    private final Role german = proxy(Role.class, (name, args) -> name.equals("getId") ? "1" : null);
    private final TextChannel channel = proxy(TextChannel.class, (name, args) -> switch (name) {
        case "getId", "getName" -> "11";
        case "getParentCategoryId" -> parent;
        case "getPermissionOverride" -> override("11", (Role) args[0]);
        case "upsertPermissionOverride" -> action("11", (Role) args[0]);
        default -> null;
    });
    private final Category category = proxy(Category.class, (name, args) -> switch (name) {
        case "getId", "getName" -> "10";
        case "getPermissionOverride" -> override("10", (Role) args[0]);
        case "upsertPermissionOverride" -> action("10", (Role) args[0]);
        default -> null;
    });
    private final Guild guild = proxy(Guild.class, (name, args) -> switch (name) {
        case "getChannels" -> List.of(category, channel);
        case "getCategoryById" -> category;
        case "getGuildChannelById" -> args[0].equals("11") ? channel : category;
        case "getRoleById" -> args[0].equals("2") ? english : german;
        default -> null;
    });

    @Test
    void aMovedChannelLosesItsDenialWhenTheCategoryChanges() {
        keep("10");
        assertTrue(denied.get("11").contains("2"));
        parent = "20";
        keep("30");
        assertFalse(denied.get("11").contains("2"));
    }

    @Test
    void aRestartResumesAnInterruptedCleanupIncludingARemovedLanguage() {
        keep("10");
        parent = "20";
        interrupted = true;
        assertThrows(AssertionError.class, () -> keep(""));
        assertFalse(denied.get("10").contains("2"));
        assertEquals(1, new LanguageDenials(jdbi).all().size());
        interrupted = false;
        final var onlyEnglish = GuildLanguages.of(List.of(language("en", "")), new Languages(List.of("en")));
        new LanguageChannels(jdbi, new AlertOnce(alert -> {})).keep(guild, onlyEnglish, roles());
        assertFalse(denied.get("11").contains("2"));
        assertFalse(denied.get("10").contains("2"));
    }

    @Test
    void rateLimitedCleanupKeepsItsWorkForTheNextSweepAndUsesTheRateLimitQueue() {
        keep("10");
        rateLimited = true;
        keep("");
        assertTrue(denied.get("11").contains("2"));
        assertEquals(2, new LanguageDenials(jdbi).all().size());
        rateLimited = false;
        keep("");
        assertFalse(denied.get("11").contains("2"));
        assertEquals(4, completed);
        assertTrue(new LanguageDenials(jdbi).all().isEmpty());
    }

    @Test
    void removingTheDeniedLanguageTakesBackItsDenials() {
        new LanguageChannels(jdbi, new AlertOnce(alert -> {}))
                .keep(
                        guild,
                        GuildLanguages.of(
                                List.of(language("en", "10"), language("de", "")), new Languages(List.of("en", "de"))),
                        roles());
        assertTrue(denied.get("11").contains("1"));
        final var onlyEnglish = GuildLanguages.of(List.of(language("en", "10")), new Languages(List.of("en")));
        new LanguageChannels(jdbi, new AlertOnce(alert -> {})).keep(guild, onlyEnglish, roles());
        assertFalse(denied.get("11").contains("1"));
        assertTrue(new LanguageDenials(jdbi).all().isEmpty());
    }

    @Test
    void aDenialThatTheBotDidNotWriteIsLeftAlone() {
        denied.put("11", new HashSet<>(Set.of("2")));
        keep("10");
        keep("");
        assertTrue(denied.get("11").contains("2"));
        assertTrue(new LanguageDenials(jdbi).all().isEmpty());
    }

    private void keep(final String selected) {
        new LanguageChannels(jdbi, new AlertOnce(alert -> {}))
                .keep(
                        guild,
                        GuildLanguages.of(
                                List.of(language("en", ""), language("de", selected)),
                                new Languages(List.of("en", "de"))),
                        roles());
    }

    private GuildRoles roles() {
        jdbi.onDemand(DiscordRoleDao.class).store(GuildRoles.language("en"), "2");
        jdbi.onDemand(DiscordRoleDao.class).store(GuildRoles.language("de"), "1");
        return GuildRoles.stored(jdbi, alert -> {});
    }

    private static GuildLanguages.Language language(final String tag, final String category) {
        return new GuildLanguages.Language(tag, tag, "", "", "", "", "", category, "");
    }

    private PermissionOverride override(final String id, final Role role) {
        final var roles = denied.computeIfAbsent(id, key -> new HashSet<>());
        return roles.contains(role.getId())
                ? proxy(
                        PermissionOverride.class,
                        (name, args) -> name.equals("getDenied") ? EnumSet.of(Permission.VIEW_CHANNEL) : null)
                : null;
    }

    private PermissionOverrideAction action(final String id, final Role role) {
        final boolean[] clear = {false};
        return proxy(PermissionOverrideAction.class, (name, args) -> {
            if (name.equals("deny") || name.equals("clear")) {
                clear[0] = name.equals("clear");
                return actionStep(id, role, clear[0]);
            }
            throw new AssertionError(name);
        });
    }

    private PermissionOverrideAction actionStep(final String id, final Role role, final boolean clear) {
        return proxy(PermissionOverrideAction.class, (name, args) -> {
            assertEquals("complete", name);
            assertTrue(
                    args == null || args.length == 0 || Boolean.TRUE.equals(args[0]),
                    "JDA must queue rate-limited requests");
            if (clear && interrupted && id.equals("11")) throw new AssertionError("process interrupted");
            if (clear && rateLimited) throw new IllegalStateException("rate limited");
            if (clear) denied.get(id).remove(role.getId());
            else denied.computeIfAbsent(id, key -> new HashSet<>()).add(role.getId());
            completed++;
            return null;
        });
    }

    private interface Call {
        Object invoke(String name, Object[] args);
    }

    private static <T> T proxy(final Class<T> type, final Call call) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (object, method, args) -> call.invoke(method.getName(), args)));
    }
}

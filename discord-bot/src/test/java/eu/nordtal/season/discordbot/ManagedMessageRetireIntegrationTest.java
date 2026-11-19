package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

class ManagedMessageRetireIntegrationTest {

    private final Jdbi jdbi = Jdbis.over(TestDatabase.fresh().dataSourceAs(DatabaseRole.DISCORD_BOT));
    private final ManagedMessageDao dao = jdbi.onDemand(ManagedMessageDao.class);
    private final List<String> deleted = new ArrayList<>();
    private boolean refuses;
    private boolean hidden;

    @Test
    void aRetiredMessageIsDeletedFromItsChannelAndForgotten() {
        dao.remember("GUIDE_EN", "11", "500");
        dao.remember("RULES_EN", "11", "400");

        new ManagedMessage(jda(), jdbi).retire("GUIDE_EN");

        assertAll(
                () -> assertEquals(List.of("500"), deleted),
                () -> assertEquals(Optional.empty(), dao.channelOf("GUIDE_EN")),
                () -> assertEquals(Optional.of("400"), dao.messageIdOf("RULES_EN", "11")));
    }

    @Test
    void aMessageDiscordRefusesToDeleteIsKeptForTheNextStart() {
        dao.remember("GUIDE_EN", "11", "500");
        refuses = true;

        new ManagedMessage(jda(), jdbi).retire("GUIDE_EN");

        assertEquals(Optional.of("500"), dao.messageIdOf("GUIDE_EN", "11"));
    }

    @Test
    void aMessageInAChannelTheBotCannotSeeIsKeptForTheNextStart() {
        dao.remember("GUIDE_EN", "11", "500");
        hidden = true;

        new ManagedMessage(jda(), jdbi).retire("GUIDE_EN");

        assertAll(
                () -> assertEquals(List.of(), deleted),
                () -> assertEquals(Optional.of("500"), dao.messageIdOf("GUIDE_EN", "11")));
    }

    @Test
    void aKindNeverPostedIsLeftAlone() {
        new ManagedMessage(jda(), jdbi).retire("GUIDE_EN");

        assertEquals(List.of(), deleted);
    }

    private JDA jda() {
        final AuditableRestAction<?> delete = proxy(AuditableRestAction.class, (name, args) -> {
            if (name.equals("complete")) {
                if (refuses) {
                    throw new IllegalStateException("missing access");
                }
                return null;
            }
            return null;
        });
        final TextChannel channel = proxy(TextChannel.class, (name, args) -> switch (name) {
            case "getId" -> "11";
            case "deleteMessageById" -> {
                deleted.add(String.valueOf(args[0]));
                yield delete;
            }
            default -> null;
        });
        return proxy(JDA.class, (name, args) -> name.equals("getChannelById") && !hidden ? channel : null);
    }

    private interface Answer {
        Object answer(String name, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final Answer answer) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (self, method, args) -> answer.answer(method.getName(), args));
    }
}

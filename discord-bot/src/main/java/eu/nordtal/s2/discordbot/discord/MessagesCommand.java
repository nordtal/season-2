package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.message.Messages;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * {@code /messages reload} - the same command the four plugins have, on the bot.
 *
 * <h2>Why the bot needs one at all, when a container restarts in seconds</h2>
 * Because a restart is not free here: it re-publishes nothing, but it does drop every in-flight
 * interaction, re-runs the guild reconcile and re-opens the gateway session, and the one moment
 * somebody wants to fix a wording is the moment people are reading it. The bundle in the jar stays
 * the place a wording change belongs; this is for the override on top of it.
 *
 * <h2>What it does not touch</h2>
 * {@code access.yml}, {@code bot.yml} and {@code database.yml}. Prices, role ids and the connection
 * pool are read once and wired into objects that exist for the life of the process - and a price
 * that changed under a half-finished purchase is worse than a restart.
 */
@Slf4j
public final class MessagesCommand extends ListenerAdapter {

    private static final String NOT_AN_ADMIN =
            "Only an admin can reload the messages.";

    private final Messages messages;
    private final ExecutorService executor;
    private final AdminFlagDao dao;

    public MessagesCommand(final Messages messages, final Jdbi jdbi, final ExecutorService executor) {
        this.messages = messages;
        this.executor = executor;
        this.dao = jdbi.onDemand(AdminFlagDao.class);
    }

    /** What the bot registers with Discord on startup. */
    public static List<CommandData> commands() {
        return List.of(Commands.slash("messages", "The wording the bot sends.")
                .addSubcommands(new net.dv8tion.jda.api.interactions.commands.build.SubcommandData(
                        "reload", "Re-read the message bundles and the override on top of them."))
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED));
    }

    @Override
    public void onSlashCommandInteraction(final @NotNull SlashCommandInteractionEvent event) {
        if (!"messages reload".equals(event.getFullCommandName())) {
            // Every other command in the bot comes through this listener too.
            return;
        }

        // Deferred and off the gateway thread: this reads files, and an interaction that is not
        // acknowledged within three seconds is dead.
        event.deferReply(true).queue();
        executor.execute(() -> {
            try {
                if (!dao.isAdmin(event.getUser().getId()).orElse(Boolean.FALSE)) {
                    event.getHook().editOriginal(NOT_AN_ADMIN).queue();
                    return;
                }
                messages.reload();
                event.getHook().editOriginal(report(messages.unknownOverrideKeys())).queue();
            } catch (final RuntimeException exception) {
                log.error("the messages could not be reloaded, the running ones are unchanged",
                        exception);
                event.getHook().editOriginal(
                        "The messages could not be reloaded; the running ones are unchanged. "
                                + "The log says why.").queue();
            }
        });
    }

    /**
     * Names the override keys that overrode nothing, rather than reporting a plain success.
     *
     * <p>An override for a key no bundle declares is stored and never looked up: the line does not
     * change and nothing fails. "Reloaded" on its own is exactly the answer that makes somebody
     * edit the same typo three times.
     */
    private static String report(final Set<String> unknown) {
        if (unknown.isEmpty()) {
            return "The message bundles were reloaded.";
        }
        return "The message bundles were reloaded. These override keys are not declared by any "
                + "bundle, so they do nothing: " + String.join(", ", unknown) + ".";
    }
}

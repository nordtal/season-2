package eu.nordtal.s2.networkcontrol.command;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * {@code /network reload} on the proxy - the message bundles and the operator's override on top of
 * them, and nothing else.
 *
 * <h2>What it deliberately does not reload</h2>
 * {@code gate.yml}, {@code pack.yml}, {@code network.yml} and {@code database.yml} are all read
 * once, at startup, and every one of them is wired into something that cannot be swapped under a
 * running proxy: the login gate's backend names, the pack's URL and hash, the connection pool. A
 * command that re-read them would either do nothing or do something worse than nothing. The MOTD
 * and every disconnect screen, on the other hand, are strings - and those are exactly what somebody
 * wants to fix at eight in the evening without dropping everyone who is online.
 *
 * <h2>Console counts as an admin here</h2>
 * {@code /phase} requires a player, because it takes a decision about the season and the audit row
 * records who took it. A reload changes no state anybody can be asked about, and the proxy console
 * is where an operator already is when they have just edited a file on the host.
 */
public final class NetworkCommand {

    private static final String ALIAS = "network";

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final LoginRoster roster;
    private final Messages messages;
    private final MessageRenderer renderer;

    public NetworkCommand(final Object plugin, final ProxyServer proxy, final Logger logger,
                          final LoginRoster roster, final Messages messages) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.roster = roster;
        this.messages = messages;
        this.renderer = new MessageRenderer(messages);
    }

    /** @return the command, ready to hand to {@code CommandManager#register(CommandMeta, Command)} */
    public BrigadierCommand build() {
        return new BrigadierCommand(BrigadierCommand
                .literalArgumentBuilder(ALIAS)
                .requires(this::mayUse)
                .then(BrigadierCommand.literalArgumentBuilder("reload")
                        .executes(this::reload)));
    }

    public static String alias() {
        return ALIAS;
    }

    /**
     * A map lookup, never a query - Brigadier evaluates this while building the command tree it
     * sends to a client, which is not a place for a blocking JDBC call. Same rule as
     * {@code PhaseCommand}.
     */
    private boolean mayUse(final CommandSource source) {
        if (source instanceof Player player) {
            return roster.isAdmin(player.getUniqueId());
        }
        return true;
    }

    private int reload(final CommandContext<CommandSource> context) {
        final CommandSource source = context.getSource();
        // The account-linked language, the same one every disconnect screen is rendered in - not
        // the client's own setting, which says nothing about what somebody actually reads.
        final Locale locale = source instanceof Player player
                ? roster.localeOf(player.getUniqueId())
                : Locale.ENGLISH;

        // Off the event thread: this reads files, and a proxy thread blocked on disk is every
        // login blocked on disk.
        proxy.getScheduler().buildTask(plugin, () -> {
            final String key;
            try {
                messages.reload();
                messages.unknownOverrideKeys().forEach(unknown -> logger.warn(
                        "the message override names {}, which no bundle declares - it is stored and"
                                + " never used; check the spelling", unknown));
                key = "network.reloaded";
            } catch (final RuntimeException exception) {
                logger.error("the messages could not be reloaded, the running ones are unchanged",
                        exception);
                source.sendMessage(renderer.get(locale, "network.reload-failed"));
                return;
            }
            source.sendMessage(renderer.get(locale, key));
        }).schedule();
        return Command.SINGLE_SUCCESS;
    }
}

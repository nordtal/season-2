package eu.nordtal.s2.smp.command;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /smp update} - the second surface onto the updater, for when Discord is not where you are.
 *
 * <h2>This plugin does not update anything</h2>
 * It cannot: the updater is a different container, with the volumes mounted and the schema in its
 * hands. What happens here is that a row is written into {@code update_request} and the answer is
 * read back out of it (docs/updater.md#how-it-is-operated) - the same table, the same rows and the
 * same report that {@code /update} in Discord shows. Nothing is rendered twice.
 *
 * <h2>Four things it can do</h2>
 * <pre>
 *   /smp update                    what is newer than what the network is running
 *   /smp update apply              install it. Restarts nothing
 *   /smp update restart            restart the whole network, after a minute of countdown
 *   /smp update restart cancel     stop that countdown
 * </pre>
 *
 * <h2>The countdown is the confirmation</h2>
 * A chat line has no button to press and no dialog to read, so {@code restart} does not ask "are
 * you sure" - it starts a minute that everybody on the network is counted down through, by the
 * proxy, wherever they are. That minute is the confirmation: an admin who mistyped has sixty
 * seconds and a command that stops it, and everybody else finds out before it happens rather than
 * afterwards.
 */
public final class UpdateCommands {

    /** How often the answer row is re-read, in ticks. Two seconds; a person is waiting. */
    private static final long CHECK_TICKS = 40L;

    /**
     * How long to wait for the updater before giving up on it.
     * <p>
     * An install downloads a Paper jar and seven plugins - minutes, not seconds. What this bounds
     * is the case where nothing is listening at all, which looks exactly the same from here until
     * it is said out loud.
     * </p>
     */
    private static final Duration PATIENCE = Duration.ofMinutes(12);

    /** How much of the report goes into chat before it is cut short. */
    private static final int MAX_LINES = 40;

    private final Plugin plugin;
    private final UpdateDirectory updates;
    private final Messages messages;
    private final PlayerLocales locales;

    public UpdateCommands(final Plugin plugin, final UpdateDirectory updates,
                          final Messages messages, final PlayerLocales locales) {
        this.plugin = plugin;
        this.updates = updates;
        this.messages = messages;
        this.locales = locales;
    }

    /**
     * The {@code update} subtree, to hang under {@code /smp}.
     * <p>
     * It carries no {@code requires} of its own: {@code /smp} is already admin-only and a second
     * check here would be a second place to get it wrong.
     * </p>
     */
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("update")
                .executes(context -> ask(context, UpdateKind.REPORT))
                .then(Commands.literal("apply")
                        .executes(context -> ask(context, UpdateKind.APPLY)))
                .then(Commands.literal("restart")
                        .executes(this::askRestart)
                        .then(Commands.literal("cancel").executes(this::cancelRestart)));
    }

    // ---------------------------------------------------------------- report and apply

    private int ask(final CommandContext<CommandSourceStack> context, final UpdateKind kind) {
        final CommandSender sender = context.getSource().getSender();
        say(sender, kind == UpdateKind.APPLY ? "smp.update.installing" : "smp.update.checking");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final UpdateRequest request;
            try {
                request = updates.submit(kind, UpdateSource.GAME, nameOf(sender), Duration.ZERO);
            } catch (final RuntimeException failure) {
                plugin.getLogger().warning("Could not write the " + kind + " request: " + failure);
                say(sender, "smp.update.failed");
                return;
            }
            watch(sender, request.id(), Instant.now().plus(PATIENCE));
        });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Re-reads the row until it is finished, then puts the updater's own report in chat.
     * <p>
     * A repeating async task rather than a loop on a worker: an install takes minutes, and a Paper
     * server's async pool is not a place to park a thread for that long.
     * </p>
     */
    private void watch(final CommandSender sender, final long id, final Instant deadline) {
        final BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            final Optional<UpdateRequest> row;
            try {
                row = updates.find(id);
            } catch (final RuntimeException failure) {
                plugin.getLogger().warning("Could not read update request " + id + ": " + failure);
                handle[0].cancel();
                say(sender, "smp.update.failed");
                return;
            }

            if (row.isEmpty()) {
                handle[0].cancel();
                say(sender, "smp.update.gone");
                return;
            }
            final UpdateRequest request = row.get();
            if (request.status().isFinished()) {
                handle[0].cancel();
                report(sender, request);
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                handle[0].cancel();
                // Names the state the row is in, because PENDING here means one specific thing:
                // nothing is listening, and the updater container is not running.
                sayRaw(sender, messages.format(localeOf(sender), "smp.update.timeout",
                        "id", id, "status", request.status()));
            }
        }, CHECK_TICKS, CHECK_TICKS);
    }

    private void report(final CommandSender sender, final UpdateRequest request) {
        final String result = request.result() == null ? "(the updater wrote nothing)" : request.result();
        final String[] lines = result.split("\n", -1);

        Bukkit.getScheduler().runTask(plugin, () -> {
            // CANCELLED is deliberately not in here: a stopped countdown is somebody using the way
            // out, and cancelRestart has already said so in its own words.
            if (request.status() == UpdateStatus.FAILED) {
                sender.sendMessage(Component.text(messages.get(localeOf(sender), "smp.update.failed")));
            }
            for (int line = 0; line < Math.min(lines.length, MAX_LINES); line++) {
                sender.sendMessage(Component.text(lines[line]));
            }
            if (lines.length > MAX_LINES) {
                sender.sendMessage(Component.text(messages.format(localeOf(sender),
                        "smp.update.truncated", "lines", lines.length - MAX_LINES)));
            }
        });
    }

    // ---------------------------------------------------------------- the restart

    private int askRestart(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = context.getSource().getSender();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                updates.submit(UpdateKind.RESTART, UpdateSource.GAME, nameOf(sender),
                        UpdateDirectory.RESTART_COUNTDOWN);
            } catch (final RuntimeException failure) {
                plugin.getLogger().warning("Could not write the restart request: " + failure);
                say(sender, "smp.update.failed");
                return;
            }
            plugin.getLogger().info(nameOf(sender) + " asked for a restart of the whole network");
            // Everybody else is told by network-control, which is the only process that sees every
            // player. This line is only for the person who typed it, and its job is to name the
            // way back out.
            sayRaw(sender, messages.format(localeOf(sender), "smp.update.restart-asked",
                    "seconds", UpdateDirectory.RESTART_COUNTDOWN.toSeconds()));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int cancelRestart(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = context.getSource().getSender();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Optional<UpdateRequest> cancelled;
            try {
                cancelled = updates.cancelPendingRestart("Cancelled in game by " + nameOf(sender));
            } catch (final RuntimeException failure) {
                plugin.getLogger().warning("Could not cancel the restart: " + failure);
                say(sender, "smp.update.failed");
                return;
            }
            // An empty answer is not an error: the countdown ran out while the command was being
            // typed, and "too late" is exactly what the admin needs to know.
            say(sender, cancelled.isPresent()
                    ? "smp.update.restart-cancelled" : "smp.update.restart-too-late");
        });
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- helpers

    /** Who asked, for the row and for the log. The console has no name and is allowed none. */
    private static String nameOf(final CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "console";
    }

    private Locale localeOf(final CommandSender sender) {
        return sender instanceof Player player ? locales.of(player.getUniqueId()) : Locale.ENGLISH;
    }

    private void say(final CommandSender sender, final String key) {
        sayRaw(sender, messages.get(localeOf(sender), key));
    }

    /** Answers on the main thread, which is where a CommandSender may be spoken to. */
    private void sayRaw(final CommandSender sender, final String text) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text(text)));
    }
}

package eu.nordtal.s2.smp.command;

import eu.nordtal.s2.common.message.MessageRenderer;
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
public final class UpdateWatcher {

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

    public UpdateWatcher(final Plugin plugin, final UpdateDirectory updates) {
        this.plugin = plugin;
        this.updates = updates;
    }

    /** The directory this watcher reads, so the commands write through the same pool. */
    public UpdateDirectory directory() {
        return updates;
    }

    /**
     * Follows one request and prints its answer when it lands.
     *
     * <h2>What is left of this class, and why</h2>
     * It used to own the {@code /smp update} Brigadier tree as well. The commands were folded into
     * {@code :commands} on 2026-09-08, so who may ask, in which language, and what is said back are
     * decided once for all three surfaces - and what stays here is the half that genuinely belongs
     * to a Paper server: a Bukkit timer, and chat lines. The comment in {@code SmpCommand} that
     * said {@code /smp update} "should not become a NordtalCommand" rested on the rule that the
     * report must never be rendered twice; that rule was deliberately rewritten the day before.
     *
     * @param id   the request to follow
     * @param user who to print it to - reached through {@code replyLiteral}, because the updater's
     *             report is text and must never go through MiniMessage: a version string or a
     *             filename containing {@code <} would become a tag
     */
    public void watch(final long id, final eu.nordtal.s2.commands.NordtalUser user) {
        watch(user, id, Instant.now().plus(PATIENCE));
    }

    private void watch(final eu.nordtal.s2.commands.NordtalUser sender, final long id,
                       final Instant deadline) {
        final BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            final Optional<UpdateRequest> row;
            try {
                row = updates.find(id);
            } catch (final RuntimeException failure) {
                plugin.getLogger().warning("Could not read update request " + id + ": " + failure);
                handle[0].cancel();
                sender.reply("smp.update.failed");
                return;
            }

            if (row.isEmpty()) {
                handle[0].cancel();
                sender.reply("smp.update.gone");
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
                sender.reply("smp.update.timeout",
                        java.util.Map.of("id", id, "status", request.status()));
            }
        }, CHECK_TICKS, CHECK_TICKS);
    }

    /**
     * Prints the updater's answer.
     *
     * <h2>The report is data now, and this renders it once</h2>
     * Since 2026-09-07 the row carries an {@link eu.nordtal.s2.common.update.UpdateReport} as JSON,
     * and its own {@code render()} is the one text form of it - the same one a console prints. That
     * is the old rule ("nothing is rendered twice") standing exactly where it always should have:
     * nothing here <em>decides</em> anything, and the text below is generated from the updater's
     * object rather than typed alongside it. A row written before that change is plain text and is
     * printed as it is, which is why the fallback exists and is not going away.
     */
    private void report(final eu.nordtal.s2.commands.NordtalUser sender, final UpdateRequest request) {
        final String stored = request.result();
        final String result = eu.nordtal.s2.common.update.UpdateReports.parse(stored)
                .map(eu.nordtal.s2.common.update.UpdateReport::render)
                .orElseGet(() -> stored == null ? "(the updater wrote nothing)" : stored);
        final String[] lines = result.split("\n", -1);

        Bukkit.getScheduler().runTask(plugin, () -> {
            // CANCELLED is deliberately not in here: a stopped countdown is somebody using the way
            // out, and /update cancel has already said so in its own words.
            if (request.status() == UpdateStatus.FAILED) {
                sender.reply("smp.update.failed");
            }
            for (int line = 0; line < Math.min(lines.length, MAX_LINES); line++) {
                // replyLiteral, NOT a message key: the updater's report is printed verbatim, here
                // and in the Discord embed. Parsing it would make a version string or a filename
                // containing '<' into a tag. What DID change on 2026-09-07 is where the text comes
                // from - it is generated from the updater's own report object above rather than
                // typed beside it, which is the half the old rule was actually protecting.
                sender.replyLiteral(lines[line]);
            }
            if (lines.length > MAX_LINES) {
                sender.reply("smp.update.truncated",
                        java.util.Map.of("lines", lines.length - MAX_LINES));
            }
        });
    }
}

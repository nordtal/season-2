package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateStatus;


import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Follows an update request on a Paper server and prints its answer when it lands.
 *
 * <h2>Why this is in {@code :paper-common} and not in one plugin</h2>
 * {@code /update} is {@code Target.LOCAL}, so all three Paper servers register it themselves - and
 * each therefore needs its own way of showing the answer. This class was {@code smp}'s alone until
 * 2026-09-08, and the other two were wired with a no-op watcher: an admin on the hunger games
 * server or in limbo got "asking the updater..." and then <b>nothing at all</b>, for ever, for
 * every one of the four commands. Found by review the same day.
 *
 * <p>It needs a Bukkit scheduler and a chat line, which is exactly the rule for living here: code
 * belongs in {@code :paper-common} only if it needs a Paper type.</p>
 *
 * <h2>The report is text and stays text</h2>
 * Lines go out through {@link NordtalUser#replyLiteral}, never a message key: the updater's report
 * carries version strings and filenames, and one containing {@code <} would become a MiniMessage
 * tag. What changed on 2026-09-07 is only where that text comes from - it is generated from the
 * updater's own report object rather than typed beside it.
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
    public void watch(final long id, final NordtalUser user) {
        watch(user, id, Instant.now().plus(PATIENCE));
    }

    private void watch(final NordtalUser sender, final long id,
                       final Instant deadline) {
        final BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            final Optional<UpdateRequest> row;
            try {
                row = updates.find(id);
            } catch (final RuntimeException failure) {
                plugin.getLogger().warning("Could not read update request " + id + ": " + failure);
                handle[0].cancel();
                sender.reply("update.failed");
                return;
            }

            if (row.isEmpty()) {
                handle[0].cancel();
                sender.reply("update.gone");
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
                sender.reply("update.timeout",
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
    private void report(final NordtalUser sender, final UpdateRequest request) {
        final String stored = request.result();
        final String result = eu.nordtal.s2.common.update.UpdateReports.parse(stored)
                .map(eu.nordtal.s2.common.update.UpdateReport::render)
                .orElseGet(() -> stored == null ? "(the updater wrote nothing)" : stored);
        final String[] lines = result.split("\n", -1);

        Bukkit.getScheduler().runTask(plugin, () -> {
            // CANCELLED is deliberately not in here: a stopped countdown is somebody using the way
            // out, and /update cancel has already said so in its own words.
            if (request.status() == UpdateStatus.FAILED) {
                sender.reply("update.failed");
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
                sender.reply("update.truncated",
                        java.util.Map.of("lines", lines.length - MAX_LINES));
            }
        });
    }
}

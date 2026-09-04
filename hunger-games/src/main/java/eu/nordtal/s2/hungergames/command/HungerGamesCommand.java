package eu.nordtal.s2.hungergames.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;

import eu.nordtal.s2.commands.Confirmations;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;
import eu.nordtal.s2.hungergames.game.Demotion;
import eu.nordtal.s2.hungergames.game.HungerGamesManager;
import eu.nordtal.s2.hungergames.lobby.Lobby;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.papercommon.command.PaperUser;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@code /hg} - Brigadier only, registered through the Lifecycle API, per
 * docs/architecture.md#commands. No command framework is used anywhere in this repository.
 *
 * <h2>The console can run this, since 2026-09-04, and could not before</h2>
 * Every handler here cast its sender to {@link Player} and every subcommand was gated on
 * {@code getSender() instanceof Player}. So the console could run <b>none</b> of it, and the start
 * of the season's flagship event depended on one client being able to connect and stay connected -
 * with no second path anywhere, while {@code /phase} has had a documented one since 2026-08-31.
 * That was the finding the whole command survey turned on, and it was not written down anywhere.
 *
 * <p>The sender is a {@link NordtalUser} now ({@code :paper-common}'s {@link PaperUser}), so a
 * player and the console are the same thing to every handler below. <b>{@code /hg ready} is the one
 * exception and stays player-only</b>: it marks <em>the sender</em> ready, and the console is not
 * registered for a game. That is why {@code Surface.CONSOLE} is a per-command decision rather than
 * a property of a module.</p>
 *
 * <h2>Where the admin check runs, and why</h2>
 * {@code /hg start} has to be gated on {@code discord_user.admin}, which this plugin can only read
 * from the database (docs/smp.md#admins - there is no LuckPerms in this repo). Brigadier's
 * {@code .requires(Predicate<CommandSourceStack>)} runs synchronously on the main thread while the
 * client's command tree is built, so a blocking database call there would stall the whole server on
 * every affected player's login. This command therefore gates <b>liberally</b> at the Brigadier
 * layer and does the real lookup inside the execution body, dispatched to an async task; an
 * unauthorised caller gets a translated refusal rather than the command simply not existing. That
 * trade (a non-admin can see the command exists, but cannot use it) was chosen over blocking the
 * main thread on the hotter path.
 *
 * <p>The console skips the lookup and is always an admin. {@link PaperUser} carries the reasoning:
 * it is a shell inside the container, and anybody holding one can edit the table by hand.</p>
 */
public final class HungerGamesCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(HungerGamesCommand.class);

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final HungerGamesSpec config;
    private final Messages messages;
    private final PlayerLocales locales;
    private final Lobby lobby;
    private final HungerGamesSounds sounds;

    /**
     * Re-reads {@code sounds.yml} and swaps it into the running adapter, answering whether that
     * worked. It is the plugin's own method rather than a handle plus an adapter passed separately,
     * because the console line naming what went wrong belongs next to the handle and not here.
     */
    private final java.util.function.BooleanSupplier reloadSounds;

    private final java.util.function.Supplier<UUID> currentGameId;
    private final Consumer<UUID> onConfirmedStart;

    /**
     * The below-soft-minimum confirmation, shared with the proxy's {@code /phase set}.
     *
     * <p>It was a {@code Map<UUID, Instant>} and its own expiry arithmetic here until 2026-09-04.
     * {@code :commands}' version keys on the whole command rather than on the person, is consumed
     * rather than leaving a window open, and works for a sender that has no UUID at all - which is
     * exactly what this class gained on the same day.</p>
     */
    private final Confirmations confirmations = new Confirmations();

    /** What a pending confirmation is about. Never shown; it is the key. */
    private static final String START = "/hg start";

    public HungerGamesCommand(final Plugin plugin, final HungerGamesDao dao, final HungerGamesSpec config,
                              final Messages messages, final PlayerLocales locales,
                              final Lobby lobby,
                              final HungerGamesSounds sounds,
                              final java.util.function.BooleanSupplier reloadSounds,
                              final java.util.function.Supplier<UUID> currentGameId,
                              final Consumer<UUID> onConfirmedStart) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
        this.lobby = lobby;
        this.sounds = sounds;
        this.reloadSounds = reloadSounds;
        this.currentGameId = currentGameId;
        this.onConfirmedStart = onConfirmedStart;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("hg")
                .then(Commands.literal("start")
                        .executes(context -> asAdmin(context, user -> attemptStart(user, false)))
                        .then(Commands.literal("confirm")
                                .executes(context -> asAdmin(context, user -> attemptStart(user, true)))))
                // The one that is still player-only, and the reason is not "it is easier": it marks
                // the SENDER ready, and the console is registered for no game.
                .then(Commands.literal("ready")
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(this::handleReady))
                .then(Commands.literal("ready-status")
                        .executes(context -> asAdmin(context, this::readyStatus)))
                .then(Commands.literal("reload")
                        .executes(context -> asAdmin(context, this::reload)))
                .build();
    }

    // ---------------------------------------------------------------- /hg reload

    /**
     * Re-reads the message bundles, the operator's override on top of them, and {@code sounds.yml}.
     *
     * <p><b>The wording and the sounds, and nothing else</b>, deliberately. {@code config.yml} holds
     * the border schedule and the loot timings, and a game is a running clock: re-reading those
     * mid-match would move a shrink that players are already running from. A typo in a message is
     * worth fixing during a game and so is a chime that turns out to be unbearable with twenty
     * people on towers; a border parameter is not.</p>
     *
     * <p>Two independent try blocks and two independent console lines, matching {@code smp}'s
     * reload: a broken {@code sounds.yml} must not stop a corrected message from arriving, and a
     * typo'd override must not read as sounds that failed to load.</p>
     */
    private void reload(final NordtalUser user) {
        // The sounds go first because they are the cheapest thing to get wrong and the only one an
        // operator is expected to be iterating on while somebody waits to hear the result.
        final boolean soundsReloaded = reloadSounds.getAsBoolean();

        boolean messagesReloaded;
        try {
            messages.reload();
            messages.unknownOverrideKeys().forEach(key -> plugin.getLogger().warning(
                    "the message override names " + key + ", which no bundle declares - it is"
                            + " stored and never used; check the spelling"));
            messagesReloaded = true;
        } catch (final RuntimeException exception) {
            plugin.getLogger().severe("the messages could not be reloaded, the running ones are "
                    + "unchanged: " + exception.getMessage());
            messagesReloaded = false;
        }

        if (soundsReloaded && messagesReloaded) {
            // No sound: an admin's confirmation of a command they just typed and are already
            // reading. smp's /smp reload is silent for the same reason.
            user.reply("hg.admin.reloaded");
        } else {
            user.reply("hg.admin.reload-failed", Map.of(), Feedback.REFUSED);
        }
    }

    // ---------------------------------------------------------------- /hg start

    private void attemptStart(final NordtalUser user, final boolean isConfirmation) {
        final UUID gameId = currentGameId.get();
        if (gameId == null) {
            user.reply("hg.start.no-game", Map.of(), Feedback.REFUSED);
            return;
        }

        final var game = dao.game(gameId);
        if (game.isEmpty() || !"REGISTRATION".equals(game.get().state().name())) {
            user.reply("hg.start.wrong-state",
                    Map.of("state", game.map(g -> g.state().name()).orElse("NONE")),
                    Feedback.REFUSED);
            return;
        }

        final List<RosterEntry> roster = dao.roster(gameId);
        final int participantCount = Demotion.resolve(roster).size();

        if (participantCount < HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS) {
            user.reply("hg.start.below-hard-minimum",
                    Map.of("minimum", HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS,
                            "count", participantCount),
                    Feedback.REFUSED);
            return;
        }

        if (isConfirmation) {
            // consume, not confirm: confirm() arms on a miss, which is right when the confirmation
            // is the SAME command typed again and wrong here - a bare `/hg start confirm` typed
            // twice would arm itself and go through on the second attempt, having never shown the
            // warning this whole branch exists for.
            if (!confirmations.consume(user, START)) {
                user.reply("hg.start.confirm-expired", Map.of(), Feedback.REFUSED);
                return;
            }
        } else if (participantCount < config.softMinimumParticipants()) {
            confirmations.arm(user, START);
            // REFUSED rather than nothing: the command did not do what was asked, and this is the
            // one place an admin about to start the season's flagship event should stop and read
            // rather than type the next thing.
            user.reply("hg.start.below-soft-minimum",
                    Map.of("count", participantCount,
                            "minimum", config.softMinimumParticipants(),
                            "seconds", Confirmations.WINDOW.toSeconds()),
                    Feedback.REFUSED);
            return;
        } else {
            // A start that needed no confirmation clears any stale one, so a warning from a minute
            // ago cannot be spent on a later game.
            confirmations.forget(user, START);
        }

        // SMALL_SUCCESS, and this is a deliberate departure from smp, where an admin's confirmation
        // of their own command is silent. Two things make it different: it is irreversible, and an
        // admin who is not themselves a participant hears NOTHING else during the entire start -
        // the TRAVEL, the countdown and the release all go to participants only. BIG_SUCCESS was
        // rejected on the other side: the admin's private chat line must not be louder than what
        // every participant hears.
        user.reply("hg.start.started", Map.of("count", participantCount), Feedback.SMALL_SUCCESS);
        // The one command that decides the whole event, and until 2026-09-04 nothing in the
        // container log said it had been run. Who, which game, and how many participants the
        // arithmetic saw.
        LOGGER.info("hunger-games game {} started by {} ({}) with {} resolvable participants{}",
                gameId, user.name(), user.origin(), participantCount,
                isConfirmation ? " (confirmed below the soft minimum)" : "");
        // Straight through on this thread. onConfirmedStart leads to HungerGamesManager#start,
        // which reads the roster and writes the team colours before it hops to the main thread
        // itself; a runTask here would have undone exactly that.
        onConfirmedStart.accept(gameId);
    }

    // ---------------------------------------------------------------- /hg ready

    private int handleReady(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final NordtalUser user = playerUser(player, false);
        final UUID gameId = currentGameId.get();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (gameId == null) {
                user.reply("hg.lobby.not-registered", Map.of(), Feedback.REFUSED);
                return;
            }
            final var discordId = dao.discordIdOf(player.getUniqueId());
            final boolean marked = discordId.isPresent() && lobby.markReady(gameId, discordId.get());
            user.reply(marked ? "hg.lobby.ready-set" : "hg.lobby.not-registered", Map.of(),
                    marked ? Feedback.SMALL_SUCCESS : Feedback.REFUSED);
        });
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- /hg ready-status

    private void readyStatus(final NordtalUser user) {
        final UUID gameId = currentGameId.get();
        if (gameId == null) {
            user.reply("hg.start.no-game", Map.of(), Feedback.REFUSED);
            return;
        }

        final List<RosterEntry> roster = lobby.readyStatus(gameId);
        final Map<String, Boolean> readyByTeam = new java.util.LinkedHashMap<>();
        for (final RosterEntry entry : roster) {
            readyByTeam.merge(entry.teamName(), entry.ready(), (a, b) -> a && b);
        }

        user.reply("hg.ready-status.header");
        readyByTeam.forEach((team, ready) -> user.reply("hg.ready-status.line",
                Map.of("team", team,
                        // A nested message, resolved in the reader's own language: this is what
                        // NordtalUser#phrase exists for.
                        "status", user.phrase(ready
                                ? "hg.ready-status.ready" : "hg.ready-status.not-ready"))));
    }

    // ---------------------------------------------------------------- admin gate

    /**
     * Resolves the sender, checks the admin flag, and runs {@code action} - all on one async task,
     * deliberately <b>not</b> back on the main thread.
     *
     * <p>Everything {@code /hg start} does before the world is touched is database work:
     * {@link HungerGamesDao#game}, {@link HungerGamesDao#roster}, and then
     * {@link HungerGamesManager#start} with its own roster read and one colour write per team.
     * Handing {@code action} to {@code runTask} put all of that on the main thread at the exact
     * moment every participant is about to be teleported onto a tower - the same mistake, in the
     * same shape, as the join-time language lookup that froze this module's login path.
     *
     * <p>What genuinely needs the main thread hops there itself: every reply, inside
     * {@link PaperUser}, and {@code HungerGamesManager#start} for the world work.</p>
     */
    private int asAdmin(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                        final Consumer<NordtalUser> action) {
        final CommandSender sender = context.getSource().getSender();

        if (!(sender instanceof Player player)) {
            // The console. No lookup at all - not as a shortcut, but because the answer cannot
            // depend on a database that may be the thing being fixed. See PaperUser.
            final NordtalUser user = PaperUser.console(plugin, sender, messages);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> action.accept(user));
            return Command.SINGLE_SUCCESS;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final boolean admin = dao.isAdmin(player.getUniqueId()).orElse(Boolean.FALSE);
            final NordtalUser user = playerUser(player, admin);
            if (!admin) {
                user.reply("hg.start.not-admin", Map.of(), Feedback.REFUSED);
                return;
            }
            action.accept(user);
        });
        return Command.SINGLE_SUCCESS;
    }

    private NordtalUser playerUser(final Player player, final boolean admin) {
        return PaperUser.of(plugin, player, locales.of(player.getUniqueId()), admin, null,
                messages, sounds::play);
    }
}

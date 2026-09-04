package eu.nordtal.s2.hungergames.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;
import eu.nordtal.s2.hungergames.game.Demotion;
import eu.nordtal.s2.hungergames.game.HungerGamesManager;
import eu.nordtal.s2.hungergames.lobby.Lobby;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /hg} - Brigadier only, registered through the Lifecycle API, per
 * docs/architecture.md#commands. No command framework is used anywhere in this repository.
 *
 * <h2>Where the admin check runs, and why</h2>
 * {@code /hg start} has to be gated on {@code discord_user.admin}, which this plugin can only read
 * from the database (docs/smp.md#admins - there is no LuckPerms in this repo). Brigadier's
 * {@code .requires(Predicate<CommandSourceStack>)} runs synchronously on the main thread while the
 * client's command tree is built, so a blocking database call there would stall the whole server on
 * every affected player's login and every {@code /reload}-triggered rebuild. This command therefore
 * gates <b>liberally</b> at the Brigadier layer - only "is a player" - and does the real admin
 * lookup inside the execution body, dispatched to an async task; an unauthorised caller gets a
 * translated refusal message rather than the command simply not existing for them. That trade
 * (a non-admin can see the command exists, but cannot use it) was chosen over blocking the main
 * thread on every requirement check, which is by far the hotter path.
 */
public final class HungerGamesCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(HungerGamesCommand.class);

    /** How long a below-soft-minimum start stays confirmable with a bare "/hg start confirm". */
    private static final Duration CONFIRM_WINDOW = Duration.ofSeconds(30);

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final HungerGamesSpec config;
    private final Messages messages;
    private final PlayerLocales locales;
    private final HungerGamesManager manager;
    private final Lobby lobby;
    private final java.util.function.Supplier<UUID> currentGameId;
    private final java.util.function.BiConsumer<UUID, Player> onConfirmedStart;

    /** mcUuid -> when their below-soft-minimum "/hg start" confirmation window closes. */
    private final Map<UUID, Instant> pendingConfirmations = new ConcurrentHashMap<>();

    public HungerGamesCommand(final Plugin plugin, final HungerGamesDao dao, final HungerGamesSpec config,
                              final Messages messages, final PlayerLocales locales,
                              final HungerGamesManager manager, final Lobby lobby,
                              final java.util.function.Supplier<UUID> currentGameId,
                              final java.util.function.BiConsumer<UUID, Player> onConfirmedStart) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
        this.manager = manager;
        this.lobby = lobby;
        this.currentGameId = currentGameId;
        this.onConfirmedStart = onConfirmedStart;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("hg")
                .then(Commands.literal("start")
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(this::handleStart)
                        .then(Commands.literal("confirm")
                                .requires(source -> source.getSender() instanceof Player)
                                .executes(this::handleStartConfirm)))
                .then(Commands.literal("ready")
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(this::handleReady))
                .then(Commands.literal("ready-status")
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(this::handleReadyStatus))
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(this::handleReload))
                .build();
    }

    // ---------------------------------------------------------------- /hg reload

    /**
     * Re-reads the message bundles and the operator's override on top of them.
     *
     * <p><b>Only the wording</b>, deliberately. {@code config.yml} holds the border schedule and
     * the loot timings, and a game is a running clock: re-reading those mid-match would move a
     * shrink that players are already running from. A typo in a message is worth fixing during a
     * game; a border parameter is not.</p>
     */
    private int handleReload(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        // runAsAdmin is already off the main thread, which is where the file reads belong.
        runAsAdmin(player, () -> {
            final Locale locale = locales.of(player.getUniqueId());
            try {
                messages.reload();
                messages.unknownOverrideKeys().forEach(key -> plugin.getLogger().warning(
                        "the message override names " + key + ", which no bundle declares - it is"
                                + " stored and never used; check the spelling"));
                tell(player, MessageRenderer.of(messages).get(locale, "hg.admin.reloaded"));
            } catch (final RuntimeException exception) {
                plugin.getLogger().severe("the messages could not be reloaded, the running ones are "
                        + "unchanged: " + exception.getMessage());
                tell(player, MessageRenderer.of(messages).get(locale, "hg.admin.reload-failed"));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- /hg start

    private int handleStart(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        runAsAdmin(player, () -> attemptStart(player, false));
        return Command.SINGLE_SUCCESS;
    }

    private int handleStartConfirm(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        runAsAdmin(player, () -> attemptStart(player, true));
        return Command.SINGLE_SUCCESS;
    }

    private void attemptStart(final Player player, final boolean isConfirmation) {
        final Locale locale = locales.of(player.getUniqueId());
        final UUID gameId = currentGameId.get();
        if (gameId == null) {
            tell(player, MessageRenderer.of(messages).get(locale, "hg.start.no-game"));
            return;
        }

        final var game = dao.game(gameId);
        if (game.isEmpty() || !"REGISTRATION".equals(game.get().state().name())) {
            tell(player, MessageRenderer.of(messages).format(locale, "hg.start.wrong-state",
                    "state", game.map(g -> g.state().name()).orElse("NONE")));
            return;
        }

        final List<RosterEntry> roster = dao.roster(gameId);
        final int participantCount = Demotion.resolve(roster).size();

        if (participantCount < HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS) {
            tell(player, MessageRenderer.of(messages).format(locale, "hg.start.below-hard-minimum",
                    "minimum", HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS, "count", participantCount));
            return;
        }

        if (participantCount < config.softMinimumParticipants() && !isConfirmation) {
            pendingConfirmations.put(player.getUniqueId(), Instant.now().plus(CONFIRM_WINDOW));
            tell(player, MessageRenderer.of(messages).format(locale, "hg.start.below-soft-minimum",
                    "count", participantCount, "minimum", config.softMinimumParticipants(),
                    "seconds", CONFIRM_WINDOW.toSeconds()));
            return;
        }

        if (isConfirmation) {
            final Instant deadline = pendingConfirmations.remove(player.getUniqueId());
            if (deadline == null || Instant.now().isAfter(deadline)) {
                tell(player, MessageRenderer.of(messages).get(locale, "hg.start.confirm-expired"));
                return;
            }
        }

        tell(player, MessageRenderer.of(messages).format(locale, "hg.start.started", "count", participantCount));
        // The one command that decides the whole event, and until now nothing in the container log
        // said it had been run. Who, which game, and how many participants the arithmetic saw.
        LOGGER.info("hunger-games game {} started by {} with {} resolvable participants{}",
                gameId, player.getUniqueId(), participantCount,
                isConfirmation ? " (confirmed below the soft minimum)" : "");
        // Straight through on this thread. onConfirmedStart leads to HungerGamesManager#start,
        // which reads the roster and writes the team colours before it hops to the main thread
        // itself; a runTask here would have undone exactly that.
        onConfirmedStart.accept(gameId, player);
    }

    // ---------------------------------------------------------------- /hg ready

    private int handleReady(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final Locale locale = locales.of(player.getUniqueId());
        final UUID gameId = currentGameId.get();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (gameId == null) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        MessageRenderer.of(messages).get(locale, "hg.lobby.not-registered")));
                return;
            }
            final var discordId = dao.discordIdOf(player.getUniqueId());
            final boolean marked = discordId.isPresent() && lobby.markReady(gameId, discordId.get());
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(MessageRenderer.of(messages).get(
                    locale, marked ? "hg.lobby.ready-set" : "hg.lobby.not-registered")));
        });
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- /hg ready-status

    private int handleReadyStatus(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final Locale locale = locales.of(player.getUniqueId());
        final UUID gameId = currentGameId.get();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (gameId == null) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        MessageRenderer.of(messages).get(locale, "hg.start.no-game")));
                return;
            }
            final List<RosterEntry> roster = lobby.readyStatus(gameId);
            final Map<String, Boolean> readyByTeam = new java.util.LinkedHashMap<>();
            for (final RosterEntry entry : roster) {
                readyByTeam.merge(entry.teamName(), entry.ready(), (a, b) -> a && b);
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(MessageRenderer.of(messages).get(locale, "hg.ready-status.header"));
                readyByTeam.forEach((team, ready) -> player.sendMessage(MessageRenderer.of(messages).format(
                        locale, "hg.ready-status.line", "team", team,
                        "status", messages.get(locale, ready ? "hg.ready-status.ready" : "hg.ready-status.not-ready"))));
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- admin gate

    /**
     * Runs {@code action} on the very async task that just checked the admin flag - deliberately
     * <b>not</b> back on the main thread.
     *
     * <p>Everything {@code /hg start} does before the world is touched is database work:
     * {@link HungerGamesDao#game}, {@link HungerGamesDao#roster}, and then
     * {@link HungerGamesManager#start} with its own roster read and one colour write per team.
     * Handing {@code action} to {@code runTask} put all of that on the main thread at the exact
     * moment every participant is about to be teleported onto a tower - the same mistake, in the
     * same shape, as the join-time language lookup that froze this module's login path.
     * {@code HungerGamesManager#start}'s own closing {@code runTask} is the proof that it was
     * always written to be called from here rather than from the server thread.
     *
     * <p>What genuinely needs the main thread hops there itself: {@link #tell} for every message,
     * and {@code HungerGamesManager#start} for the world work.
     */
    private void runAsAdmin(final Player player, final Runnable action) {
        final Locale locale = locales.of(player.getUniqueId());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final boolean admin = dao.isAdmin(player.getUniqueId()).orElse(Boolean.FALSE);
            if (!admin) {
                tell(player, MessageRenderer.of(messages).get(locale, "hg.start.not-admin"));
                return;
            }
            action.run();
        });
    }

    /** Sends one message on the main thread, from wherever it is called. */
    /** Sends one already-rendered message on the main thread, from wherever it is called. */
    private void tell(final Player player, final Component message) {
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(message));
    }
}

package eu.nordtal.season.smp.duel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.aura.AuraCause;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.config.DuelAreaSpec;
import eu.nordtal.season.smp.config.DuelKitSpec;
import eu.nordtal.season.smp.config.DuelLoadoutItemSpec;
import eu.nordtal.season.smp.config.DuelPositionSpec;
import eu.nordtal.season.smp.config.DuelsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.feedback.WorldEffects;
import eu.nordtal.season.smp.port.Arenas;
import eu.nordtal.season.smp.region.Box;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The one duel at a time, fought in the arena at spawn with the kit both fighters agreed on in the {@link Negotiation}.
 *
 * The arena's blocks are copied before the fight and placed back after it, however it ends.
 */
public final class Duels implements Arenas {

    enum Outcome {
        WON,
        LOST,
        INTERRUPTED
    }

    /** An interrupted duel moves no aura and shows no title. */
    record Result(Outcome outcome, @Example("25") int aura) {}

    /** The beat at zero seconds is the start of the fight. */
    record Countdown(@Example("5") long seconds) {}

    static final DocumentSpec<DocumentSpec.None> WAITING = DocumentSpec.game(
                    "smp", "duel-waiting", "Duels", DocumentSpec.Shape.CHAT)
            .titled("Waiting for an opponent");
    static final DocumentSpec<Countdown> COUNTDOWN_LINE = DocumentSpec.game(
                    "smp", "duel-countdown", "Duels", DocumentSpec.Shape.CHAT, Countdown.class)
            .titled("Duel countdown");
    static final DocumentSpec<Result> RESULT = DocumentSpec.game(
                    "smp", "duel-result", "Duels", DocumentSpec.Shape.CHAT, Result.class)
            .titled("Duel result");
    static final DocumentSpec<Result> RESULT_TITLE = DocumentSpec.game(
                    "smp", "duel-result-title", "Duels", DocumentSpec.Shape.TITLE, Result.class)
            .titled("Duel result title");

    private static final int COUNTDOWN_SECONDS = 3;

    private static final CountdownPlan COUNTDOWN = CountdownPlan.at().everySecondFrom(COUNTDOWN_SECONDS);

    private static final Title.Times OUTCOME =
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(600));

    private final Plugin plugin;
    private final AuraDao aura;
    private final DuelsSpec config;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;
    private final WorldEffects effects;
    private final Clock clock;
    private final ArenaCopy copy;
    private final Negotiation negotiation;

    private @Nullable ActiveDuel active;

    /** {@link #respawned} restores the saved state of a fighter who died in the arena. */
    private final Map<UUID, SavedState> pending = new HashMap<>();

    public Duels(
            final Plugin plugin,
            final AuraDao aura,
            final DuelsSpec config,
            final Identities identities,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final SmpSounds sounds,
            final WorldEffects effects,
            final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.aura = aura;
        this.config = config;
        this.identities = identities;
        this.renderer = renderer;
        this.sounds = sounds;
        this.effects = effects;
        this.copy = new ArenaCopy(plugin);
        this.negotiation = new Negotiation(plugin, this, config, identities, renderer, documents, sounds, clock);
    }

    /** {@code discordIds} is read at the start, because the identity cache forgets a player on quit. */
    private record ActiveDuel(
            UUID first,
            UUID second,
            String kit,
            Box arena,
            Map<UUID, SavedState> saved,
            Map<UUID, String> discordIds,
            long startedAt) {

        UUID opponentOf(final UUID player) {
            return player.equals(first) ? second : first;
        }

        boolean fights(final UUID player) {
            return player.equals(first) || player.equals(second);
        }
    }

    @Override
    public boolean isInArena(final Player player) {
        return active != null && active.fights(player.getUniqueId());
    }

    /** A fighter builds in the arena during the duel, because the arena is restored afterwards. */
    @Override
    public boolean buildsInArena(final Player player, final Block block) {
        final ActiveDuel duel = active;
        return duel != null
                && duel.fights(player.getUniqueId())
                && duel.arena().contains(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    boolean fighting() {
        return active != null;
    }

    /** Places back a copy of the arena that a crash left behind. Main thread, once the worlds are loaded. */
    public void restoreLeftover() {
        copy.restore();
    }

    public void moved(final PlayerMoveEvent event) {
        negotiation.moved(event);
    }

    public void quit(final Player player) {
        negotiation.quit(player);
        if (isInArena(player)) {
            decide(player);
        }
    }

    /** The inventory is restored one tick later, after the respawn has written its own. */
    public void respawned(final org.bukkit.event.player.PlayerRespawnEvent event) {
        final SavedState state = pending.remove(event.getPlayer().getUniqueId());
        if (state == null) {
            return;
        }
        final Location where = exit();
        if (where != null) {
            event.setRespawnLocation(where);
        }
        PaperScheduler.of(plugin).onMain(() -> {
            final Player player = Bukkit.getPlayer(event.getPlayer().getUniqueId());
            if (player != null) {
                state.restore(player, exit());
            }
        });
    }

    static Box box(final DuelAreaSpec spec) {
        return Box.spanning(spec.world(), spec.minX(), spec.minY(), spec.minZ(), spec.maxX(), spec.maxY(), spec.maxZ());
    }

    /**
     * Called by the negotiation's countdown, never from a move event: Bukkit would undo the teleport.
     *
     * @return false when no duel could start, with the two players left where they stand
     */
    boolean begin(final Player first, final Player second, final DuelKitSpec kit) {
        if (active != null) {
            return false;
        }
        final Box arena = box(config.arena());
        final World world = Bukkit.getWorld(arena.world());
        final Location startFirst = position(config.fighterStartFirst());
        final Location startSecond = position(config.fighterStartSecond());
        if (world == null || startFirst == null || startSecond == null) {
            plugin.getLogger()
                    .warning("the duel arena or a fighter start names a world that is not loaded; no duel is fought");
            return false;
        }
        if (!copy.save(world, arena)) {
            return false;
        }
        face(startFirst, startSecond);
        face(startSecond, startFirst);

        final Map<UUID, SavedState> saved = new HashMap<>();
        saved.put(first.getUniqueId(), SavedState.of(first));
        saved.put(second.getUniqueId(), SavedState.of(second));

        final Map<UUID, String> discordIds = new HashMap<>();
        identities.discordIdOf(first.getUniqueId()).ifPresent(id -> discordIds.put(first.getUniqueId(), id.value()));
        identities.discordIdOf(second.getUniqueId()).ifPresent(id -> discordIds.put(second.getUniqueId(), id.value()));

        final ActiveDuel duel = new ActiveDuel(
                first.getUniqueId(), second.getUniqueId(), kit.name(), arena, saved, discordIds, clock.millis());
        active = duel;

        if (!enter(first, startFirst, kit.loadout()) || !enter(second, startSecond, kit.loadout())) {
            abort(duel);
            return true;
        }
        countdown(duel);
        return true;
    }

    /** Turns {@code from} to look at {@code to}. */
    private static void face(final Location from, final Location to) {
        from.setDirection(to.toVector().subtract(from.toVector()).setY(0));
        from.setPitch(0);
    }

    private static @Nullable Location position(final DuelPositionSpec spec) {
        final World world = Bukkit.getWorld(spec.world());
        return world == null ? null : new Location(world, spec.x() + 0.5, spec.y(), spec.z() + 0.5);
    }

    /** Beside the negotiation area; null falls back to where the fighter stood before the duel. */
    private @Nullable Location exit() {
        return position(config.exit());
    }

    /** Books no stake. */
    private void abort(final ActiveDuel duel) {
        active = null;
        restore(duel, duel.first(), null);
        restore(duel, duel.second(), null);
        copy.restore();
    }

    /** Returns false when the player is not in the arena. */
    private boolean enter(final Player player, final Location at, final List<DuelLoadoutItemSpec> loadout) {
        SavedState.clear(player);
        if (!player.teleport(at)) {
            plugin.getLogger()
                    .warning(player.getName() + " could not be moved into the duel arena; "
                            + "the duel is called off rather than fought outside it");
            return false;
        }
        player.setGameMode(GameMode.ADVENTURE);
        giveLoadout(player, loadout);
        sounds.play(player, Feedback.TRAVEL);
        effects.arenaEntered(at);
        return true;
    }

    private void giveLoadout(final Player player, final List<DuelLoadoutItemSpec> loadout) {
        for (final DuelLoadoutItemSpec entry : loadout) {
            final Material material = GameKeys.material(entry.item()).orElse(null);
            if (material == null) {
                plugin.getLogger()
                        .warning("a duel loadout names '" + entry.item()
                                + "', which is not a material - that piece is missing from the fight");
                continue;
            }
            final ItemStack stack = new ItemStack(material, Math.max(1, entry.amount()));
            if (!equipIfArmour(player, material, stack)) {
                player.getInventory().addItem(stack);
            }
        }
    }

    private static boolean equipIfArmour(final Player player, final Material material, final ItemStack stack) {
        final String name = material.name();
        if (name.endsWith("_HELMET")) {
            player.getInventory().setHelmet(stack);
        } else if (name.endsWith("_CHESTPLATE")) {
            player.getInventory().setChestplate(stack);
        } else if (name.endsWith("_LEGGINGS")) {
            player.getInventory().setLeggings(stack);
        } else if (name.endsWith("_BOOTS")) {
            player.getInventory().setBoots(stack);
        } else {
            return false;
        }
        return true;
    }

    /** The last beat is the start of the fight. */
    private void countdown(final ActiveDuel duel) {
        for (final CountdownPlan.Beat<Sent<Countdown>> beat : COUNTDOWN.beats(
                Duration.ofSeconds(COUNTDOWN_SECONDS),
                seconds -> COUNTDOWN_LINE.with(new Countdown(seconds)),
                COUNTDOWN_LINE.with(new Countdown(0)))) {
            PaperScheduler.of(plugin).onMainAfter(beat.delay(), () -> say(duel, beat));
        }
    }

    private void say(final ActiveDuel duel, final CountdownPlan.Beat<Sent<Countdown>> beat) {
        if (!duel.equals(active)) {
            return;
        }
        forBoth(duel, player -> {
            player.setGameMode(beat.seconds() > 0 ? GameMode.ADVENTURE : GameMode.SURVIVAL);
            player.sendMessage(renderer.format(identities.languageOf(player.getUniqueId()), beat.said()));
            sounds.play(player, Feedback.COUNTDOWN_TICK);
        });
    }

    public void decide(final Player loser) {
        final ActiveDuel duel = active;
        if (duel == null || !duel.fights(loser.getUniqueId())) {
            return;
        }
        final UUID winnerId = duel.opponentOf(loser.getUniqueId());
        finish(duel, winnerId, loser.getUniqueId());
    }

    private void finish(final ActiveDuel duel, final UUID winnerId, final UUID loserId) {
        active = null;
        restore(duel, winnerId, Feedback.BIG_SUCCESS);
        restore(duel, loserId, Feedback.LOSS);
        copy.restore();
        book(winnerId, loserId, duel);
    }

    /** A null {@code feedback} means that {@code stop()} ends the duel. */
    private void restore(final ActiveDuel duel, final UUID playerId, final @Nullable Feedback feedback) {
        final SavedState state = duel.saved().get(playerId);
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || state == null) {
            return;
        }
        if (player.isDead()) {
            pending.put(playerId, state);
            player.spigot().respawn();
        } else {
            state.restore(player, exit());
        }
        final java.util.Locale locale = identities.languageOf(playerId);
        final Result result = new Result(
                feedback == null ? Outcome.INTERRUPTED : feedback == Feedback.BIG_SUCCESS ? Outcome.WON : Outcome.LOST,
                config.duelStake());
        player.sendMessage(renderer.format(locale, RESULT.with(result)));
        if (feedback != null) {
            player.showTitle(renderer.title(locale, RESULT_TITLE.with(result), OUTCOME));
        }
        if (feedback != null) {
            sounds.play(player, feedback);
        }
    }

    private void book(final UUID winnerId, final UUID loserId, final ActiveDuel duel) {
        // A disconnect has already cleared the identity cache.
        final String winner = duel.discordIds().get(winnerId);
        final String loser = duel.discordIds().get(loserId);
        if (winner == null || loser == null) {
            return;
        }
        final int stake = config.duelStake();
        final String kit = duel.kit();

        PaperScheduler.of(plugin).execute(() -> {
            aura.addAura(DiscordId.of(winner), stake, new AuraCause.Duel(true, kit));
            aura.addAura(DiscordId.of(loser), -stake, new AuraCause.Duel(false, kit));
        });
    }

    public void stop() {
        negotiation.stop();
        final ActiveDuel duel = active;
        if (duel != null) {
            active = null;
            restore(duel, duel.first(), null);
            restore(duel, duel.second(), null);
        }
        copy.restore();
    }

    private void forBoth(final ActiveDuel duel, final java.util.function.Consumer<Player> action) {
        for (final UUID id : List.of(duel.first(), duel.second())) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                action.accept(player);
            }
        }
    }
}

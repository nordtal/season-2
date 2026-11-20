package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.wheel.WheelTimetable;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.SmpTexts;
import eu.nordtal.season.smp.TakenItems;
import eu.nordtal.season.smp.config.WheelPrizeSpec;
import eu.nordtal.season.smp.config.WheelSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** The SQL update that spends a spin runs off the main thread, so two clicks yield one prize. */
public final class Wheel {

    record NoSpin(@Example("2") int extras) {}

    record Available(@Example("3") int count) {}

    static final DocumentSpec<NoSpin> NONE = chat("wheel-none", NoSpin.class).titled("No spins left");
    static final DocumentSpec<Available> AVAILABLE =
            chat("wheel-available", Available.class).titled("Spins available");
    static final DocumentSpec<DocumentSpec.None> BROKEN_PRIZE = DocumentSpec.game(
                    "smp", "wheel-broken-prize", "Wheel", DocumentSpec.Shape.CHAT)
            .titled("Unknown prize");

    private static <V extends Record> DocumentSpec<V> chat(final String name, final Class<V> values) {
        return DocumentSpec.game("smp", name, "Wheel", DocumentSpec.Shape.CHAT, values);
    }

    private final Plugin plugin;
    private final SpinDao dao;
    private final ItemHandout handout;
    private final Executor itemWork;
    private final WheelSpec config;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final SmpSounds sounds;
    private final Dialogs dialogs;
    private final WheelTimetable timetable;
    private final Random random = new Random();

    /** The players whose wheel turns, from click to payout, each with the payout {@link #stop} runs. */
    private final Map<UUID, WheelTurn> turning = new ConcurrentHashMap<>();

    private final WheelFaces faces = new WheelFaces(random);

    /** Ends a player's turn on the spot: stops its clicks and pays as a turn that ran to its end. Closing calls it. */
    private final Map<UUID, Runnable> ending = new ConcurrentHashMap<>();

    /** The pop-in that still plays, per player, with the turn a spin starts after it; a spin or a close stops it. */
    private final Map<UUID, Scheduler.Task> popping = new ConcurrentHashMap<>();

    private final Clock clock;

    private static final Duration TICK = Duration.ofMillis(50);

    public Wheel(
            final Plugin plugin,
            final SpinDao dao,
            final ItemHandout handout,
            final Executor itemWork,
            final WheelSpec config,
            final Identities identities,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final SmpSounds sounds,
            final Dialogs dialogs,
            final WheelTimetable timetable,
            final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.dao = dao;
        this.handout = handout;
        this.itemWork = itemWork;
        this.config = config;
        this.identities = identities;
        this.renderer = renderer;
        this.documents = documents;
        this.sounds = sounds;
        this.dialogs = dialogs;
        this.timetable = timetable;
    }

    /**
     * Shows the player's wheel and waits for the button.
     * The prizes pop in; no spin is spent. Callable from the main thread; ignored while the player's wheel turns.
     */
    public void open(final Player player) {
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        final Locale locale = identities.languageOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            player.sendMessage(renderer.format(locale, Sent.of(SmpTexts.NO_ACCOUNT_LINK)));
            sounds.play(player, Feedback.REFUSED);
            return;
        }
        final UUID id = player.getUniqueId();
        if (turning.containsKey(id)) {
            return;
        }
        PaperScheduler.of(plugin).execute(() -> {
            final Spins spins = dao.spinsOf(discordId.get()).orElse(new Spins(0, 0, null));
            final int count = spins.available(LocalDate.now(clock));
            final WheelPlan plan =
                    WheelPlan.waiting(faces.next(id, config.wheelPrizes()).face());
            final List<ItemStack> stacks = shown(plan);
            PaperScheduler.of(plugin).onMain(() -> {
                if (!player.isOnline() || turning.containsKey(id)) {
                    return;
                }
                final Dialogs.Send send = dialogs.send();
                final ClickEvent press = count > 0 ? send.clickUnless(() -> turning.containsKey(id), this::spin) : null;
                player.setCooldown(WheelDialog.GROUP, timetable.open());
                player.showDialog(WheelDialog.of(
                        plan,
                        stacks,
                        stacks,
                        timetable.rows(),
                        texts(locale, count),
                        locale.getLanguage(),
                        count > 0,
                        press,
                        closing(send)));
                stopPopping(id);
                popping.put(id, pops(player, () -> popping.remove(id), Runnable::run));
            });
        });
    }

    /** The dialog's Esc and close button: closes the dialog, which the button leaves open, and ends a turn at once. */
    private DialogAction closing(final Dialogs.Send send) {
        return send.action((player, response) -> {
            player.closeDialog();
            close(player);
        });
    }

    /**
     * Stops the player's wheel: the pop-in's sounds end, and a turning wheel stops and pays its prize straight away.
     * Callable from the main thread; the dialog's close calls it, and so does anything else that closes the dialog.
     */
    public void close(final Player player) {
        final UUID id = player.getUniqueId();
        stopPopping(id);
        final Runnable end = ending.remove(id);
        if (end != null) {
            end.run();
        }
    }

    private void stopPopping(final UUID id) {
        final Scheduler.Task task = popping.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    /** Spends a spin and turns the player's wheel; the dialog's button calls it. Callable from the main thread. */
    public void spin(final Player player) {
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        final Locale locale = identities.languageOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            player.sendMessage(renderer.format(locale, Sent.of(SmpTexts.NO_ACCOUNT_LINK)));
            sounds.play(player, Feedback.REFUSED);
            return;
        }

        final UUID id = player.getUniqueId();
        final WheelTurn turn = new WheelTurn();
        if (turning.putIfAbsent(id, turn) != null) {
            return;
        }
        PaperScheduler.of(plugin)
                .execute(() -> guarded(id, turn, () -> {
                    final LocalDate today = LocalDate.now(clock);
                    final DiscordId account = discordId.get();
                    final Spins spins = dao.spinsOf(account).orElse(new Spins(0, 0, null));

                    synchronized (turn) {
                        if (turn.ended()) {
                            return;
                        }
                        if (!take(turn, account, spins, today)) {
                            finish(id, turn, false, () -> {});
                            tell(
                                    player,
                                    renderer.format(locale, NONE.with(new NoSpin(spins.extras()))),
                                    Feedback.REFUSED);
                            return;
                        }
                    }
                    award(player, account, locale, turn, Math.max(0, spins.available(today) - 1));
                }));
    }

    private boolean take(final WheelTurn turn, final DiscordId id, final Spins spins, final LocalDate today) {
        if (spins.hasFree(today)) {
            if (dao.takeFreeSpin(id, today).isEmpty()) {
                return false;
            }
            turn.spent(() -> refund(() -> dao.restoreFreeSpin(id, spins.lastFree(), today)));
        } else {
            if (dao.takeEarnedSpin(id).isEmpty()) {
                return false;
            }
            turn.spent(() -> refund(() -> dao.restoreEarnedSpin(id)));
        }
        return true;
    }

    private void guarded(final UUID id, final WheelTurn turn, final Runnable step) {
        if (turn.ended()) {
            return;
        }
        try {
            step.run();
        } catch (final RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "wheel turn failed: player=" + id, failure);
            if (Bukkit.isPrimaryThread()) {
                settle(id, turn);
            } else {
                try {
                    PaperScheduler.of(plugin).onMain(() -> settle(id, turn));
                } catch (final RejectedExecutionException stopping) {
                    // Shutdown settles every registered turn on the main thread.
                }
            }
        }
    }

    private void settle(final UUID id, final WheelTurn turn) {
        final Player player = Bukkit.getPlayer(id);
        finish(id, turn, player != null && player.isOnline(), () -> {});
    }

    private void finish(final UUID id, final WheelTurn turn, final boolean online, final Runnable cheer) {
        try {
            turn.end(online, cheer);
        } finally {
            turning.remove(id, turn);
        }
    }

    /** Spends no spin. */
    public void describe(final Player player) {
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        final Locale locale = identities.languageOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            return;
        }
        PaperScheduler.of(plugin).execute(() -> {
            final Spins spins = dao.spinsOf(discordId.get()).orElse(new Spins(0, 0, null));
            final int count = spins.available(LocalDate.now(clock));
            tell(player, renderer.format(locale, AVAILABLE.with(new Available(count))));
        });
    }

    private void award(
            final Player player,
            final DiscordId account,
            final Locale locale,
            final WheelTurn turn,
            final int spinsLeft) {
        final UUID id = player.getUniqueId();
        final List<WheelPrizeSpec> pool = config.wheelPrizes();
        final WheelFaces.Next next = faces.peek(id, pool);
        final WheelPlan.Face face = next.face();
        final WheelPlan plan;
        synchronized (turn) {
            if (turn.ended()) {
                return;
            }
            plan = WheelPlan.turning(face, face.draw(pool, timetable.variants(), random));
            final WheelPrizeSpec prize = plan.winner();
            final ItemStack item = itemOf(prize);
            if (item == null) {
                plugin.getLogger().warning("wheel prize item unreadable: item=" + prize.item());
                finish(id, turn, false, () -> {});
                tell(player, renderer.format(locale, Sent.of(BROKEN_PRIZE)), Feedback.LOSS);
                return;
            }
            drawReward(id, account, locale, turn, prize, item);
        }

        present(player, locale, turn, spinsLeft, next, plan);
    }

    private void drawReward(
            final UUID id,
            final DiscordId account,
            final Locale locale,
            final WheelTurn turn,
            final WheelPrizeSpec prize,
            final ItemStack item) {
        final int amount = Math.max(1, prize.amount());
        if (!prize.structure().isBlank()) {
            final Runnable store = () -> handout.storeMap(account, prize.item(), amount, prize.structure());
            turn.drawn(store, store);
        } else {
            final ItemStack stack = item.asQuantity(amount);
            turn.drawn(
                    () -> handout.give(Objects.requireNonNull(Bukkit.getPlayer(id)), stack, locale),
                    () -> handout.store(account, stack));
        }
    }

    private void present(
            final Player player,
            final Locale locale,
            final WheelTurn turn,
            final int spinsLeft,
            final WheelFaces.Next next,
            final WheelPlan plan) {
        final UUID id = player.getUniqueId();
        final List<ItemStack> stacks = shown(plan);
        final List<ItemStack> after = shown(WheelPlan.waiting(plan.after()));
        PaperScheduler.of(plugin)
                .onMain(() -> guarded(id, turn, () -> {
                    if (!player.isOnline()) {
                        settle(id, turn);
                        return;
                    }
                    final Dialogs.Send send = dialogs.send();
                    final ClickEvent again =
                            spinsLeft > 0 ? send.clickUnless(() -> turning.containsKey(id), this::spin) : null;
                    stopPopping(id);
                    final Runnable show = () -> {
                        player.setCooldown(WheelDialog.GROUP, timetable.cooldown());
                        player.showDialog(WheelDialog.of(
                                plan,
                                stacks,
                                null,
                                timetable.rows(),
                                texts(locale, spinsLeft),
                                locale.getLanguage(),
                                spinsLeft > 0,
                                again,
                                closing(send)));
                    };
                    if (next.drawn()) {
                        popIn(player, next.face(), stacks, locale, spinsLeft);
                    }
                    animate(player, plan, turn, next.drawn(), show, () -> {
                        if (player.isOnline()) {
                            player.showDialog(rested(player, plan, stacks, after, locale, spinsLeft));
                        }
                    });
                }));
    }

    /** Shows new prizes popping in before they turn, with Spin off: the wheel turns once they stand. */
    private void popIn(
            final Player player,
            final WheelPlan.Face face,
            final List<ItemStack> stacks,
            final Locale locale,
            final int spinsLeft) {
        player.setCooldown(WheelDialog.GROUP, timetable.open());
        player.showDialog(WheelDialog.of(
                WheelPlan.waiting(face),
                stacks,
                null,
                timetable.rows(),
                texts(locale, spinsLeft),
                locale.getLanguage(),
                false,
                null,
                closing(dialogs.send())));
    }

    /**
     * The turn's dialog again once its wheel rests: the same picture, with hovers over the prizes where they stopped.
     */
    private Dialog rested(
            final Player player,
            final WheelPlan plan,
            final List<ItemStack> stacks,
            final List<ItemStack> after,
            final Locale locale,
            final int spinsLeft) {
        final UUID id = player.getUniqueId();
        final Dialogs.Send send = dialogs.send();
        return WheelDialog.of(
                plan,
                stacks,
                after,
                timetable.rows(),
                texts(locale, spinsLeft),
                locale.getLanguage(),
                spinsLeft > 0,
                spinsLeft > 0 ? send.clickUnless(() -> turning.containsKey(id), this::spin) : null,
                closing(send));
    }

    /**
     * Shows and turns the wheel of {@code plan} with {@code show}, after the pop-in when {@code popIn}.
     * It pays the prize once the wheel rests, then runs {@code rest}. A close before then pays at once.
     */
    private void animate(
            final Player player,
            final WheelPlan plan,
            final WheelTurn turn,
            final boolean popIn,
            final Runnable show,
            final Runnable rest) {
        final UUID id = player.getUniqueId();
        final WheelFaces.Before before = faces.turning(id, plan.after());
        final Scheduler.Task[] clicks = {null};
        turn.clicks(() -> {
            ending.remove(id);
            stopPopping(id);
            if (clicks[0] != null) {
                clicks[0].cancel();
            }
            if (player.isOnline()) {
                faces.ended(id);
            } else {
                faces.back(id, before);
            }
        });
        final Runnable end = () -> {
            final Player recipient = Bukkit.getPlayer(id);
            finish(id, turn, recipient != null && recipient.isOnline(), () -> {
                if (player.isOnline()) {
                    sounds.play(player, Feedback.BIG_SUCCESS);
                }
            });
        };
        ending.put(id, end);
        final Consumer<Runnable> guard = step -> guarded(id, turn, step);
        final Runnable start = () -> {
            popping.remove(id);
            if (!player.isOnline()) {
                end.run();
                return;
            }
            show.run();
            clicks[0] = turn(
                    player,
                    plan.variant(),
                    () -> {
                        end.run();
                        rest.run();
                    },
                    guard);
        };
        if (popIn) {
            popping.put(id, pops(player, start, guard));
        } else {
            start.run();
        }
    }

    /** Plays the pop-in with its sounds, then {@code end}. */
    private Scheduler.Task pops(final Player player, final Runnable end, final Consumer<Runnable> guard) {
        final Set<Integer> pops = Set.copyOf(timetable.pops());
        return play(
                player,
                timetable.open(),
                tick -> {
                    if (pops.contains(tick)) {
                        sounds.play(player, Feedback.POP);
                    }
                },
                end,
                guard);
    }

    /** Plays the clicks of {@code variant}'s turn, then {@code end} once the wheel rests. */
    private Scheduler.Task turn(
            final Player player, final int variant, final Runnable end, final Consumer<Runnable> guard) {
        final Set<Integer> clicks = Set.copyOf(timetable.clicks().getOrDefault(variant, List.of()));
        return play(
                player,
                timetable.end(variant),
                tick -> {
                    if (clicks.contains(tick)) {
                        sounds.play(player, Feedback.COUNTDOWN_TICK);
                    }
                },
                end,
                guard);
    }

    /** Each body's prize as an item; one whose item does not read shows as a barrier. */
    private List<ItemStack> shown(final WheelPlan plan) {
        final List<ItemStack> stacks = new ArrayList<>(WheelPlan.PLACES);
        plan.bodies()
                .forEach(shown -> stacks.add(
                        Objects.requireNonNullElseGet(itemOf(shown), () -> new ItemStack(Material.BARRIER))));
        return stacks;
    }

    private WheelDialog.Texts texts(final Locale locale, final int spinsLeft) {
        final GameDocument document = new GameDocument(
                renderer,
                documents.applied(WheelDialog.DOCUMENT),
                Viewer.of(locale),
                new WheelDialog.Window(spinsLeft));
        return new WheelDialog.Texts(
                document.title(),
                Dialogs.plain(document.title()),
                document.label(spinsLeft > 0 ? "spins-left" : "no-spins-left"),
                document.label("spin-button"),
                document.label("spin-title"),
                document.label("spin-cost"),
                document.label("spin-none"),
                document.label("close"));
    }

    /**
     * Pays out every spin still turning and puts back every one taken but not shown yet.
     * Paper disables plugins before it disconnects players.
     */
    public void stop() {
        List.copyOf(popping.values()).forEach(Scheduler.Task::cancel);
        popping.clear();
        ending.clear();
        Map.copyOf(turning).forEach((id, turn) -> {
            try {
                settle(id, turn);
            } catch (final RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "wheel payout failed on shutdown: player=" + id, failure);
            }
        });
        turning.clear();
    }

    /**
     * Runs {@code each} on every tick from 0 until {@code ticks}, then {@code end} once; stops early with {@code end}
     * when the player leaves.
     */
    private Scheduler.Task play(
            final Player player,
            final int ticks,
            final IntConsumer each,
            final Runnable end,
            final Consumer<Runnable> guard) {
        final int[] tick = {0};
        final Scheduler.Task[] task = {null};
        task[0] = PaperScheduler.of(plugin)
                .onMainEvery(
                        Duration.ZERO,
                        TICK,
                        () -> guard.accept(() -> {
                            final int now = tick[0]++;
                            if (now >= ticks || !player.isOnline()) {
                                task[0].cancel();
                                end.run();
                                return;
                            }
                            each.accept(now);
                        }));
        return task[0];
    }

    /** Returns one of a prize's item: the whole stack an admin took in, else its material, or null if neither reads. */
    private static @Nullable ItemStack itemOf(final WheelPrizeSpec prize) {
        return TakenItems.one(prize.item(), prize.data());
    }

    private void refund(final Runnable work) {
        if (!Bukkit.isPrimaryThread()) {
            work.run();
        } else if (!plugin.isEnabled()) {
            CompletableFuture.runAsync(work, itemWork).join();
        } else {
            PaperScheduler.of(plugin).execute(work);
        }
    }

    private void tell(final Player player, final Component message) {
        tell(player, message, null);
    }

    private void tell(final Player player, final Component message, final @Nullable Feedback feedback) {
        PaperScheduler.of(plugin).onMain(() -> {
            if (player.isOnline()) {
                player.sendMessage(message);
                if (feedback != null) {
                    sounds.play(player, feedback);
                }
            }
        });
    }
}

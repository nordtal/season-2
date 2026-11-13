package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.GameLines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.packrendering.wheel.WheelTimetable;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.SmpTexts;
import eu.nordtal.season.smp.TakenItems;
import eu.nordtal.season.smp.config.WheelPrizeSpec;
import eu.nordtal.season.smp.config.WheelSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import io.papermc.paper.datacomponent.DataComponentTypes;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** The SQL update that spends a spin runs off the main thread, so two clicks yield one prize. */
public final class Wheel {

    record Won(
            @Example("16") int amount,
            @Example("item.minecraft.diamond") GameContent item) {}

    record NoSpin(@Example("2") int extras) {}

    record Available(@Example("3") int count) {}

    static final DocumentSpec<Won> WON = chat("wheel-won", Won.class).titled("Prize won");
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
    private final WheelSpec config;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final SmpSounds sounds;
    private final Dialogs dialogs;
    private final WheelTimetable timetable;
    private final Random random = new Random();

    /** The players whose wheel turns, from click to payout, each with the payout {@link #stop} runs. */
    private final Map<UUID, Runnable> turning = new ConcurrentHashMap<>();

    /** Each player's wheel: drawn on the first open and kept, turned by each spin, until the server stops. */
    private final Map<UUID, WheelPlan.Face> faces = new ConcurrentHashMap<>();

    /** The pop-in's sounds that still play, per player, which a spin stops. */
    private final Map<UUID, Scheduler.Task> popping = new ConcurrentHashMap<>();

    private final Clock clock;

    private static final Duration TICK = Duration.ofMillis(50);

    public Wheel(
            final Plugin plugin,
            final SpinDao dao,
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
            final WheelPlan plan = WheelPlan.waiting(face(id, config.wheelPrizes()));
            final List<ItemStack> stacks = shown(plan);
            PaperScheduler.of(plugin).onMain(() -> {
                if (!player.isOnline() || turning.containsKey(id)) {
                    return;
                }
                final ClickEvent press =
                        count > 0 ? dialogs.send().clickUnless(() -> turning.containsKey(id), this::spin) : null;
                player.setCooldown(WheelDialog.GROUP, timetable.open());
                player.showDialog(
                        WheelDialog.of(plan, stacks, texts(locale, count), locale.getLanguage(), count > 0, press));
                final Set<Integer> pops = Set.copyOf(timetable.pops());
                stopPopping(id);
                popping.put(
                        id,
                        play(
                                player,
                                timetable.open(),
                                tick -> {
                                    if (pops.contains(tick)) {
                                        sounds.play(player, Feedback.POP);
                                    }
                                },
                                () -> popping.remove(id)));
            });
        });
    }

    /** The player's wheel, drawn now if they have none yet or a prize on it left the pool. */
    private WheelPlan.Face face(final UUID id, final List<WheelPrizeSpec> pool) {
        return faces.compute(
                id, (key, face) -> face != null && face.drawnFrom(pool) ? face : WheelPlan.Face.random(pool, random));
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

        // One spin at a time: a click or a right-click while the wheel turns is ignored and spends nothing.
        final UUID uuid = player.getUniqueId();
        if (turning.putIfAbsent(uuid, () -> {}) != null) {
            return;
        }
        PaperScheduler.of(plugin).execute(() -> {
            final LocalDate today = LocalDate.now(clock);
            final Spins spins = dao.spinsOf(discordId.get()).orElse(new Spins(0, 0, null));

            final boolean free = spins.hasFree(today);
            final LocalDate previousFree = spins.lastFree();
            final boolean took = free
                    ? dao.takeFreeSpin(discordId.get(), today).isPresent()
                    : dao.takeEarnedSpin(discordId.get()).isPresent();

            if (!took) {
                turning.remove(uuid);
                tell(player, renderer.format(locale, NONE.with(new NoSpin(spins.extras()))), Feedback.REFUSED);
                return;
            }
            final String id = discordId.get().value();
            final Runnable refund = once(
                    free
                            ? () -> offThread(() -> dao.restoreFreeSpin(DiscordId.of(id), previousFree, today))
                            : () -> offThread(() -> dao.restoreEarnedSpin(DiscordId.of(id))));
            // Until the wheel shows, a shutdown puts the spin back.
            turning.replace(uuid, refund);
            final int spinsLeft = Math.max(0, spins.available(today) - 1);
            guarded(uuid, refund, () -> award(player, locale, refund, spinsLeft));
        });
    }

    /**
     * Runs {@code step} of a taken spin. If it throws, the spin is put back and the player can spin again.
     */
    private void guarded(final UUID uuid, final Runnable refund, final Runnable step) {
        try {
            step.run();
        } catch (final RuntimeException e) {
            turning.remove(uuid);
            refund.run();
            plugin.getLogger().log(Level.WARNING, "wheel spin failed, spin returned", e);
        }
    }

    /** {@code action}, run at most once however often it is called. */
    private static Runnable once(final Runnable action) {
        final AtomicBoolean ran = new AtomicBoolean();
        return () -> {
            if (ran.compareAndSet(false, true)) {
                action.run();
            }
        };
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

    private void award(final Player player, final Locale locale, final Runnable refund, final int spinsLeft) {
        final List<WheelPrizeSpec> pool = config.wheelPrizes();
        final UUID id = player.getUniqueId();
        // This part runs off the main thread.
        final WheelPlan.Face face = face(id, pool);
        final WheelPlan plan = WheelPlan.turning(face, face.draw(pool, timetable.variants(), random));
        final WheelPrizeSpec prize = plan.winner();
        final ItemStack item = itemOf(prize);
        if (item == null) {
            plugin.getLogger().warning("wheel prize item unreadable, spin returned: item=" + prize.item());
            refund.run();
            turning.remove(id);
            tell(player, renderer.format(locale, Sent.of(BROKEN_PRIZE)), Feedback.LOSS);
            return;
        }
        final List<ItemStack> stacks = shown(plan);

        PaperScheduler.of(plugin)
                .onMain(() -> guarded(id, refund, () -> {
                    if (!player.isOnline()) {
                        turning.remove(id);
                        give(player, item, prize.amount(), locale, refund);
                        return;
                    }
                    final ClickEvent again = spinsLeft > 0
                            ? dialogs.send().clickUnless(() -> turning.containsKey(id), this::spin)
                            : null;
                    stopPopping(id);
                    // The cooldown goes first, so the dialog's first frame already plays the turn.
                    player.setCooldown(WheelDialog.GROUP, timetable.cooldown());
                    player.showDialog(WheelDialog.of(
                            plan, stacks, texts(locale, spinsLeft), locale.getLanguage(), spinsLeft > 0, again));
                    final WheelPlan.Face before = faces.put(id, plan.after());
                    final AtomicBoolean paid = new AtomicBoolean();
                    final Runnable payout = () -> {
                        if (paid.compareAndSet(false, true)) {
                            turning.remove(id);
                            // A spin that goes back leaves the wheel as it was before it.
                            if (!player.isOnline() && before != null) {
                                faces.put(id, before);
                            }
                            give(player, item, prize.amount(), locale, refund);
                        }
                    };
                    turning.put(id, payout);
                    turn(player, plan.variant(), () -> {
                        if (player.isOnline() && !paid.get()) {
                            sounds.play(player, Feedback.BIG_SUCCESS);
                        }
                        payout.run();
                    });
                }));
    }

    /** Plays the clicks of {@code variant}'s turn, then {@code end} once the wheel rests. */
    private void turn(final Player player, final int variant, final Runnable end) {
        final Set<Integer> clicks = Set.copyOf(timetable.clicks().getOrDefault(variant, List.of()));
        play(
                player,
                timetable.end(variant),
                tick -> {
                    if (clicks.contains(tick)) {
                        sounds.play(player, Feedback.COUNTDOWN_TICK);
                    }
                },
                end);
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
                document.label("spin-none"));
    }

    /**
     * Pays out every spin still turning and puts back every one taken but not shown yet.
     * Paper disables plugins before it disconnects players.
     */
    public void stop() {
        List.copyOf(popping.values()).forEach(Scheduler.Task::cancel);
        popping.clear();
        List.copyOf(turning.values()).forEach(Runnable::run);
        turning.clear();
    }

    /**
     * Runs {@code each} on every tick from 0 until {@code ticks}, then {@code end} once; stops early with {@code end}
     * when the player leaves.
     */
    private Scheduler.Task play(final Player player, final int ticks, final IntConsumer each, final Runnable end) {
        final int[] tick = {0};
        final Scheduler.Task[] task = {null};
        task[0] = PaperScheduler.of(plugin).onMainEvery(Duration.ZERO, TICK, () -> {
            final int now = tick[0]++;
            if (now >= ticks || !player.isOnline()) {
                task[0].cancel();
                end.run();
                return;
            }
            each.accept(now);
        });
        return task[0];
    }

    /** The spin's end calls it once. It runs {@code refund} when the player is offline. */
    private void give(
            final Player player, final ItemStack item, final int amount, final Locale locale, final Runnable refund) {
        final int count = Math.max(1, amount);
        if (!player.isOnline()) {
            plugin.getLogger()
                    .warning("player left mid-spin, prize not given, spin returned: player=" + player.getName()
                            + " item=" + item.getType().name() + " count=" + count);
            refund.run();
            return;
        }
        final ItemStack stack = item.asQuantity(count);
        final Location dropAt = Objects.requireNonNull(player.getLocation());
        player.getInventory()
                .addItem(stack)
                .values()
                .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
        player.sendMessage(renderer.format(locale, WON.with(new Won(count, nameOf(item)))));
    }

    /**
     * Returns the name a won item is called by, the one Steward's prize tile shows of the stored item.
     * That is its custom name, else an item name of its own as a pack's item carries, else its material's.
     */
    static GameContent nameOf(final ItemStack item) {
        return named(
                item.getData(DataComponentTypes.CUSTOM_NAME),
                item.isDataOverridden(DataComponentTypes.ITEM_NAME) ? item.getData(DataComponentTypes.ITEM_NAME) : null,
                item.getType().translationKey());
    }

    /** Names the item by its custom name, else its own item name, else the material's under {@code materialKey}. */
    static GameContent named(
            final @Nullable Component customName, final @Nullable Component itemName, final String materialKey) {
        final Component name = customName != null ? customName : itemName;
        return name == null ? GameContent.of(materialKey) : GameLines.of(name);
    }

    /** Returns one of a prize's item: the whole stack an admin took in, else its material, or null if neither reads. */
    private static @Nullable ItemStack itemOf(final WheelPrizeSpec prize) {
        return TakenItems.one(prize.item(), prize.data());
    }

    /** It logs a refund that the shutdown rejects. */
    private void offThread(final Runnable work) {
        try {
            PaperScheduler.of(plugin).execute(work);
        } catch (final IllegalStateException | IllegalArgumentException refused) {
            plugin.getLogger().warning("wheel spin not returned, server stopping: cause=" + refused.getMessage());
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

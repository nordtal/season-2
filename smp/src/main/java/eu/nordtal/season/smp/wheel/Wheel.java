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
import java.util.Collections;
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

    /** What each player's wheel last showed, so the next spin swaps from it. */
    private final Map<UUID, WheelPlan.Face> faces = new ConcurrentHashMap<>();

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

    /** Callable from the main thread. */
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
            final Runnable refund = free
                    ? () -> offThread(() -> dao.restoreFreeSpin(DiscordId.of(id), previousFree, today))
                    : () -> offThread(() -> dao.restoreEarnedSpin(DiscordId.of(id)));
            award(player, locale, refund, Math.max(0, spins.available(today) - 1));
        });
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
        final List<Integer> weights = new ArrayList<>(pool.size());
        pool.forEach(prize -> weights.add(prize.weight()));

        final int index = PrizeDraw.draw(weights, random);
        final WheelPrizeSpec prize = pool.get(index);
        final ItemStack item = itemOf(prize);
        if (item == null) {
            plugin.getLogger()
                    .warning("wheel-prizes names '" + prize.item()
                            + "', which is not a material or whose item data does not read"
                            + " - the spin is being put back and nothing was given");
            refund.run();
            turning.remove(player.getUniqueId());
            tell(player, renderer.format(locale, Sent.of(BROKEN_PRIZE)), Feedback.LOSS);
            return;
        }

        // This part runs off the main thread.
        final List<Integer> variants = timetable.variants();
        final int variant = variants.get(random.nextInt(variants.size()));
        final WheelPlan.Face before = faces.getOrDefault(player.getUniqueId(), WheelPlan.Face.random(pool, random));
        final WheelPlan plan = WheelPlan.of(before, prize, others(pool, index), variant);
        final List<ItemStack> stacks = new ArrayList<>(WheelPlan.PLACES);
        plan.bodies()
                .forEach(shown -> stacks.add(
                        Objects.requireNonNullElseGet(itemOf(shown), () -> new ItemStack(Material.BARRIER))));

        PaperScheduler.of(plugin).onMain(() -> {
            if (!player.isOnline()) {
                turning.remove(player.getUniqueId());
                give(player, item, prize.amount(), locale, refund);
                return;
            }
            final WheelDialog.Texts texts = texts(locale, spinsLeft);
            final UUID id = player.getUniqueId();
            final ClickEvent again =
                    spinsLeft > 0 ? dialogs.send().clickUnless(() -> turning.containsKey(id), this::spin) : null;
            // The cooldown goes first, so the dialog's first frame already plays the spin.
            player.setCooldown(WheelDialog.GROUP, timetable.cooldown());
            player.showDialog(WheelDialog.of(plan, stacks, texts, locale.getLanguage(), spinsLeft > 0, again));
            faces.put(id, plan.after());
            final AtomicBoolean paid = new AtomicBoolean();
            final Runnable payout = () -> {
                if (paid.compareAndSet(false, true)) {
                    turning.remove(id);
                    give(player, item, prize.amount(), locale, refund);
                }
            };
            turning.put(id, payout);
            play(player, variant, () -> {
                if (player.isOnline() && !paid.get()) {
                    sounds.play(player, Feedback.BIG_SUCCESS);
                }
                payout.run();
            });
        });
    }

    private WheelDialog.Texts texts(final Locale locale, final int spinsLeft) {
        final GameDocument document = new GameDocument(
                renderer,
                documents.applied(WheelDialog.DOCUMENT),
                Viewer.of(locale),
                new WheelDialog.Window(spinsLeft));
        return new WheelDialog.Texts(
                Dialogs.plain(document.title()),
                document.label(spinsLeft > 0 ? "spins-left" : "no-spins-left"),
                document.label("again-button"),
                document.label("again-title"),
                document.label("again-cost"),
                document.label("again-none"));
    }

    /** Pays out every spin still turning; Paper disables plugins before it disconnects players. */
    public void stop() {
        List.copyOf(turning.values()).forEach(Runnable::run);
        turning.clear();
    }

    /** The 11 prizes the wheel shows beside the winner, each prize of the pool before any shows twice. */
    private List<WheelPrizeSpec> others(final List<WheelPrizeSpec> pool, final int winner) {
        final List<WheelPrizeSpec> rest = new ArrayList<>(pool);
        rest.remove(winner);
        if (rest.isEmpty()) {
            rest.add(pool.get(winner));
        }
        final List<WheelPrizeSpec> out = new ArrayList<>(WheelPlan.PLACES - 1);
        while (out.size() < WheelPlan.PLACES - 1) {
            final List<WheelPrizeSpec> round = new ArrayList<>(rest);
            Collections.shuffle(round, random);
            out.addAll(round.subList(0, Math.min(round.size(), WheelPlan.PLACES - 1 - out.size())));
        }
        return out;
    }

    /** Plays the spin's sounds on the timetable, a pop per place and a click per peg, then runs {@code end} once. */
    private void play(final Player player, final int variant, final Runnable end) {
        final Set<Integer> pops = Set.copyOf(timetable.pops());
        final Set<Integer> clicks = Set.copyOf(timetable.clicks().getOrDefault(variant, List.of()));
        final int[] tick = {0};
        final Scheduler.Task[] task = {null};
        task[0] = PaperScheduler.of(plugin).onMainEvery(Duration.ZERO, TICK, () -> {
            final int now = tick[0]++;
            if (now >= timetable.cooldown() || !player.isOnline()) {
                task[0].cancel();
                end.run();
                return;
            }
            if (pops.contains(now)) {
                sounds.play(player, Feedback.POP);
            }
            if (clicks.contains(now)) {
                sounds.play(player, Feedback.COUNTDOWN_TICK);
            }
        });
    }

    /** The spin's end calls it once. It runs {@code refund} when the player is offline. */
    private void give(
            final Player player, final ItemStack item, final int amount, final Locale locale, final Runnable refund) {
        final int count = Math.max(1, amount);
        if (!player.isOnline()) {
            plugin.getLogger()
                    .warning(player.getName() + " left mid-spin; " + count + "x "
                            + item.getType().name() + " could not be handed over, so the spin goes back");
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
            plugin.getLogger()
                    .warning("could not put a wheel spin back - the server is shutting" + " down: "
                            + refused.getMessage());
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

package eu.nordtal.season.smp.duel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The stake of one pair before the lock: both chests, both offers and the countdown to the lock. Main thread only.
 *
 * Every change clears both confirmations and stops the countdown, whose end hands both chests to {@link Events}.
 */
final class StakeTable implements StakeChest.Seat {

    record Aura(@Example("40") long amount) {}

    record Offer(PlayerContext opponent, @Example("25") long amount) {}

    record Seconds(@Example("3") long seconds) {}

    record Fighter(PlayerContext player) {}

    static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "duel-stake",
                    "Menus",
                    DocumentSpec.None.class,
                    StakePanel.ROWS,
                    Slot.label("ready"),
                    Slot.label("ready-hint"),
                    Slot.label("withdraw"),
                    Slot.label("aura-own", Aura.class),
                    Slot.label("aura-negative"),
                    Slot.label("offer-own", Aura.class),
                    Slot.label("raise"),
                    Slot.label("lower"),
                    Slot.label("name", Fighter.class),
                    Slot.label("their-offer", Offer.class),
                    Slot.label("their-ready", NegotiationDialog.Opponent.class),
                    Slot.label("alone"),
                    Slot.label("cursor"),
                    Slot.label("countdown", Seconds.class))
            .titled("Duel stake");

    /** What the table tells the negotiation. */
    interface Events {

        /**
         * The countdown ended and both chests are empty.
         *
         * {@code items} and {@code aura} hold what the accounts seated on each side offered.
         */
        void locked(List<DiscordId> accounts, List<List<ItemStack>> items, int[] aura);

        /** A player closed their chest with Esc. */
        void closed(Player player);

        /** A countdown to the lock stopped before its end. */
        void countdownStopped();
    }

    /** One side as a chest draws it. */
    record Side(UUID id, String name, int offer, int balance, boolean ready, List<ItemStack> deposits) {}

    private static final int COUNTDOWN_SECONDS = 3;

    private static final Duration SECOND = Duration.ofSeconds(1);

    private final Plugin plugin;
    private final Function<UUID, GameDocument> document;
    private final AuraDao aura;
    private final SmpSounds sounds;
    private final Events events;
    private final Escrow escrow;
    private final StakeState state = new StakeState();
    private final @Nullable UUID[] players = new UUID[2];
    private final String[] names = {"", ""};
    private final @Nullable DiscordId[] accounts = new DiscordId[2];
    private final @Nullable StakeChest[] chests = new StakeChest[2];
    private final boolean[] refused = new boolean[2];
    private Scheduler.@Nullable Task countdown;
    private Scheduler.@Nullable Task poll;
    private boolean over;

    StakeTable(
            final Plugin plugin,
            final Function<UUID, GameDocument> document,
            final AuraDao aura,
            final SmpSounds sounds,
            final Events events,
            final Escrow escrow) {
        this.plugin = plugin;
        this.document = document;
        this.aura = aura;
        this.sounds = sounds;
        this.events = events;
        this.escrow = escrow;
    }

    /** Seats {@code player} on {@code side} and opens their chest. */
    void seat(final int side, final Player player, final DiscordId account) {
        players[side] = player.getUniqueId();
        names[side] = player.getName();
        accounts[side] = account;
        final StakeChest chest = new StakeChest(side, this, viewOf(side), false);
        chests[side] = chest;
        redraw();
        chest.open(player);
        if (poll == null) {
            poll = PaperScheduler.of(plugin).onMainEvery(Duration.ZERO, SECOND, this::pollBalances);
        }
    }

    /** Opens the chest again for a player who closed it. */
    void reopen(final Player player) {
        for (final StakeChest chest : chests) {
            if (chest != null && player.getUniqueId().equals(players[chest.side()])) {
                chest.open(player);
            }
        }
    }

    boolean counting() {
        return countdown != null;
    }

    /**
     * Gives each player the items in their chest, and those {@code leaving} or offline through the pending store.
     *
     * A player who stays gets them into the inventory, and what does not fit drops at their feet.
     */
    void end(final Set<UUID> leaving) {
        if (over) {
            return;
        }
        over = true;
        cancel(countdown);
        cancel(poll);
        countdown = null;
        poll = null;
        for (final StakeChest chest : chests) {
            if (chest == null) {
                continue;
            }
            final int side = chest.side();
            final @Nullable UUID id = players[side];
            final @Nullable Player player = id == null ? null : Bukkit.getPlayer(id);
            final boolean shown = player != null && chest.showsTo(player);
            final List<ItemStack> items = chest.lock();
            if (player != null && !leaving.contains(player.getUniqueId())) {
                StakeChest.hand(player, items);
            } else {
                escrow.giveBack(Objects.requireNonNull(accounts[side]), items);
            }
            if (player != null && shown) {
                player.closeInventory();
            }
        }
    }

    /** Stops the countdown at once, so no tick locks before the click lands in the chest. */
    @Override
    public void deposited(final int side) {
        state.changed();
        stopCountdown();
        // The click lands in the inventory after its event, so the opponent sees it from the next tick on.
        PaperScheduler.of(plugin).onMain(() -> {
            if (!over) {
                redraw();
            }
        });
    }

    @Override
    public void step(final int side, final int delta) {
        refused[side] = false;
        if (state.step(side, delta)) {
            changed();
        }
    }

    @Override
    public void confirm(final int side, final Player player) {
        final StakeState.Refusal refusal =
                state.confirm(side, player.getItemOnCursor().isEmpty());
        refused[side] = refusal == StakeState.Refusal.CURSOR;
        sounds.play(player, refused[side] ? Feedback.REFUSED : Feedback.SMALL_SUCCESS);
        if (players[1] != null && state.startCountdown(COUNTDOWN_SECONDS)) {
            countdown = PaperScheduler.of(plugin).onMainEvery(SECOND, SECOND, this::tick);
            tickSound();
        }
        redraw();
    }

    @Override
    public void withdraw(final int side) {
        state.withdraw(side);
        changed();
    }

    @Override
    public void closed(final int side, final Player player) {
        state.withdraw(side);
        changed();
        events.closed(player);
    }

    /** Stops a running countdown and redraws both chests. */
    private void changed() {
        stopCountdown();
        redraw();
    }

    private void stopCountdown() {
        if (countdown != null) {
            cancel(countdown);
            countdown = null;
            events.countdownStopped();
        }
    }

    private void tick() {
        switch (state.tick()) {
            case COUNTING -> {
                tickSound();
                redraw();
            }
            case LOCK -> {
                cancel(countdown);
                countdown = null;
                lock();
            }
            case STOPPED -> stopCountdown();
        }
    }

    private void tickSound() {
        for (final UUID id : players) {
            final @Nullable Player player = id == null ? null : Bukkit.getPlayer(id);
            if (player != null) {
                sounds.play(player, Feedback.COUNTDOWN_TICK);
            }
        }
    }

    private void lock() {
        over = true;
        cancel(poll);
        poll = null;
        final List<List<ItemStack>> items = List.of(lockSide(0), lockSide(1));
        events.locked(
                List.of(Objects.requireNonNull(accounts[0]), Objects.requireNonNull(accounts[1])),
                items,
                new int[] {state.offer(0), state.offer(1)});
    }

    private List<ItemStack> lockSide(final int side) {
        final @Nullable StakeChest chest = chests[side];
        if (chest == null) {
            return List.of();
        }
        final List<ItemStack> taken = chest.lock();
        final @Nullable UUID id = players[side];
        final @Nullable Player player = id == null ? null : Bukkit.getPlayer(id);
        if (player != null && chest.showsTo(player)) {
            player.closeInventory();
        }
        return taken;
    }

    private void pollBalances() {
        for (int side = 0; side < 2; side++) {
            final int seated = side;
            final @Nullable DiscordId account = accounts[side];
            if (account == null) {
                continue;
            }
            try {
                PaperScheduler.of(plugin).execute(() -> {
                    final int balance = aura.auraOf(account).orElse(0);
                    PaperScheduler.of(plugin).onMain(() -> balanceIs(seated, balance));
                });
            } catch (final RejectedExecutionException stopping) {
                return;
            }
        }
    }

    private void balanceIs(final int side, final int balance) {
        if (over) {
            return;
        }
        final int before = state.balance(side);
        if (state.balanceIs(side, balance)) {
            changed();
        } else if (before != balance) {
            redraw();
        }
    }

    /** Draws both chests from the current state. */
    private void redraw() {
        for (final StakeChest chest : chests) {
            if (chest == null) {
                continue;
            }
            final int own = chest.side();
            final int other = 1 - own;
            final @Nullable UUID id = players[own];
            if (id == null) {
                continue;
            }
            final GameDocument words = document.apply(id);
            final Side mine = side(own);
            final @Nullable Side their = players[other] == null ? null : side(other);
            chest.draw(
                    Bukkit.getPlayer(id),
                    StakePanel.title(viewOf(own)),
                    display(words, mine, their, state.counting()),
                    state.ready(own),
                    state.counting());
        }
    }

    /** Returns the panel view of the player seated on {@code own}. */
    private StakePanel.View viewOf(final int own) {
        final GameDocument words = document.apply(Objects.requireNonNull(players[own]));
        final int other = 1 - own;
        final @Nullable Side their = players[other] == null ? null : side(other);
        final @Nullable String refusal = refused[own] ? words.label("cursor") : null;
        return view(words, side(own), their, state.seconds(), refusal);
    }

    private Side side(final int side) {
        final @Nullable StakeChest chest = chests[side];
        return new Side(
                Objects.requireNonNull(players[side]),
                names[side],
                state.offer(side),
                state.balance(side),
                state.ready(side),
                chest == null ? List.of() : chest.deposits());
    }

    static StakePanel.View view(
            final GameDocument words,
            final Side own,
            final @Nullable Side their,
            final int seconds,
            final @Nullable String refusal) {
        return new StakePanel.View(
                Dialogs.plain(words.title()),
                own.name(),
                their == null ? null : their.name(),
                own.offer(),
                own.balance(),
                their == null ? 0 : their.offer(),
                own.ready(),
                their != null && their.ready(),
                seconds,
                refusal,
                new StakePanel.Labels(
                        words.label("ready"), words.label("alone"), words.label("countdown", new Seconds(seconds))));
    }

    /** Returns the item of each drawn slot: heads, tooltips and the opponent's mirrored deposits. */
    static Map<Integer, ItemStack> display(
            final GameDocument words, final Side own, final @Nullable Side their, final boolean counting) {
        final Map<Integer, ItemStack> shown = new HashMap<>();
        final Component balance = own.balance() < 0
                ? words.labelText("aura-negative", null)
                : words.labelText("aura-own", new Aura(own.balance()));
        final PlayerContext self = PlayerContext.of(PlayerId.of(own.id()), own.name());
        shown.put(
                StakePanel.OWN_HEAD,
                BlankItem.styled(head(own.id()), words.labelText("name", new Fighter(self)), List.of(balance)));
        shown.put(StakePanel.LOWER, tip(words.labelText("lower", null)));
        // A + at the cap tells why it does nothing.
        shown.put(
                StakePanel.RAISE,
                tip(own.offer() < Math.max(0, own.balance()) ? words.labelText("raise", null) : balance));
        final ItemStack figure = BlankItem.styled(
                BlankItem.silent(), words.labelText("offer-own", new Aura(own.offer())), List.of(balance));
        StakePanel.OWN_FIGURE.forEach(slot -> shown.put(slot, figure));
        if (counting) {
            StakePanel.COUNTDOWN.forEach(slot -> shown.put(slot, BlankItem.silent()));
            shown.put(StakePanel.WITHDRAW, tip(words.labelText("withdraw", null)));
        } else {
            final ItemStack ready = own.ready()
                    ? tip(words.labelText("withdraw", null))
                    : BlankItem.styled(
                            BlankItem.silent(),
                            words.labelText("ready", null),
                            List.of(words.labelText("ready-hint", null)));
            StakePanel.READY.forEach(slot -> shown.put(slot, ready));
        }
        if (their == null) {
            return shown;
        }
        final PlayerContext opponent = PlayerContext.of(PlayerId.of(their.id()), their.name());
        final Component offer = words.labelText("their-offer", new Offer(opponent, their.offer()));
        shown.put(
                StakePanel.THEIR_HEAD,
                BlankItem.styled(head(their.id()), words.labelText("name", new Fighter(opponent)), List.of(offer)));
        final ItemStack theirFigure = tip(offer);
        StakePanel.THEIR_FIGURE.forEach(slot -> shown.put(slot, theirFigure));
        if (their.ready()) {
            shown.put(
                    StakePanel.THEIR_STATUS,
                    BlankItem.styled(
                            head(their.id()),
                            words.labelText("their-ready", new NegotiationDialog.Opponent(opponent)),
                            List.of()));
        }
        for (int index = 0; index < their.deposits().size() && index < StakePanel.THEIR_DEPOSIT.size(); index++) {
            shown.put(StakePanel.THEIR_DEPOSIT.get(index), their.deposits().get(index));
        }
        return shown;
    }

    /** Opens a read-only stake chest at its example state in {@code drafted}'s words for Steward's draft preview. */
    static void example(final GameDocument drafted, final Player reader) {
        final Side own = new Side(
                reader.getUniqueId(),
                reader.getName(),
                25,
                40,
                false,
                List.of(new ItemStack(Material.DIAMOND, 3), new ItemStack(Material.GOLDEN_APPLE, 2)));
        final Side their = new Side(
                UUID.nameUUIDFromBytes("Alex".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "Alex",
                10,
                10,
                true,
                List.of(new ItemStack(Material.EMERALD, 16)));
        final StakeChest preview = new StakeChest(0, new ReadOnly(), view(drafted, own, their, 0, null), true);
        preview.draw(
                null,
                StakePanel.title(view(drafted, own, their, 0, null)),
                display(drafted, own, their, false),
                false,
                false);
        for (int index = 0; index < own.deposits().size(); index++) {
            preview.put(index, own.deposits().get(index));
        }
        preview.open(reader);
    }

    /** The seat of a preview, which takes no click. */
    private static final class ReadOnly implements StakeChest.Seat {
        @Override
        public void deposited(final int side) {}

        @Override
        public void step(final int side, final int delta) {}

        @Override
        public void confirm(final int side, final Player player) {}

        @Override
        public void withdraw(final int side) {}

        @Override
        public void closed(final int side, final Player player) {}
    }

    private static ItemStack tip(final Component name) {
        return BlankItem.styled(BlankItem.silent(), name, List.of());
    }

    private static ItemStack head(final UUID player) {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(meta -> {
            if (meta instanceof final SkullMeta skull) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(player));
            }
        });
        return head;
    }

    private static void cancel(final Scheduler.@Nullable Task task) {
        if (task != null) {
            task.cancel();
        }
    }
}

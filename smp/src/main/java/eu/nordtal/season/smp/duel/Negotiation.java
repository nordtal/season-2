package eu.nordtal.season.smp.duel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.config.DuelKitSpec;
import eu.nordtal.season.smp.config.DuelsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.region.ConfigAreas;
import eu.nordtal.season.smp.region.PolygonArea;
import eu.nordtal.season.smp.region.PolygonAreas;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The two players on the negotiation area, their stake in the {@link StakeTable} and their kit, main thread only.
 *
 * A pair ends further than {@link #APART} blocks apart, offline, when a limit runs out or with the duel.
 */
final class Negotiation {

    /** @param fighting whether the pair in the area fights, rather than still negotiating */
    record Held(@Example("true") boolean fighting) {}

    static final DocumentSpec<Held> AREA_HELD = DocumentSpec.game(
                    "smp", "duel-area-held", "Duels", DocumentSpec.Shape.CHAT, Held.class)
            .titled("Negotiation area taken");

    /** @param reason why the duel cannot start */
    record Unavailable(@Example("arena-busy") Duels.Blocked reason) {}

    static final DocumentSpec<Unavailable> UNAVAILABLE = DocumentSpec.game(
                    "smp", "duel-unavailable", "Duels", DocumentSpec.Shape.CHAT, Unavailable.class)
            .titled("Duel unavailable");

    static final DocumentSpec<DocumentSpec.None> ENDED_FAR = DocumentSpec.game(
                    "smp", "duel-ended-far", "Duels", DocumentSpec.Shape.CHAT)
            .titled("Duel over, too far apart");

    static final DocumentSpec<NegotiationDialog.Opponent> ENDED_OFFLINE = DocumentSpec.game(
                    "smp", "duel-ended-offline", "Duels", DocumentSpec.Shape.CHAT, NegotiationDialog.Opponent.class)
            .titled("Duel over, opponent offline");

    static final DocumentSpec<DocumentSpec.None> ENDED_TIME = DocumentSpec.game(
                    "smp", "duel-ended-time", "Duels", DocumentSpec.Shape.CHAT)
            .titled("Duel over, time ran out");

    /** To a player who closed the chest or the dialog: a click on it opens it again. */
    static final DocumentSpec<DocumentSpec.None> OPEN = DocumentSpec.game(
                    "smp", "duel-open", "Duels", DocumentSpec.Shape.CHAT)
            .titled("Duel open");

    /** A pair further apart than this many blocks ends. */
    static final int APART = 16;

    /** The seconds from the agreed kit to the fight. */
    static final int COUNTDOWN_SECONDS = 3;

    /** Each phase has this long: the stake from the second player's step, the kits from the lock. */
    private static final Duration LIMIT = Duration.ofSeconds(60);

    private static final Duration SECOND = Duration.ofSeconds(1);

    private static final long NOTICE_INTERVAL_MILLIS = 3_000;

    private static final double PUSH = 0.8;

    private static final double LIFT = 0.35;

    /** Where a pair stands: filling the stake chests, waiting for the lock to be written, or picking a kit. */
    private enum Phase {
        STAKE,
        LOCKING,
        KITS
    }

    private final Plugin plugin;
    private final Duels duels;
    private final DuelsSpec config;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final Dialogs dialogs;
    private final SmpSounds sounds;
    private final Clock clock;
    private final AuraDao aura;
    private final Escrow escrow;
    private final NoticeThrottle notices = new NoticeThrottle(NOTICE_INTERVAL_MILLIS);

    private volatile PolygonAreas configuredAreas;
    private @Nullable UUID first;
    private @Nullable UUID second;
    private Phase phase = Phase.STAKE;
    private @Nullable StakeTable table;
    private final Map<UUID, Integer> picks = new HashMap<>();

    /** The stake held for the pair from the lock on; the lock's answer for another stake is stale. */
    private @Nullable UUID stake;

    private int[] staked = {0, 0};

    /** The kits as they were at the lock, so a settings change during the pick changes nothing for the pair. */
    private List<DuelKitSpec> kits = List.of();

    /** A player whose pair ended here negotiates again only after stepping off. */
    private final Set<UUID> left = new HashSet<>();

    /** A player who typed since the last send closed the dialog, and gets no redraw until reopening it. */
    private final Set<UUID> closed = new HashSet<>();

    private Scheduler.@Nullable Task countdown;
    private int seconds;
    private Scheduler.@Nullable Task timeout;

    /** The limit ran out during a countdown, which may finish; the pair ends if it stops. */
    private boolean late;

    Negotiation(
            final Plugin plugin,
            final Duels duels,
            final DuelsSpec config,
            final Identities identities,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Dialogs dialogs,
            final SmpSounds sounds,
            final Clock clock,
            final AuraDao aura,
            final Escrow escrow) {
        this.plugin = plugin;
        this.duels = duels;
        this.config = config;
        this.identities = identities;
        this.renderer = renderer;
        this.documents = documents;
        this.dialogs = dialogs;
        this.sounds = sounds;
        this.clock = clock;
        this.aura = aura;
        this.escrow = escrow;
        this.configuredAreas = ConfigAreas.ofDuel(config.negotiationArea());
    }

    void reload() {
        configuredAreas = ConfigAreas.ofDuel(config.negotiationArea());
    }

    PolygonAreas areas() {
        return configuredAreas;
    }

    boolean isOn(final Location at) {
        return areas().containsBlock(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    private boolean held() {
        return second != null || duels.fighting();
    }

    private boolean negotiates(final UUID player) {
        return player.equals(first) || player.equals(second);
    }

    private @Nullable UUID opponentOf(final UUID player) {
        return player.equals(first) ? second : player.equals(second) ? first : null;
    }

    /** Handles a move that changes the block a player stands in. */
    void moved(final PlayerMoveEvent event) {
        final Player player = event.getPlayer();
        final UUID id = player.getUniqueId();
        final boolean on = isOn(event.getTo());
        if (!on) {
            left.remove(id);
        }
        switch (AreaStep.of(
                player.getGameMode(),
                isOn(event.getFrom()),
                on,
                negotiates(id),
                left.contains(id),
                duels.isInArena(player),
                held(),
                first != null)) {
            case NONE -> {
                if (second != null && negotiates(id) && tooFar(event.getTo(), opponentOf(id))) {
                    end(Sent.of(ENDED_FAR), Sent.of(ENDED_FAR));
                }
            }
            case LEAVE -> end(null, null);
            case REFUSE -> {
                event.setCancelled(true);
                push(player);
                if (notices.allows(id, clock.millis())) {
                    tell(player, AREA_HELD.with(new Held(duels.fighting())), Feedback.REFUSED);
                }
            }
            case WAIT -> step(player, 0);
            case JOIN -> step(player, 1);
        }
    }

    /** Seats a player who stepped onto the area, once their account is linked. */
    private void step(final Player player, final int side) {
        final UUID id = player.getUniqueId();
        final Optional<DiscordId> account = identities.discordIdOf(id);
        if (account.isEmpty()) {
            left.add(id);
            tell(player, UNAVAILABLE.with(new Unavailable(Duels.Blocked.NOT_LINKED)), Feedback.REFUSED);
            return;
        }
        if (side == 0) {
            first = id;
            phase = Phase.STAKE;
            table = new StakeTable(plugin, this::stakeWords, aura, sounds, new TableEvents(), escrow);
        } else {
            second = id;
            timeout = PaperScheduler.of(plugin).onMainAfter(LIMIT, this::timedOut);
        }
        final @Nullable StakeTable seating = table;
        // Opening a window inside the move event lets Bukkit apply the move over it.
        PaperScheduler.of(plugin).onMain(() -> {
            final @Nullable Player online = Bukkit.getPlayer(id);
            if (seating != null && seating == table && negotiates(id) && online != null) {
                seating.seat(side, online, account.get());
            }
        });
    }

    /** Whether {@code at} is more than {@link #APART} blocks from {@code other}, or in another world. */
    private static boolean tooFar(final Location at, final @Nullable UUID other) {
        final @Nullable Player opponent = other == null ? null : Bukkit.getPlayer(other);
        if (opponent == null) {
            return false;
        }
        final Location there = Objects.requireNonNull(opponent.getLocation());
        final boolean sameWorld = there.getWorld().equals(at.getWorld());
        return apart(sameWorld, sameWorld ? there.distanceSquared(at) : 0);
    }

    /** Whether two negotiators {@code distanceSquared} apart, or in different worlds, end their pair. */
    static boolean apart(final boolean sameWorld, final double distanceSquared) {
        return !sameWorld || distanceSquared > (double) APART * APART;
    }

    /** What the stake table tells the pair. */
    private final class TableEvents implements StakeTable.Events {

        @Override
        public void locked(final List<DiscordId> accounts, final List<List<ItemStack>> items, final int[] offers) {
            lock(accounts, items, offers);
        }

        @Override
        public void closed(final Player player) {
            offerReopen(player);
        }

        @Override
        public void countdownStopped() {
            if (late) {
                end(Sent.of(ENDED_TIME), Sent.of(ENDED_TIME));
            }
        }
    }

    /** Writes both stakes for the accounts seated at the step; the kit pick starts once the write answers. */
    private void lock(final List<DiscordId> accounts, final List<List<ItemStack>> items, final int[] offers) {
        final @Nullable UUID one = first;
        final @Nullable UUID two = second;
        table = null;
        cancel(timeout);
        timeout = null;
        late = false;
        final UUID duel = UUID.randomUUID();
        final List<DuelStakes.Offer> held = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            held.add(new DuelStakes.Offer(
                    accounts.get(side),
                    offers[side],
                    items.get(side).stream().map(Escrow::staked).toList()));
        }
        if (one == null || two == null) {
            // The pair ended in the same tick: the items go back through the pending store.
            for (int side = 0; side < 2; side++) {
                escrow.giveBack(accounts.get(side), items.get(side));
            }
            return;
        }
        phase = Phase.LOCKING;
        stake = duel;
        staked = offers.clone();
        escrow.lock(duel, held, locked -> {
            try {
                PaperScheduler.of(plugin).onMain(() -> lockAnswered(duel, locked));
            } catch (final RejectedExecutionException stopping) {
                // The next start refunds the held stake.
            }
        });
    }

    private void lockAnswered(final UUID duel, final boolean locked) {
        if (!duel.equals(stake)) {
            if (locked) {
                escrow.refund(duel);
            }
            return;
        }
        if (!locked) {
            stake = null;
            final Sent<?> shortLine = UNAVAILABLE.with(new Unavailable(Duels.Blocked.STAKE_SHORT));
            end(shortLine, shortLine);
            return;
        }
        phase = Phase.KITS;
        kits = List.copyOf(shownKits());
        timeout = PaperScheduler.of(plugin).onMainAfter(LIMIT, this::timedOut);
        redraw(null);
    }

    private void picked(final Player player, final int kit) {
        final UUID id = player.getUniqueId();
        if (phase != Phase.KITS || !negotiates(id) || kit < 0 || kit >= kits.size()) {
            return;
        }
        picks.put(id, kit);
        if (agreed()) {
            if (countdown == null) {
                seconds = COUNTDOWN_SECONDS;
                countdown = PaperScheduler.of(plugin).onMainEvery(SECOND, SECOND, this::tick);
                tickSound();
            }
        } else if (countdown != null) {
            cancel(countdown);
            countdown = null;
            seconds = 0;
            if (late) {
                end(Sent.of(ENDED_TIME), Sent.of(ENDED_TIME));
                return;
            }
        }
        redraw(id);
    }

    /** A negotiator chatted or ran a command, so their dialog is closed: they get one line to open it again. */
    void typed(final Player player) {
        final UUID id = player.getUniqueId();
        if (phase == Phase.KITS && negotiates(id) && closed.add(id)) {
            offerReopen(player);
        }
    }

    private void offerReopen(final Player player) {
        final Dialogs.Send send = dialogs.send();
        player.sendMessage(renderer.format(identities.languageOf(player.getUniqueId()), Sent.of(OPEN))
                .clickEvent(send.click(this::reopen)));
    }

    private void reopen(final Player player) {
        final UUID id = player.getUniqueId();
        if (!negotiates(id)) {
            return;
        }
        final @Nullable StakeTable seated = table;
        if (phase == Phase.STAKE && seated != null) {
            seated.reopen(player);
        } else if (phase == Phase.KITS) {
            closed.remove(id);
            show(player, false);
        }
    }

    void quit(final Player player) {
        final UUID id = player.getUniqueId();
        notices.forget(id);
        left.remove(id);
        if (!negotiates(id)) {
            return;
        }
        final Sent<?> gone =
                ENDED_OFFLINE.with(new NegotiationDialog.Opponent(PlayerContext.of(PlayerId.of(id), player.getName())));
        // The one who quit gets nothing: only the one still online is told.
        end(id.equals(first) ? null : gone, id.equals(second) ? null : gone, Set.of(id));
    }

    /** Gives back every stake and forgets the pair, for a server stop. */
    void stop() {
        final Set<UUID> everyone = new HashSet<>();
        if (first != null) {
            everyone.add(first);
        }
        if (second != null) {
            everyone.add(second);
        }
        end(null, null, everyone);
    }

    /**
     * Ends the negotiation, gives back what the pair staked and tells each negotiator their line if one is given.
     *
     * A player still on the area negotiates again only after stepping off.
     */
    private void end(final @Nullable Sent<?> toFirst, final @Nullable Sent<?> toSecond) {
        end(toFirst, toSecond, Set.of());
    }

    /** @param leaving the players who quit or stop with the server, whose stake goes through the pending store */
    private void end(final @Nullable Sent<?> toFirst, final @Nullable Sent<?> toSecond, final Set<UUID> leaving) {
        cancel(countdown);
        cancel(timeout);
        countdown = null;
        timeout = null;
        seconds = 0;
        late = false;
        final boolean paired = second != null;
        final @Nullable UUID one = first;
        final @Nullable UUID two = second;
        final Phase was = phase;
        final @Nullable StakeTable seated = table;
        final @Nullable UUID held = stake;
        first = null;
        second = null;
        table = null;
        stake = null;
        phase = Phase.STAKE;
        picks.clear();
        kits = List.of();
        if (seated != null) {
            seated.end(leaving);
        }
        // A lock still being written finds no stake and refunds itself.
        if (was == Phase.KITS && held != null) {
            escrow.refund(held);
        }
        endFor(one, toFirst, paired, was == Phase.KITS);
        endFor(two, toSecond, paired, was == Phase.KITS);
    }

    private void endFor(
            final @Nullable UUID id, final @Nullable Sent<?> line, final boolean paired, final boolean dialog) {
        if (id == null) {
            return;
        }
        final boolean shown = !closed.remove(id);
        if (paired) {
            left.add(id);
        }
        final @Nullable Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return;
        }
        if (dialog && shown) {
            player.closeDialog();
        }
        if (line != null) {
            tell(player, line, Feedback.REFUSED);
        }
    }

    /** Whether a limit ends the pair at once; a countdown already running may finish first. */
    static boolean timeoutExpired(final boolean countdownRunning) {
        return !countdownRunning;
    }

    private void timedOut() {
        timeout = null;
        final @Nullable StakeTable seated = table;
        final boolean counting = countdown != null || (seated != null && seated.counting());
        if (timeoutExpired(counting)) {
            end(Sent.of(ENDED_TIME), Sent.of(ENDED_TIME));
        } else {
            late = true;
        }
    }

    private void tick() {
        seconds--;
        if (seconds > 0) {
            tickSound();
            redraw(null);
            return;
        }
        cancel(countdown);
        countdown = null;
        start();
    }

    private void tickSound() {
        for (final @Nullable UUID id : new UUID[] {first, second}) {
            final @Nullable Player player = id == null ? null : Bukkit.getPlayer(id);
            if (player != null) {
                sounds.play(player, Feedback.COUNTDOWN_TICK);
            }
        }
    }

    /** Hands the pair and its stake to the duel; a duel that cannot start refunds the stake. */
    private void start() {
        final @Nullable UUID one = first;
        final @Nullable UUID two = second;
        final @Nullable Integer kit = one == null ? null : picks.get(one);
        final @Nullable Player fighter = one == null ? null : Bukkit.getPlayer(one);
        final @Nullable Player other = two == null ? null : Bukkit.getPlayer(two);
        final @Nullable UUID held = stake;
        final int[] offers = staked;
        final List<DuelKitSpec> frozen = kits;
        // The duel owns the stake from here, so the end below gives nothing back.
        stake = null;
        end(null, null);
        left.remove(one);
        left.remove(two);
        if (held == null) {
            return;
        }
        if (fighter == null || other == null || kit == null) {
            escrow.refund(held);
            return;
        }
        final Duels.@Nullable Blocked blocked = duels.begin(fighter, other, frozen.get(kit), held, offers);
        if (blocked != null) {
            escrow.refund(held);
            tell(fighter, UNAVAILABLE.with(new Unavailable(blocked)), Feedback.REFUSED);
            tell(other, UNAVAILABLE.with(new Unavailable(blocked)), Feedback.REFUSED);
        }
    }

    private boolean agreed() {
        final @Nullable Integer mine = first == null ? null : picks.get(first);
        return mine != null && second != null && mine.equals(picks.get(second));
    }

    private static void cancel(final Scheduler.@Nullable Task task) {
        if (task != null) {
            task.cancel();
        }
    }

    /** Sends the dialog to each negotiator who has it open, with the redraw title for all but {@code cause}. */
    private void redraw(final @Nullable UUID cause) {
        if (first != null) {
            redrawFor(first, cause);
        }
        if (second != null) {
            redrawFor(second, cause);
        }
    }

    private void redrawFor(final UUID id, final @Nullable UUID cause) {
        final @Nullable Player player = Bukkit.getPlayer(id);
        if (player != null && !closed.contains(id)) {
            show(player, cause != null && !cause.equals(id));
        }
    }

    private void show(final Player viewer, final boolean redrawn) {
        final UUID id = viewer.getUniqueId();
        final @Nullable UUID otherId = opponentOf(id);
        final @Nullable Player other = otherId == null ? null : Bukkit.getPlayer(otherId);
        final Locale locale = identities.languageOf(id);
        final GameDocument document = new GameDocument(
                renderer, documents.applied(NegotiationDialog.DOCUMENT), Viewer.of(locale), new DocumentSpec.None());
        viewer.showDialog(NegotiationDialog.of(
                document,
                new NegotiationDialog.View(
                        shown(kits, locale),
                        id,
                        picks.get(id),
                        otherId == null ? null : picks.get(otherId),
                        otherId,
                        otherId == null || other == null
                                ? null
                                : PlayerContext.of(PlayerId.of(otherId), other.getName()),
                        countdown == null ? 0 : seconds,
                        redrawn),
                dialogs.send(),
                this::picked));
    }

    /** Returns the kits as a player of {@code locale} reads them. */
    static List<NegotiationDialog.Kit> shown(final List<DuelKitSpec> kits, final Locale locale) {
        return kits.stream()
                .map(kit -> new NegotiationDialog.Kit(
                        nameIn(kit, locale), kit.icon(), NegotiationDialog.loadout(kit.loadout())))
                .toList();
    }

    /** Returns the kit's name a player of {@code locale} reads, the English one when no German one is set. */
    static String nameIn(final DuelKitSpec kit, final Locale locale) {
        return locale.getLanguage().equals("de") && !kit.nameDe().isBlank() ? kit.nameDe() : kit.name();
    }

    private GameDocument stakeWords(final UUID player) {
        return new GameDocument(
                renderer,
                documents.applied(StakeTable.DOCUMENT),
                Viewer.of(identities.languageOf(player)),
                new DocumentSpec.None());
    }

    private List<DuelKitSpec> shownKits() {
        final List<DuelKitSpec> configured = config.duelKits();
        return configured.subList(0, Math.min(configured.size(), NegotiationDialog.KITS));
    }

    /** Pushes a player away from the middle of the area, or backwards when standing on it. */
    private void push(final Player player) {
        final Location at = Objects.requireNonNull(player.getLocation());
        final PolygonAreas areas = areas();
        final PolygonArea area = areas.atBlock(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ())
                .orElse(areas.isEmpty() ? null : areas.all().getFirst());
        final double[] middle = area == null ? new double[] {at.getX(), at.getZ()} : area.centre();
        Vector away = new Vector(at.getX() - middle[0], 0, at.getZ() - middle[1]);
        if (away.lengthSquared() < 0.01) {
            away = at.getDirection().setY(0).multiply(-1);
        }
        if (away.lengthSquared() < 0.01) {
            away = new Vector(1, 0, 0);
        }
        player.setVelocity(away.normalize().multiply(PUSH).setY(LIFT));
    }

    private void tell(final Player player, final Sent<?> message, final Feedback feedback) {
        player.sendMessage(renderer.format(identities.languageOf(player.getUniqueId()), message));
        sounds.play(player, feedback);
    }
}

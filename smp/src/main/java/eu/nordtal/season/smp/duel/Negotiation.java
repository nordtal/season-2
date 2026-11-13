package eu.nordtal.season.smp.duel;

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
import eu.nordtal.season.smp.config.DuelKitSpec;
import eu.nordtal.season.smp.config.DuelsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.region.Box;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The two players on the negotiation area and the kits they pick, main thread only; a held area pushes others back.
 *
 * Esc ends nothing. A pair ends further than {@link #APART} blocks apart, offline, after a minute or with the duel.
 */
final class Negotiation {

    static final DocumentSpec<DocumentSpec.None> AREA_HELD = DocumentSpec.game(
                    "smp", "duel-area-held", "Duels", DocumentSpec.Shape.CHAT)
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

    /** To a player who closed the dialog, under their chat line: a click on it opens the dialog again. */
    static final DocumentSpec<DocumentSpec.None> OPEN = DocumentSpec.game(
                    "smp", "duel-open", "Duels", DocumentSpec.Shape.CHAT)
            .titled("Duel open");

    /** A pair further apart than this many blocks ends. */
    static final int APART = 16;

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final Duration COUNTDOWN = Duration.ofSeconds(3);

    private static final long NOTICE_INTERVAL_MILLIS = 3_000;

    private static final double PUSH = 0.8;

    private static final double LIFT = 0.35;

    private final Plugin plugin;
    private final Duels duels;
    private final DuelsSpec config;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final Dialogs dialogs;
    private final SmpSounds sounds;
    private final Clock clock;
    private final NoticeThrottle notices = new NoticeThrottle(NOTICE_INTERVAL_MILLIS);

    private @Nullable UUID first;
    private @Nullable UUID second;
    private final Map<UUID, Integer> picks = new HashMap<>();

    /** A player whose pair ended here negotiates again only after stepping off. */
    private final Set<UUID> left = new HashSet<>();

    /** A negotiator who typed since the last send closed the dialog, and gets no redraw until reopening it. */
    private final Set<UUID> closed = new HashSet<>();

    private Scheduler.@Nullable Task countdown;
    private Scheduler.@Nullable Task timeout;

    Negotiation(
            final Plugin plugin,
            final Duels duels,
            final DuelsSpec config,
            final Identities identities,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Dialogs dialogs,
            final SmpSounds sounds,
            final Clock clock) {
        this.plugin = plugin;
        this.duels = duels;
        this.config = config;
        this.identities = identities;
        this.renderer = renderer;
        this.documents = documents;
        this.dialogs = dialogs;
        this.sounds = sounds;
        this.clock = clock;
    }

    Box area() {
        return Duels.box(config.negotiationArea());
    }

    boolean isOn(final Location at) {
        return area().contains(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
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
                    tell(player, Sent.of(AREA_HELD), Feedback.REFUSED);
                }
            }
            case WAIT -> {
                first = id;
                // Opening a dialog inside the move event lets Bukkit apply the move over it.
                PaperScheduler.of(plugin).onMain(() -> redraw(null));
            }
            case JOIN -> {
                second = id;
                timeout = PaperScheduler.of(plugin).onMainAfter(TIMEOUT, this::timedOut);
                PaperScheduler.of(plugin).onMain(() -> redraw(id));
            }
        }
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

    private void picked(final Player player, final int kit) {
        final UUID id = player.getUniqueId();
        if (!negotiates(id) || kit < 0 || kit >= shownKits().size()) {
            return;
        }
        picks.put(id, kit);
        if (agreed()) {
            if (countdown == null) {
                countdown = PaperScheduler.of(plugin).onMainAfter(COUNTDOWN, this::tick);
            }
        } else {
            cancel(countdown);
            countdown = null;
        }
        redraw(id);
    }

    /** A negotiator chatted or ran a command, so their dialog is closed: they get one line to open it again. */
    void typed(final Player player) {
        final UUID id = player.getUniqueId();
        if (!negotiates(id) || !closed.add(id)) {
            return;
        }
        final Dialogs.Send send = dialogs.send();
        player.sendMessage(
                renderer.format(identities.languageOf(id), Sent.of(OPEN)).clickEvent(send.click(this::reopen)));
    }

    private void reopen(final Player player) {
        final UUID id = player.getUniqueId();
        closed.remove(id);
        if (negotiates(id)) {
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
        end(id.equals(first) ? null : gone, id.equals(second) ? null : gone);
    }

    /** Closes every negotiator's dialog and forgets the pair, for a server stop. */
    void stop() {
        end(null, null);
    }

    /**
     * Ends the negotiation, telling each negotiator their line if one is given.
     *
     * It closes each open dialog. A player still on the area negotiates again only after stepping off.
     */
    private void end(final @Nullable Sent<?> toFirst, final @Nullable Sent<?> toSecond) {
        cancel(countdown);
        cancel(timeout);
        countdown = null;
        timeout = null;
        final boolean paired = second != null;
        final @Nullable UUID one = first;
        final @Nullable UUID two = second;
        first = null;
        second = null;
        picks.clear();
        endFor(one, toFirst, paired);
        endFor(two, toSecond, paired);
    }

    private void endFor(final @Nullable UUID id, final @Nullable Sent<?> line, final boolean paired) {
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
        if (shown) {
            player.closeDialog();
        }
        if (line != null) {
            tell(player, line, Feedback.REFUSED);
        }
    }

    /** Whether the one-minute limit ends a pair whose countdown was running when the timer fired. */
    static boolean timeoutExpired(final boolean countdownRunning) {
        return true;
    }

    private void timedOut() {
        timeout = null;
        if (timeoutExpired(countdown != null)) {
            end(Sent.of(ENDED_TIME), Sent.of(ENDED_TIME));
        }
    }

    /** The countdown's own task, a tick after any event, starts the duel. */
    private void tick() {
        countdown = null;
        start();
    }

    private void start() {
        final @Nullable UUID one = first;
        final @Nullable UUID two = second;
        final @Nullable Integer kit = one == null ? null : picks.get(one);
        final @Nullable Player fighter = one == null ? null : Bukkit.getPlayer(one);
        final @Nullable Player other = two == null ? null : Bukkit.getPlayer(two);
        end(null, null);
        left.remove(one);
        left.remove(two);
        if (fighter == null || other == null || kit == null) {
            return;
        }
        final Duels.@Nullable Blocked blocked =
                duels.begin(fighter, other, shownKits().get(kit));
        if (blocked != null) {
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

    /** Sends the dialog to each negotiator who has it open, with no redraw title for {@code cause}. */
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
        final GameDocument document = new GameDocument(
                renderer,
                documents.applied(NegotiationDialog.DOCUMENT),
                Viewer.of(identities.languageOf(id)),
                new DocumentSpec.None());
        viewer.showDialog(NegotiationDialog.of(
                document,
                new NegotiationDialog.View(
                        shownKits().stream().map(DuelKitSpec::name).toList(),
                        picks.get(id),
                        other == null ? null : picks.get(other.getUniqueId()),
                        other == null ? null : PlayerContext.of(PlayerId.of(other.getUniqueId()), other.getName()),
                        redrawn),
                dialogs.send(),
                this::picked));
    }

    private List<DuelKitSpec> shownKits() {
        final List<DuelKitSpec> kits = config.duelKits();
        return kits.subList(0, Math.min(kits.size(), NegotiationDialog.KITS));
    }

    /** Pushes a player away from the middle of the area, or backwards when standing on it. */
    private void push(final Player player) {
        final Box box = area();
        final Location at = Objects.requireNonNull(player.getLocation());
        final double middleX = (box.minX() + box.maxX() + 1) / 2.0;
        final double middleZ = (box.minZ() + box.maxZ() + 1) / 2.0;
        Vector away = new Vector(at.getX() - middleX, 0, at.getZ() - middleZ);
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

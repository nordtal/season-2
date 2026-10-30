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
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.config.DuelKitSpec;
import eu.nordtal.season.smp.config.DuelsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.region.Box;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
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
 * The two players on the negotiation area and the kits they pick. Main thread only.
 *
 * While two players negotiate or a duel runs, the area is held and a third player is pushed back.
 */
final class Negotiation {

    static final DocumentSpec<DocumentSpec.None> AREA_HELD = DocumentSpec.game(
                    "smp", "duel-area-held", "Duels", DocumentSpec.Shape.CHAT)
            .titled("Negotiation area taken");

    static final DocumentSpec<DocumentSpec.None> UNAVAILABLE = DocumentSpec.game(
                    "smp", "duel-unavailable", "Duels", DocumentSpec.Shape.CHAT)
            .titled("Duel unavailable");

    /** The countdown bar has this many cells, one per step. */
    static final int STEPS = 6;

    private static final Duration STEP = Duration.ofMillis(500);

    private static final long NOTICE_INTERVAL_MILLIS = 3_000;

    private static final double PUSH = 0.8;

    private static final double LIFT = 0.35;

    private final Plugin plugin;
    private final Duels duels;
    private final DuelsSpec config;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final SmpSounds sounds;
    private final Clock clock;
    private final NoticeThrottle notices = new NoticeThrottle(NOTICE_INTERVAL_MILLIS);

    private @Nullable UUID first;
    private @Nullable UUID second;
    private final Map<UUID, Integer> picks = new HashMap<>();
    private final Map<UUID, NegotiationWindow> windows = new HashMap<>();

    /** A player here closed the window and negotiates again only after stepping off. */
    private final Set<UUID> left = new HashSet<>();

    private Scheduler.@Nullable Task countdown;
    private int step;

    Negotiation(
            final Plugin plugin,
            final Duels duels,
            final DuelsSpec config,
            final Identities identities,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final SmpSounds sounds,
            final Clock clock) {
        this.plugin = plugin;
        this.duels = duels;
        this.config = config;
        this.identities = identities;
        this.renderer = renderer;
        this.documents = documents;
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
            case NONE -> {}
            case LEAVE -> leave(player, false);
            case REFUSE -> {
                event.setCancelled(true);
                push(player);
                if (notices.allows(id, clock.millis())) {
                    tell(player, Sent.of(AREA_HELD), Feedback.REFUSED);
                }
            }
            case WAIT -> {
                first = id;
                tell(player, Sent.of(Duels.WAITING), Feedback.SELECT);
            }
            case JOIN -> {
                second = id;
                // Opening a window inside the move event lets Bukkit apply the move over it.
                PaperScheduler.of(plugin).onMain(this::redraw);
            }
        }
    }

    void picked(final Player player, final int kit) {
        final UUID id = player.getUniqueId();
        if (second == null || !negotiates(id) || kit < 0 || kit >= shownKits().size()) {
            return;
        }
        picks.put(id, kit);
        sounds.play(player, Feedback.SELECT);
        if (agreed()) {
            if (countdown == null) {
                step = 0;
                countdown = PaperScheduler.of(plugin).onMainEvery(STEP, STEP, this::tick);
            }
        } else {
            cancelCountdown();
        }
        redraw();
    }

    /** The window of a negotiating player was closed by the player. */
    void closed(final Player player) {
        final UUID id = player.getUniqueId();
        if (!negotiates(id)) {
            return;
        }
        windows.remove(id);
        left.add(id);
        leave(player, true);
    }

    void quit(final Player player) {
        notices.forget(player.getUniqueId());
        left.remove(player.getUniqueId());
        if (negotiates(player.getUniqueId())) {
            leave(player, false);
        }
    }

    /** Closes every window and forgets the players, for a server stop. */
    void stop() {
        cancelCountdown();
        final List<UUID> players = new ArrayList<>(windows.keySet());
        first = null;
        second = null;
        picks.clear();
        windows.clear();
        players.forEach(id -> {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.closeInventory();
            }
        });
    }

    private void leave(final Player player, final boolean push) {
        final UUID id = player.getUniqueId();
        cancelCountdown();
        picks.clear();
        final UUID other = id.equals(first) ? second : first;
        first = other;
        second = null;
        final NegotiationWindow window = windows.remove(id);
        if (window != null) {
            player.closeInventory();
        }
        if (push) {
            push(player);
        }
        if (other != null && windows.containsKey(other)) {
            redraw();
        }
    }

    private void tick() {
        step++;
        if (step < STEPS) {
            redraw();
            return;
        }
        cancelCountdown();
        start();
    }

    private void start() {
        final UUID firstId = first;
        final UUID secondId = second;
        final Integer kit = firstId == null ? null : picks.get(firstId);
        first = null;
        second = null;
        picks.clear();
        windows.clear();
        if (firstId == null || secondId == null || kit == null) {
            return;
        }
        final Player one = Bukkit.getPlayer(firstId);
        final Player two = Bukkit.getPlayer(secondId);
        if (one == null || two == null) {
            return;
        }
        one.closeInventory();
        two.closeInventory();
        if (!duels.begin(one, two, shownKits().get(kit))) {
            tell(one, Sent.of(UNAVAILABLE), Feedback.REFUSED);
            tell(two, Sent.of(UNAVAILABLE), Feedback.REFUSED);
        }
    }

    private boolean agreed() {
        final Integer mine = first == null ? null : picks.get(first);
        return mine != null && second != null && mine.equals(picks.get(second));
    }

    private void cancelCountdown() {
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
        step = 0;
    }

    /** Opens or redraws the window of each negotiating player. */
    private void redraw() {
        for (final UUID id : new UUID[] {first, second}) {
            if (id == null) {
                continue;
            }
            final Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            final UUID otherId = id.equals(first) ? second : first;
            final Player other = otherId == null ? null : Bukkit.getPlayer(otherId);
            if (other == null && !windows.containsKey(id)) {
                // Standing alone without a window: the chat line already said so.
                continue;
            }
            final NegotiationWindow next = window(player, other);
            final NegotiationWindow open = windows.put(id, next);
            if (open == null) {
                next.open(player);
            } else {
                open.replaceWith(player, next);
            }
        }
    }

    private NegotiationWindow window(final Player viewer, final @Nullable Player other) {
        final GameDocument document = new GameDocument(
                renderer,
                documents.applied(NegotiationWindow.DOCUMENT),
                Viewer.of(identities.languageOf(viewer.getUniqueId())),
                new DocumentSpec.None());
        final List<NegotiationWindow.Shown> kits = shownKits().stream()
                .map(kit -> new NegotiationWindow.Shown(
                        kit.name(), GameKeys.material(kit.icon()).orElse(null)))
                .toList();
        return new NegotiationWindow(
                this,
                document,
                new NegotiationWindow.View(
                        kits,
                        picks.get(viewer.getUniqueId()),
                        other == null ? null : picks.get(other.getUniqueId()),
                        other == null ? null : PlayerContext.of(PlayerId.of(other.getUniqueId()), other.getName()),
                        step,
                        STEPS));
    }

    private List<DuelKitSpec> shownKits() {
        final List<DuelKitSpec> kits = config.duelKits();
        return kits.subList(0, Math.min(kits.size(), NegotiationPanel.KIT_ROWS));
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

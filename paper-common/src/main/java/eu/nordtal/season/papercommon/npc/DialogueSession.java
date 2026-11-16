package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.packrendering.npc.DialogueScreen;
import eu.nordtal.season.papercommon.hud.Hud;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Carries one player's {@link DialogueRun} out on the server: the bars, the panel, zoom, vignette, camera and hotbar.
 * Call it on the main thread only; {@link #stopNow} puts everything back.
 */
final class DialogueSession {

    /** The look point above an NPC's feet. */
    static final double LOOK_HEIGHT = 1.05;

    static final int ACTION_BAR_EVERY = 20;
    static final int VIGNETTE_EVERY = 10;

    private final Player player;
    private final Npc npc;
    private final DialogueRun run;
    private final Feel feel;
    private final Hud hud;
    private final DialogueSounds sounds;
    private final NamespacedKey zoomKey;
    private final double startDistance;
    private final CameraEase camera;

    private @Nullable Frame drawn;
    private int sinceActionBar;
    private int sinceVignette;
    private int vignette;
    private double zoom;
    private int heldSlot = -1;
    private boolean movingSlot;

    DialogueSession(
            final Player player,
            final Npc npc,
            final Mannequin figure,
            final DialogueRun run,
            final Feel feel,
            final Hud hud,
            final DialogueSounds sounds,
            final NamespacedKey zoomKey) {
        this.player = player;
        this.npc = npc;
        this.run = run;
        this.feel = feel;
        this.hud = hud;
        this.sounds = sounds;
        this.zoomKey = zoomKey;
        this.startDistance = horizontal(player.getEyeLocation(), figure.getLocation());
        this.camera = new CameraEase(player.getYaw(), player.getPitch());
        hud.hold(player);
    }

    Player player() {
        return player;
    }

    Npc npc() {
        return npc;
    }

    DialogueRun run() {
        return run;
    }

    /** Returns whether the hotbar is being moved by the session itself, so its event is not an input. */
    boolean movingSlot() {
        return movingSlot;
    }

    /** Runs one tick at {@code figure} and returns the effects the caller carries out: hand-offs and the end. */
    List<Effect> tick(final Mannequin figure) {
        final Location look = figure.getLocation().add(0, LOOK_HEIGHT, 0);
        final Location eye = player.getEyeLocation();
        final double dx = look.getX() - eye.getX();
        final double dy = look.getY() - eye.getY();
        final double dz = look.getZ() - eye.getZ();
        final Where where = Gaze.where(
                startDistance, dx, dz, horizontal(player.getEyeLocation(), figure.getLocation()), player.getYaw());
        final Frame frame = run.tick(where);
        draw(frame);
        if (run.listening()) {
            final CameraEase.Turn turn =
                    camera.tick(player.getYaw(), player.getPitch(), Gaze.yawTo(dx, dz), Gaze.pitchTo(dx, dy, dz));
            if (turn != null) {
                ViewPackets.turn(player, turn);
            }
        }
        final List<Effect> left = new ArrayList<>();
        for (final Effect effect : frame.effects()) {
            switch (effect) {
                case Effect.Sound sound -> sounds.play(player, sound.cue(), npc.pitch());
                case Effect.HoldSlot _ -> holdSlot();
                case Effect.ReleaseSlot _ -> releaseSlot();
                case Effect.HandOff handOff -> left.add(handOff);
                case Effect.End end -> left.add(end);
            }
        }
        return left;
    }

    /** Puts back everything the session changed, at once. */
    void stopNow() {
        player.sendActionBar(Component.empty());
        if (vignette > 0) {
            ViewPackets.vignette(player, 0);
            vignette = 0;
        }
        zoom(0.0);
        releaseSlot();
        hud.release(player);
    }

    private void draw(final Frame frame) {
        sinceActionBar++;
        if (drawn == null || !frame.drawsLike(drawn) || sinceActionBar >= ACTION_BAR_EVERY) {
            hud.cover(player, frame.bars() > 0 ? DialogueScreen.top(frame.bars()) : null);
            player.sendActionBar(
                    frame.bars() > 0 || frame.panel() != null
                            ? DialogueScreen.bottom(frame.bars(), frame.panel(), frame.hints(), frame.toggle())
                            : Component.empty());
            sinceActionBar = 0;
        }
        drawn = frame;
        sinceVignette++;
        if (frame.vignette() != vignette || (vignette > 0 && sinceVignette >= VIGNETTE_EVERY)) {
            ViewPackets.vignette(player, frame.vignette());
            vignette = frame.vignette();
            sinceVignette = 0;
        }
        zoom(frame.zoom());
    }

    /** Narrows the view by {@code progress} of the zoom, through the walking speed the client widens its view by. */
    private void zoom(final double progress) {
        if (Math.abs(progress - zoom) < 1e-9) {
            return;
        }
        zoom = progress;
        final AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(zoomKey);
        if (progress > 0) {
            speed.addTransientModifier(new AttributeModifier(
                    zoomKey, 2 * (feel.zoom() - 1) * progress, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    private void holdSlot() {
        if (heldSlot >= 0) {
            return;
        }
        heldSlot = player.getInventory().getHeldItemSlot();
        moveSlot(Hotbar.CHOICE_SLOT);
    }

    private void releaseSlot() {
        if (heldSlot < 0) {
            return;
        }
        final int back = heldSlot;
        heldSlot = -1;
        moveSlot(back);
    }

    private void moveSlot(final int slot) {
        movingSlot = true;
        try {
            player.getInventory().setHeldItemSlot(slot);
        } finally {
            movingSlot = false;
        }
    }

    private static double horizontal(final Location a, final Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }
}

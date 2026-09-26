package eu.nordtal.s2.smp.grave;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.common.phase.SeasonDates;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.ExpiredGrave;
import eu.nordtal.s2.smp.db.GraveRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Graves: what a death leaves behind, everywhere except the duel arena.
 *
 * <p>A grave <em>looks</em> like a chest with the player's own head resting on it, tilted and half
 * sunk into the lid as if it had fallen there, but it is display entities plus an
 * {@link Interaction} to click - three of them, or four while decay is on, since a fourth carries
 * the countdown hovering above it (season-2-ingame/19). Real blocks were rejected: a death in
 * the void, in lava, under the Nether roof or inside somebody's wall would each replace blocks that
 * belong to somebody, and graves stand forever. A display cannot land in a wall, cannot collide with
 * a second grave, and is gone when the grave is emptied.
 *
 * <p><b>Anyone may open one</b> - no timer, no ownership lock. Killing somebody and emptying their
 * grave is therefore possible; that is accepted rather than closed off, because locking a grave to
 * its owner would also stop a friend bringing somebody's things back (season-2-ingame/21, Till
 * 2026-09-15). {@code /rules} says so, because a mechanic that turns people against each other has
 * to be visible before somebody hits it.
 */
public final class Graves implements InventoryHolder {

    private final Plugin plugin;
    private final SmpDao dao;
    private final eu.nordtal.s2.smp.player.Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;
    private final WorldEffects effects;
    private final SmpSpec config;

    /** Grave id -> the entities drawing it, so they can be removed together. */
    private final Map<UUID, List<org.bukkit.entity.Entity>> parts = new HashMap<>();

    /** Interaction entity id -> grave id, which is how a click finds its grave. */
    private final Map<UUID, UUID> byInteraction = new HashMap<>();

    /** Grave id -> what is in it right now. Emptied graves are removed from here. */
    private final Map<UUID, GraveRow> open = new HashMap<>();

    /**
     * Grave id -> the countdown text hovering over it, when {@code graveMaxAgeHours} is on
     * (season-2-ingame/19). Absent for a grave drawn while decay is off - a countdown against a
     * limit that never triggers is not a countdown, it is a number that never moves.
     */
    private final Map<UUID, TextDisplay> holograms = new HashMap<>();

    /**
     * Grave id -> the epoch millisecond {@link #tickHolograms()} is next allowed to touch its
     * hologram. What keeps a grave that stands for 24 hours from writing a packet to everyone
     * watching it once a second for all 86 400 of them - see {@link #tickHolograms()}.
     */
    private final Map<UUID, Long> nextHologramUpdate = new HashMap<>();

    /**
     * Grave id -> the one window showing it, however many people are looking.
     *
     * <p>One window and not one per viewer: two people can right-click the same grave in the same
     * second, and two inventories filled from the same stored contents would each write a whole
     * snapshot back - duplicating everything the dead player was carrying. A shared inventory is
     * what a vanilla chest does; the grave is settled when the last viewer closes it, see
     * {@link #onClosed}.</p>
     */
    private final Map<UUID, Inventory> shown = new HashMap<>();

    public Graves(
            final Plugin plugin,
            final SmpDao dao,
            final eu.nordtal.s2.smp.player.Identities identities,
            final Messages messages,
            final PlayerLocales locales,
            final SmpSounds sounds,
            final WorldEffects effects,
            final SmpSpec config) {
        this.plugin = plugin;
        this.dao = dao;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
        this.effects = effects;
        this.config = config;
    }

    /**
     * Whether {@code inventory} is a grave standing open right now.
     *
     * <p>A grave inventory has a null holder and is recognised by identity, which is why
     * {@code SurfaceListener} takes this as a predicate rather than checking a marker interface.
     */
    public boolean isShowingGrave(final Inventory inventory) {
        return shown.containsValue(inventory);
    }

    @Override
    public Inventory getInventory() {
        throw new UnsupportedOperationException("graves hold many inventories, one per open grave");
    }

    /** Puts every grave that still holds something back into the world. Main thread. */
    public void restore(final List<GraveRow> rows) {
        for (final GraveRow row : rows) {
            final World world = Bukkit.getWorld(row.world());
            if (world == null) {
                continue;
            }
            draw(row, new Location(world, row.x() + 0.5, row.y(), row.z() + 0.5));
        }
        plugin.getLogger().info("restored " + open.size() + " grave(s)");
    }

    /**
     * Records a death and draws its grave. The caller has already taken the items off the player.
     *
     * @param at        where they died
     * @param contents  their whole inventory
     * @param experience the experience to credit back to whoever empties it
     */
    public void create(
            final String ownerId,
            final UUID ownerUuid,
            final Location at,
            final ItemStack[] contents,
            final int experience) {
        final byte[] bytes = ItemStack.serializeItemsAsBytes(contents);
        final Location grave = at.getBlock().getLocation().add(0.5, 0, 0.5);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            dao.createGrave(
                    ownerId,
                    grave.getWorld().getName(),
                    grave.getBlockX(),
                    grave.getBlockY(),
                    grave.getBlockZ(),
                    bytes,
                    experience);
            // Read back rather than invented locally, so a restart draws exactly what a fresh
            // death drew.
            final List<GraveRow> rows = dao.openGraves();
            Bukkit.getScheduler()
                    .runTask(
                            plugin,
                            () -> rows.stream()
                                    .filter(row -> !open.containsKey(row.id()))
                                    .forEach(row -> {
                                        final World world = Bukkit.getWorld(row.world());
                                        if (world != null) {
                                            draw(row, new Location(world, row.x() + 0.5, row.y(), row.z() + 0.5));
                                        }
                                    }));
        });
    }

    // ------------------------------------------------------------------ drawing

    /**
     * The vanilla chest model's own height, in blocks - it is drawn at its native size now, with no
     * stretch or squash (season-2-ingame/14: the old 0.6 squash was chosen for nothing).
     */
    private static final float CHEST_HEIGHT = 0.875f;

    /**
     * Half the height of a skull {@link ItemDisplay} drawn with
     * {@link ItemDisplay.ItemDisplayTransform#NONE}, which centres the model on the display's own
     * origin. A player head is eight pixels tall, not sixteen - it is the bottom half of a block
     * cell when placed - so the model is 0.5 blocks high and half of that is 0.25.
     *
     * <p>An earlier version of this constant said 1.0 and half of 0.5, on the assumption that a head
     * is a full block. It is not, and the difference is a quarter of a block of daylight between the
     * skull and the chest lid it is supposed to be resting in. Corrected without a client, so it is
     * still a starting point rather than a measurement - like the three below it.</p>
     */
    private static final float HEAD_HALF_HEIGHT = 0.25f;

    /**
     * How much of its own size the skull is drawn at, so that a tilted head stays on the lid
     * instead of hanging over the rim (season-2-ingame/14, Till 2026-09-18: before the last round it
     * "stuck out over the chest a little").
     *
     * <p>Computed, not measured, and the sum is worth writing down because the number looks
     * arbitrary otherwise. A player head item carries a hat layer at 1.125 of the head's own
     * 8 pixels, so its half-extent is 9/32 = 0.28125 blocks and its worst corner sits
     * 0.28125*sqrt(3) =~ 0.487 blocks from the centre - a rotation is length-preserving, so that
     * distance holds at any tilt. A chest is 14 pixels wide, i.e. 0.4375 blocks from its own centre
     * to its rim. 0.487 &gt; 0.4375 is the overhang Till saw; 0.85 brings it to 0.414 and leaves
     * about a third of a pixel of lid on every side.</p>
     */
    private static final float HEAD_SCALE = 0.85f;

    /**
     * How far the skull's centre dips below the chest's top edge, so it reads as fallen rather than
     * placed. <b>A placeholder, not a measurement</b> - season-2-ingame/14 is explicit that this
     * number is found by looking at it in the game, not by computing it, and nobody has done that yet.
     */
    private static final float HEAD_SINK_DEPTH = 0.15f;

    /**
     * Rotation around the X axis that tips the skull onto its side instead of standing it upright.
     * Placeholder, same caveat as {@link #HEAD_SINK_DEPTH}.
     */
    private static final float HEAD_TILT_DEGREES = 65f;

    /**
     * A second, smaller rotation around the Z axis, so the skull does not look perfectly
     * axis-aligned - two axes together are what makes it read as fallen rather than posed. Placeholder,
     * same caveat as {@link #HEAD_SINK_DEPTH}.
     */
    private static final float HEAD_ROLL_DEGREES = 12f;

    /**
     * How far above {@code at} the countdown hologram floats (season-2-ingame/19). A placeholder,
     * same caveat as {@link #HEAD_SINK_DEPTH}: high enough to clear the skull was guessed, not
     * measured, and is found by looking at it in the game.
     */
    private static final double HOLOGRAM_HEIGHT = 1.6;

    /**
     * Below this much time left, the hologram is refreshed every second instead of every minute -
     * the point at which a player watching it actually reads the number (season-2-ingame/19: not
     * every second, a grave standing 24 hours would be 86 400 packet updates to everyone in sight).
     */
    private static final Duration HOLOGRAM_FINAL_STRETCH = Duration.ofMinutes(1);

    private static final long HOLOGRAM_REFRESH_MINUTES_MS =
            Duration.ofMinutes(1).toMillis();
    private static final long HOLOGRAM_REFRESH_SECONDS_MS =
            Duration.ofSeconds(1).toMillis();

    private void draw(final GraveRow row, final Location at) {
        final World world = at.getWorld();
        final List<org.bukkit.entity.Entity> entities = new ArrayList<>(4);

        // A chest, not a squashed plank: season-2-ingame/14, Till 2026-09-15. It is drawn at its own
        // size - no scale override - the translation only re-centres the model on the block cell,
        // because a BlockDisplay's model spans (0,0,0)-(1,1,1) from its entity location and `at` is
        // already the cell's centre point.
        final BlockDisplay chest = world.spawn(at, BlockDisplay.class, display -> {
            display.setBlock(Material.CHEST.createBlockData());
            display.setPersistent(false);
            display.setTransformation(new Transformation(
                    new Vector3f(-0.5f, 0f, -0.5f), new AxisAngle4f(),
                    new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
        });
        entities.add(chest);

        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (row.ownerUuid() != null) {
            // Null only when the account link is gone, i.e. somebody was unlinked after dying; a
            // plain head is the right answer there.
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(row.ownerUuid())));
        }
        // Spawned at the chest's own location, not lifted by adding to the spawn point: every part of
        // "on top of the chest, sunk in, and tilted" is one Transformation (season-2-ingame/14 -
        // "eine Zahl beide Teile hält"), so one number moves the skull and nothing has to be kept in
        // step with it.
        //
        // What that does NOT buy is the pivot. Minecraft applies a Transformation as
        // translation * leftRotation * scale * rightRotation, so both rotations turn the model about
        // its own centre and the lift happens afterwards either way - the tilt cannot be made to
        // pivot on the rim of the chest by writing it this way. It stays put over the chest instead
        // of swinging off it, which is the part that matters here.
        //
        // NO X/Z offset here, and that is the whole of season-2-ingame/14's last round. The chest
        // above needs -0.5/-0.5 because a BlockDisplay draws its model from the entity's position
        // outwards, spanning (0,0,0)-(1,1,1); an ItemDisplay with ItemDisplayTransform.NONE draws
        // its model CENTRED on that position - HEAD_HALF_HEIGHT's own comment says so, and the Y
        // term below has always been written for a centred model. Copying the chest's offset across
        // on 2026-09-17 therefore did not centre the skull, it moved it half a block off the chest:
        // Till, 2026-09-18, "before it stuck out over the chest a little, now it floats beside it
        // entirely". The two display types are not symmetrical and that asymmetry is the bug.
        //
        // What is left of the original complaint - the head hanging slightly over the rim - is
        // answered by HEAD_SCALE instead, where the arithmetic for it stands.
        final ItemDisplay skull = world.spawn(at, ItemDisplay.class, display -> {
            display.setItemStack(head);
            display.setPersistent(false);
            display.setBillboard(Display.Billboard.FIXED);
            // The default already, made explicit because the constants above are computed for
            // exactly this transform: the head's own model, centred on the origin.
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setTransformation(new Transformation(
                    new Vector3f(0f, CHEST_HEIGHT + HEAD_HALF_HEIGHT * HEAD_SCALE - HEAD_SINK_DEPTH, 0f),
                    new AxisAngle4f((float) Math.toRadians(HEAD_TILT_DEGREES), 1f, 0f, 0f),
                    new Vector3f(HEAD_SCALE, HEAD_SCALE, HEAD_SCALE),
                    new AxisAngle4f((float) Math.toRadians(HEAD_ROLL_DEGREES), 0f, 0f, 1f)));
        });
        entities.add(skull);

        final Interaction click = world.spawn(at, Interaction.class, interaction -> {
            interaction.setInteractionWidth(1.0f);
            interaction.setInteractionHeight(1.2f);
            interaction.setResponsive(true);
            interaction.setPersistent(false);
        });
        entities.add(click);

        // The countdown (season-2-ingame/19, Till 2026-09-15): see-through so it reads from behind
        // a wall, billboarded to CENTER so it turns to face whoever is looking rather than only
        // whoever stood north of it. Skipped while decay is off (graveMaxAgeHours() <= 0): there is
        // no deadline to count down to, and a number that never moves is not a countdown.
        if (config.graveMaxAgeHours() > 0) {
            final Duration timeLeft = timeLeft(row);
            final TextDisplay hologram =
                    world.spawn(at.clone().add(0, HOLOGRAM_HEIGHT, 0), TextDisplay.class, display -> {
                        display.setPersistent(false);
                        display.setSeeThrough(true);
                        display.setBillboard(Display.Billboard.CENTER);
                        display.text(hologramText(row, timeLeft));
                    });
            entities.add(hologram);
            holograms.put(row.id(), hologram);
            nextHologramUpdate.put(row.id(), System.currentTimeMillis() + refreshInterval(timeLeft));
        }

        parts.put(row.id(), entities);
        byInteraction.put(click.getUniqueId(), row.id());
        open.put(row.id(), row);
    }

    /** How long until {@code row} decays, floored at zero. Zero when decay itself is off. */
    private Duration timeLeft(final GraveRow row) {
        final int hours = config.graveMaxAgeHours();
        if (hours <= 0) {
            return Duration.ZERO;
        }
        final Duration timeLeft = Duration.between(Instant.now(), row.created().plus(Duration.ofHours(hours)));
        return timeLeft.isNegative() ? Duration.ZERO : timeLeft;
    }

    /**
     * The hologram's text for {@code timeLeft} remaining, in the dead player's own language.
     *
     * <p>Read in the owner's locale rather than the viewer's: the hologram hangs in the world for
     * anybody standing nearby, the same problem the grave window's title has (see the class
     * comment there) and the same answer - one language has to be picked, and it is the one person
     * every viewer of a shared display has in common. {@link PlayerLocales#of} degrades to English
     * on its own when the owner is offline or unlinked, which is also the fallback here.
     */
    private Component hologramText(final GraveRow row, final Duration timeLeft) {
        final Locale locale = row.ownerUuid() == null ? Locale.ENGLISH : locales.of(row.ownerUuid());
        final MessageRenderer renderer = MessageRenderer.of(messages);
        if (timeLeft.compareTo(HOLOGRAM_FINAL_STRETCH) < 0) {
            return renderer.format(locale, MESSAGES.smp().grave().hologramSeconds(timeLeft.toSeconds()));
        }
        return renderer.format(locale, MESSAGES.smp().grave().hologram(timeLeft.toHours(), timeLeft.toMinutesPart()));
    }

    /** Once a minute normally, once a second inside {@link #HOLOGRAM_FINAL_STRETCH}. */
    private static long refreshInterval(final Duration timeLeft) {
        return timeLeft.compareTo(HOLOGRAM_FINAL_STRETCH) < 0
                ? HOLOGRAM_REFRESH_SECONDS_MS
                : HOLOGRAM_REFRESH_MINUTES_MS;
    }

    /**
     * Refreshes every grave's countdown that is due, at whatever interval it currently needs
     * (season-2-ingame/19). Call once a second; {@link #nextHologramUpdate} is what keeps a grave
     * far from expiring from writing a packet to everyone watching it on every one of those calls.
     *
     * <p>Main thread: {@link TextDisplay#text} is a packet to everybody who can see the entity.
     */
    public void tickHolograms() {
        if (holograms.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final Map.Entry<UUID, TextDisplay> entry : holograms.entrySet()) {
            final UUID graveId = entry.getKey();
            final Long dueAt = nextHologramUpdate.get(graveId);
            if (dueAt != null && now < dueAt) {
                continue;
            }
            final GraveRow row = open.get(graveId);
            if (row == null) {
                continue;
            }
            final Duration timeLeft = timeLeft(row);
            entry.getValue().text(hologramText(row, timeLeft));
            nextHologramUpdate.put(graveId, now + refreshInterval(timeLeft));
        }
    }

    private void erase(final UUID graveId) {
        final List<org.bukkit.entity.Entity> entities = parts.remove(graveId);
        if (entities != null) {
            entities.forEach(entity -> {
                byInteraction.remove(entity.getUniqueId());
                entity.remove();
            });
        }
        open.remove(graveId);
        shown.remove(graveId);
        holograms.remove(graveId);
        nextHologramUpdate.remove(graveId);
    }

    /**
     * Deletes every grave that has run out of time, and takes its display down (ingame/20).
     *
     * <p>Till, 2026-09-15: a grave stands at most 24 hours, and <b>what is in it decays with it</b>
     * - the same as vanilla items that despawn. Nothing is dropped on the ground, which is why this
     * method never touches the contents at all: the row is deleted and the bytes go with it.
     *
     * <p><b>Call this off the main thread.</b> It runs the delete on the calling thread, because a
     * Paper plugin does not query the database from the main thread and the timer that calls it is
     * already asynchronous - a second hop inside here would only make that harder to see. Taking a
     * display down and making a sound are main-thread work, so the second half hops back.
     *
     * <p>A grave the server never drew - one in a world that is not loaded, one made while this
     * process was not running - is deleted all the same and simply has no entities to remove. That
     * is the case {@code erase} already handles by finding nothing under the id.
     *
     * @param hours the configured maximum age. Zero or less means decay is off and nothing happens;
     *              the check is here rather than at the call site so there is one place to read.
     */
    public void expire(final int hours) {
        if (hours <= 0) {
            return;
        }
        final List<ExpiredGrave> gone = dao.expireGravesOlderThan(hours);
        if (gone.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (final ExpiredGrave grave : gone) {
                erase(grave.id());
                final World world = Bukkit.getWorld(grave.world());
                if (world != null) {
                    // The same sound as a grave being emptied, and deliberately so: from where
                    // anybody is standing, both are a grave that is there and then is not.
                    sounds.playAt(
                            new Location(world, grave.x() + 0.5, grave.y() + 0.5, grave.z() + 0.5), Feedback.RECLAIMED);
                }
            }
            plugin.getLogger()
                    .info("expired " + gone.size() + " grave(s) older than " + hours + "h, contents included");
        });
    }

    /** Removes every display this plugin drew. Called at disable; the rows stay in the database. */
    public void clearDisplays() {
        List.copyOf(parts.keySet()).forEach(this::erase);
        shown.clear();
    }

    // ------------------------------------------------------------------ opening

    /** Whether an entity is a grave's click surface, and if so which grave. */
    public Optional<UUID> graveOfInteraction(final UUID interactionId) {
        return Optional.ofNullable(byInteraction.get(interactionId));
    }

    /** Opens a grave for somebody - anybody. Main thread. */
    public void open(final Player player, final UUID graveId) {
        final GraveRow row = open.get(graveId);
        if (row == null) {
            return;
        }
        final Locale locale = locales.of(player.getUniqueId());

        // The window this grave already has, if somebody else is standing in it - a second one
        // built from the same stored contents duplicates the loot. The cost is that the second
        // looter reads the first looter's title, which differs only in language.
        final Inventory inventory = shown.computeIfAbsent(graveId, id -> window(row, locale));
        player.openInventory(inventory);

        // At the grave, not at the player, so anybody nearby hears it being disturbed.
        final org.bukkit.World world = Bukkit.getWorld(row.world());
        if (world != null) {
            effects.graveOpened(new org.bukkit.Location(world, row.x(), row.y(), row.z()));
        }
    }

    /**
     * Builds the one window a grave is shown in - design {@code G1}, drawn by {@link GravePanel}.
     *
     * <p>The footer makes the waiting experience visible, and every one of its nine cells holds an
     * item: a shift-click from the player's own inventory goes into the first free slot of the
     * window, and a free footer cell sits outside everything {@link #settle} writes back.</p>
     */
    private Inventory window(final GraveRow row, final Locale locale) {
        final ItemStack[] contents = ItemStack.deserializeItemsFromBytes(row.contents());
        final int contentRows = GravePanel.contentRows(contents.length);
        final MessageRenderer renderer = MessageRenderer.of(messages);

        final Inventory window = Bukkit.createInventory(
                null,
                GravePanel.rows(contentRows) * 9,
                GravePanel.title(
                        renderer.format(locale, MESSAGES.smp().grave().title()),
                        contentRows,
                        row.experience() > 0
                                ? messages.format(locale, MESSAGES.smp().grave().experienceLine(row.experience()))
                                : "",
                        messages.format(locale, MESSAGES.smp().grave().takeAllButton())));

        final int slots = GravePanel.contentSlots(contentRows);
        for (int slot = 0; slot < slots && slot < contents.length; slot++) {
            window.setItem(slot, contents[slot]);
        }

        window.setItem(GravePanel.headSlot(contentRows), head(row, renderer, locale));

        // The name is on the head rather than in the title: one window is shared by everybody
        // standing in the grave, so a title would carry the first opener's language.
        final ItemStack experience = eu.nordtal.s2.papercommon.menu.BlankItem.of(
                renderer.format(locale, MESSAGES.smp().grave().experienceTooltip()),
                List.of(renderer.format(locale, MESSAGES.smp().grave().experienceHint(row.experience()))));
        GravePanel.experienceSlots(contentRows).forEach(slot -> window.setItem(slot, experience));

        final ItemStack takeAll = eu.nordtal.s2.papercommon.menu.BlankItem.of(
                renderer.format(locale, MESSAGES.smp().grave().takeAll()),
                List.of(renderer.format(locale, MESSAGES.smp().grave().takeAllHint())));
        GravePanel.takeAllSlots(contentRows).forEach(slot -> window.setItem(slot, takeAll));

        return window;
    }

    /**
     * How the grave head's lore prints the date it was made - Till, 2026-09-17 triage: "Died
     * dd/mm/yyyy at x y z", replacing the old "whoever empties this..." hint rather than joining it
     * ("nicht ... sondern", not "zusätzlich"). {@link SeasonDates#ZONE} is reused rather than the
     * JVM default for the same reason it exists there: every container in {@code compose.yml} runs
     * on UTC, and a death at 23:30 local time would otherwise print the next day's date.
     */
    private static final DateTimeFormatter GRAVE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * The dead player's own head, which is where their name is (see {@link #window}). A real item
     * since season-2-ingame/21, Till 2026-09-15, but nobody takes it: since season-2-ingame/14,
     * 2026-09-18, {@link #settle} hands it over when the emptied grave closes.
     *
     * <p>The lore names when and where they died ({@code smp.grave.died-at}, season-2-ingame/14,
     * 2026-09-17 triage) - it replaced the old {@code smp.grave.owner-hint} outright, the key is
     * gone rather than merely unused.</p>
     */
    private ItemStack head(final GraveRow row, final MessageRenderer renderer, final Locale locale) {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        final String name = row.ownerUuid() == null
                ? null
                : Bukkit.getOfflinePlayer(row.ownerUuid()).getName();
        head.editMeta(meta -> {
            if (meta instanceof SkullMeta skull && row.ownerUuid() != null) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(row.ownerUuid()));
            }
            meta.displayName(renderer.format(
                            locale,
                            name == null
                                    ? MESSAGES.smp().grave().ownerUnknown()
                                    : MESSAGES.smp().grave().owner(new PlayerContext(name)))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            final String date = GRAVE_DATE.format(row.created().atZone(SeasonDates.ZONE));
            meta.lore(List.of(renderer.format(locale, MESSAGES.smp().grave().diedAt(date, row.x(), row.y(), row.z()))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        });
        return head;
    }

    /**
     * A click inside a grave window.
     *
     * <p>The content rows stay free in both directions; the footer is locked, and one of its cells
     * is the button that empties the grave in one go.</p>
     *
     * @return true when the click was in the footer and has been dealt with
     */
    public boolean click(final Player player, final Inventory inventory, final int rawSlot) {
        if (!isShowingGrave(inventory) || rawSlot < 0 || rawSlot >= inventory.getSize()) {
            return false;
        }
        final int contentRows = inventory.getSize() / 9 - 1;
        if (GravePanel.isContent(rawSlot, contentRows)) {
            return false;
        }
        if (GravePanel.takeAllSlots(contentRows).contains(rawSlot)) {
            takeAll(player, inventory, contentRows);
        }
        return true;
    }

    /**
     * Empties a grave into one player's inventory, and out onto the floor for what does not fit.
     *
     * <p>Closing the window is what settles the grave, so this only moves the items and closes.
     * Two people in the same grave are safe because the slots are emptied here on the main thread,
     * so the second click finds nothing.</p>
     *
     * <p><b>The head does not go with it</b> (season-2-ingame/14, Till 2026-09-18). It did between
     * season-2-ingame/21 and this change, and the cost was a second press: somebody who had emptied
     * the grave by hand still had to hit this button once more, on a window holding nothing, purely
     * to collect the skull. It is furniture again - {@link #settle} hands it over when the emptied
     * grave is closed, and nobody clicks it at all.</p>
     *
     * <p>An empty grave still closes here rather than refusing. Closing is what finishes it and what
     * hands the head over, so a button that did nothing on a grave with nothing left in it would be
     * a dead end in the one state this ticket is about.</p>
     */
    private void takeAll(final Player player, final Inventory inventory, final int contentRows) {
        boolean took = false;
        for (int slot = 0; slot < GravePanel.contentSlots(contentRows); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            inventory.setItem(slot, null);
            player.getInventory()
                    .addItem(stack)
                    .values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            took = true;
        }

        if (!took) {
            // Nothing was in it, which is not a refusal: the close below is what settles the grave
            // and gives the head back.
            sounds.play(player, Feedback.SELECT);
            player.closeInventory();
            return;
        }
        sounds.play(player, Feedback.SELECT);
        player.closeInventory();
    }

    /**
     * Called when a grave inventory is closed: whatever is left goes back, and an empty one is
     * finished.
     *
     * <p>The experience is credited on the grave becoming empty rather than on each item taken -
     * one death, one refund, whoever finished the job.
     *
     * <p><b>Settled a tick later, when the window is empty of people.</b> Bukkit fires the close
     * before it drops the viewer, so "is anybody left?" has no honest answer inside the event.</p>
     */
    public void onClosed(final Player player, final Inventory inventory) {
        if (!shown.containsValue(inventory)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> settle(player, inventory));
    }

    private void settle(final Player player, final Inventory inventory) {
        if (!inventory.getViewers().isEmpty()) {
            // Somebody else still has it open and will come through here when they close it.
            return;
        }
        final UUID graveId = shown.entrySet().stream()
                .filter(entry -> entry.getValue().equals(inventory))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (graveId == null) {
            return;
        }
        shown.remove(graveId);
        final GraveRow row = open.get(graveId);
        if (row == null) {
            return;
        }

        // The CONTENT slots and not the whole window: the footer's furniture would make the grave
        // never empty and would be written into the row as loot.
        final ItemStack[] left = contentOf(inventory);
        final boolean contentGone = java.util.Arrays.stream(left)
                .allMatch(stack -> stack == null || stack.getType().isAir());

        // The content decides, and only the content (season-2-ingame/14, Till 2026-09-18). Between
        // season-2-ingame/21 and that date the head had to be gone as well, because the button was
        // the only way to it and a grave finishing with the skull still in the footer would have
        // deleted it uncollected. The head is not taken by anybody any more - it is handed over a
        // few lines below - so the condition that protected it is the condition that kept an
        // emptied grave standing.
        final boolean empty = contentGone;
        if (!empty) {
            // Not finished: keep what is left so anybody can come back for the rest.
            final byte[] remaining = ItemStack.serializeItemsAsBytes(left);
            open.put(
                    graveId,
                    new GraveRow(
                            row.id(),
                            row.ownerId(),
                            row.ownerUuid(),
                            row.world(),
                            row.x(),
                            row.y(),
                            row.z(),
                            remaining,
                            row.experience(),
                            row.created()));
            // AND IN THE DATABASE: the map above is this process's memory, but the enable-time
            // restore reads the row, so a half-emptied grave would come back full after a restart.
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> dao.updateGraveContents(graveId, remaining));
            return;
        }

        // The head comes back by itself, on the main thread, before anything asynchronous starts:
        // this is the whole of Till's request from 2026-09-18 - empty the grave, close it, and the
        // skull is in your inventory without a second gesture. Onto the floor for what does not fit,
        // the same fallback every other item in this class uses; the grave is about to be erased, so
        // leaving it in the window would destroy it.
        final int contentRows = inventory.getSize() / 9 - 1;
        final int headSlot = GravePanel.headSlot(contentRows);
        final ItemStack head = inventory.getItem(headSlot);
        if (head != null && !head.getType().isAir()) {
            inventory.setItem(headSlot, null);
            player.getInventory()
                    .addItem(head)
                    .values()
                    .forEach(spill -> player.getWorld().dropItemNaturally(player.getLocation(), spill));
        }

        // The looter's DISCORD id, never their Minecraft UUID: `looted_by` is varchar(32) like
        // every other person column in this schema, and a UUID's 36 characters do not fit - the
        // insert throws and takes the whole loot with it.
        //
        // Null is legitimate: an unlinked looter cannot be named and the grave still has to close.
        final String looterId = identities.discordIdOf(player.getUniqueId()).orElse(null);
        final int experience = row.experience();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (dao.markGraveLooted(graveId, looterId).isEmpty()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                erase(graveId);

                // A WORLD sound, not a player one: anybody standing at the grave - not only the
                // looter - is there to hear it settle (season-2-ingame/15, Till 2026-09-15: the
                // grave used to disappear silently, and should not).
                //
                // And here rather than inside erase, which is the one place the ticket pointed at.
                // erase has another caller that is not a grave finishing: clearDisplays at plugin
                // disable. A sound there would play a skeleton's death once per grave at every
                // shutdown, to nobody's benefit.
                final World graveWorld = Bukkit.getWorld(row.world());
                if (graveWorld != null) {
                    sounds.playAt(
                            new Location(graveWorld, row.x() + 0.5, row.y() + 0.5, row.z() + 0.5), Feedback.RECLAIMED);
                }

                if (experience > 0 && player.isOnline()) {
                    player.giveExp(experience);
                    player.sendMessage(MessageRenderer.of(messages)
                            .format(
                                    locales.of(player.getUniqueId()),
                                    MESSAGES.smp().grave().experience(experience)));
                    sounds.play(player, Feedback.SMALL_SUCCESS);
                }
            });
        });
    }

    /**
     * What is in a grave window's content slots, without its footer.
     *
     * <p>Public so the tests split content from footer by the same rule the settle uses.
     */
    public static ItemStack[] contentOf(final Inventory inventory) {
        final int contentRows = inventory.getSize() / 9 - 1;
        return java.util.Arrays.copyOf(inventory.getContents(), GravePanel.contentSlots(contentRows));
    }

    // forgetWorld stood here until 2026-09-20: it erased every grave in a world for the nightly
    // farm-world reset, which was its only caller. Both went with season-2-ingame/30.
}

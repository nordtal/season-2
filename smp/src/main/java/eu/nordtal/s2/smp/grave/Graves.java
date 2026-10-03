package eu.nordtal.s2.smp.grave;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.ExpiredGrave;
import eu.nordtal.s2.smp.db.GraveRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
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
 * Display entities and an {@link Interaction}, never real blocks; anyone may open one, and {@code /rules} says so.
 */
public final class Graves implements InventoryHolder {

    private final Plugin plugin;
    private final SmpDao dao;
    private final Identities identities;
    private final Messages messages;
    private final SmpSounds sounds;
    private final WorldEffects effects;
    private final SmpSpec config;

    private final Map<UUID, List<org.bukkit.entity.Entity>> parts = new HashMap<>();

    private final Map<UUID, UUID> byInteraction = new HashMap<>();

    private final Map<UUID, GraveRow> open = new HashMap<>();

    /** Grave id to the countdown hovering over it, absent while decay is off. */
    private final Map<UUID, TextDisplay> holograms = new HashMap<>();

    /** Grave id to the epoch millisecond {@link #tickHolograms()} may next touch its hologram. */
    private final Map<UUID, Long> nextHologramUpdate = new HashMap<>();

    /**
     * Grave id to the one window showing it, however many people are looking.
     *
     * One shared window, because two windows would each write a whole snapshot back and duplicate the contents.
     */
    private final Map<UUID, Inventory> shown = new HashMap<>();

    private final Clock clock;

    public Graves(
            final Plugin plugin,
            final SmpDao dao,
            final Identities identities,
            final Messages messages,
            final SmpSounds sounds,
            final WorldEffects effects,
            final SmpSpec config,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.dao = dao;
        this.identities = identities;
        this.messages = messages;
        this.sounds = sounds;
        this.effects = effects;
        this.config = config;
    }

    /** Whether {@code inventory} is a grave standing open right now, recognised by identity. */
    public boolean isShowingGrave(final Inventory inventory) {
        return shown.containsValue(inventory);
    }

    @Override
    public Inventory getInventory() {
        throw new UnsupportedOperationException("graves hold many inventories, one per open grave");
    }

    /** Puts every grave that still holds something back into the world, on the main thread. */
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
     * Records a death and draws its grave, from items the caller has already taken off the player.
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
        final Location grave =
                java.util.Objects.requireNonNull(at.getBlock().getLocation()).add(0.5, 0, 0.5);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            dao.createGrave(
                    ownerId,
                    grave.getWorld().getName(),
                    grave.getBlockX(),
                    grave.getBlockY(),
                    grave.getBlockZ(),
                    bytes,
                    experience);
            // Read back rather than invented locally, so a restart draws exactly what a fresh death drew.
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

    /** The vanilla chest model's height in blocks, drawn at native size. */
    private static final float CHEST_HEIGHT = 0.875f;

    /**
     * Half the height of a skull {@link ItemDisplay} drawn with {@link ItemDisplay.ItemDisplayTransform#NONE}.
     *
     * That transform centres the model, and a player head is 0.5 blocks high.
     */
    private static final float HEAD_HALF_HEIGHT = 0.25f;

    /**
     * How much of its own size the skull is drawn at, so that a tilted head stays on the lid instead of the rim.
     *
     * The hat layer's worst corner is 0.487 blocks out at any tilt; the rim is 0.4375, and 0.85 brings it to 0.414.
     */
    private static final float HEAD_SCALE = 0.85f;

    /** How far the skull's centre dips below the chest's top edge, found by eye. */
    private static final float HEAD_SINK_DEPTH = 0.15f;

    /** Rotation around the X axis that tips the skull onto its side, found by eye. */
    private static final float HEAD_TILT_DEGREES = 65f;

    /** A smaller rotation around the Z axis so the skull reads as fallen rather than posed, found by eye. */
    private static final float HEAD_ROLL_DEGREES = 12f;

    /** How far above {@code at} the countdown hologram floats, found by eye. */
    private static final double HOLOGRAM_HEIGHT = 1.6;

    /** Below this much time left, the hologram is refreshed every second instead of every minute. */
    private static final Duration HOLOGRAM_FINAL_STRETCH = Duration.ofMinutes(1);

    private static final long HOLOGRAM_REFRESH_MINUTES_MS =
            Duration.ofMinutes(1).toMillis();
    private static final long HOLOGRAM_REFRESH_SECONDS_MS =
            Duration.ofSeconds(1).toMillis();

    /**
     * Spawns the chest, skull, click target and, while decay is on, the countdown for {@code row} at {@code at}.
     *
     * A BlockDisplay draws from its corner and needs a half-block offset; an ItemDisplay with NONE draws centred.
     */
    private void draw(final GraveRow row, final Location at) {
        final World world = at.getWorld();
        final List<org.bukkit.entity.Entity> entities = new ArrayList<>(4);

        // Drawn at its own size, no scale override: the translation only re-centres the model on the block cell.
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
            // Null only when the account was unlinked after the death; a plain head is then right.
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(row.ownerUuid())));
        }
        // Spawned at the chest's own location, no X/Z offset.
        final ItemDisplay skull = world.spawn(at, ItemDisplay.class, display -> {
            display.setItemStack(head);
            display.setPersistent(false);
            display.setBillboard(Display.Billboard.FIXED);
            // Explicit though it is the default: the constants above are computed for this transform.
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

        spawnHologramIfDecaying(row, at, world, entities);

        parts.put(row.id(), entities);
        byInteraction.put(click.getUniqueId(), row.id());
        open.put(row.id(), row);
    }

    /** Spawns the see-through, billboarded countdown, unless decay is off. */
    private void spawnHologramIfDecaying(
            final GraveRow row, final Location at, final World world, final List<org.bukkit.entity.Entity> entities) {
        if (config.graveMaxAgeHours() <= 0) {
            return;
        }
        final Duration timeLeft = timeLeft(row);
        final TextDisplay hologram = world.spawn(at.clone().add(0, HOLOGRAM_HEIGHT, 0), TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setSeeThrough(true);
            display.setBillboard(Display.Billboard.CENTER);
            display.text(hologramText(row, timeLeft));
        });
        entities.add(hologram);
        holograms.put(row.id(), hologram);
        nextHologramUpdate.put(row.id(), clock.millis() + refreshInterval(timeLeft));
    }

    /** How long until {@code row} decays, floored at zero. Zero when decay itself is off. */
    private Duration timeLeft(final GraveRow row) {
        final int hours = config.graveMaxAgeHours();
        if (hours <= 0) {
            return Duration.ZERO;
        }
        final Duration timeLeft =
                Duration.between(clock.instant(), row.created().plus(Duration.ofHours(hours)));
        return timeLeft.isNegative() ? Duration.ZERO : timeLeft;
    }

    /**
     * The hologram's text for {@code timeLeft} remaining, in the dead player's own language.
     *
     * One language serves every viewer; {@link Identities#languageOf} falls back to English for an offline owner.
     */
    private Component hologramText(final GraveRow row, final Duration timeLeft) {
        final Locale locale = row.ownerUuid() == null ? Locale.ENGLISH : identities.languageOf(row.ownerUuid());
        // Whole minutes outside the final stretch, since the text is redrawn once a minute there.
        final Duration shown =
                timeLeft.compareTo(HOLOGRAM_FINAL_STRETCH) < 0 ? timeLeft : timeLeft.truncatedTo(ChronoUnit.MINUTES);
        return MessageRenderer.of(messages)
                .format(locale, MESSAGES.smp().grave().hologram(shown));
    }

    /** Once a minute normally, once a second inside {@link #HOLOGRAM_FINAL_STRETCH}. */
    private static long refreshInterval(final Duration timeLeft) {
        return timeLeft.compareTo(HOLOGRAM_FINAL_STRETCH) < 0
                ? HOLOGRAM_REFRESH_SECONDS_MS
                : HOLOGRAM_REFRESH_MINUTES_MS;
    }

    /** Refreshes every grave's countdown that is due; call once a second, on the main thread. */
    public void tickHolograms() {
        if (holograms.isEmpty()) {
            return;
        }
        final long now = clock.millis();
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
     * Deletes every grave that has run out of time, with its contents and display; call it off the main thread.
     *
     * @param hours the configured maximum age; zero or less turns decay off
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
                    // The same sound as a grave being emptied, deliberately.
                    sounds.playAt(
                            new Location(world, grave.x() + 0.5, grave.y() + 0.5, grave.z() + 0.5), Feedback.RECLAIMED);
                }
            }
            plugin.getLogger()
                    .info("expired " + gone.size() + " grave(s) older than " + hours + "h, contents included");
        });
    }

    /** Removes every display this plugin drew, at disable; the rows stay in the database. */
    public void clearDisplays() {
        List.copyOf(parts.keySet()).forEach(this::erase);
        shown.clear();
    }

    /** Whether an entity is a grave's click surface, and if so which grave. */
    public Optional<UUID> graveOfInteraction(final UUID interactionId) {
        return Optional.ofNullable(byInteraction.get(interactionId));
    }

    /** Opens a grave for anybody, on the main thread. */
    public void open(final Player player, final UUID graveId) {
        final GraveRow row = open.get(graveId);
        if (row == null) {
            return;
        }
        final Locale locale = identities.languageOf(player.getUniqueId());

        // The window this grave already has, if somebody else is in it.
        final Inventory inventory = shown.computeIfAbsent(graveId, id -> window(row, locale));
        player.openInventory(inventory);

        // At the grave, not at the player, so anybody nearby hears it being disturbed.
        final org.bukkit.World world = Bukkit.getWorld(row.world());
        if (world != null) {
            effects.graveOpened(new org.bukkit.Location(world, row.x(), row.y(), row.z()));
        }
    }

    /** Builds the one window a grave is shown in, drawn by {@link GravePanel}. */
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

        // The name is on the head, not the title.
        final ItemStack experience = eu.nordtal.s2.smp.menu.BlankItem.of(
                renderer.format(locale, MESSAGES.smp().grave().experienceTooltip()),
                List.of(renderer.format(locale, MESSAGES.smp().grave().experienceHint(row.experience()))));
        GravePanel.experienceSlots(contentRows).forEach(slot -> window.setItem(slot, experience));

        final ItemStack takeAll = eu.nordtal.s2.smp.menu.BlankItem.of(
                renderer.format(locale, MESSAGES.smp().grave().takeAll()),
                List.of(renderer.format(locale, MESSAGES.smp().grave().takeAllHint())));
        GravePanel.takeAllSlots(contentRows).forEach(slot -> window.setItem(slot, takeAll));

        return window;
    }

    /**
     * The dead player's own head, with when and where they died in the lore.
     *
     * Nobody takes it directly: {@link #settle} hands it over when the emptied grave closes.
     */
    private ItemStack head(final GraveRow row, final MessageRenderer renderer, final Locale locale) {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        final UUID owner = row.ownerUuid();
        final String name =
                owner == null ? null : Bukkit.getOfflinePlayer(owner).getName();
        head.editMeta(meta -> {
            if (meta instanceof SkullMeta skull && owner != null) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            }
            meta.displayName(renderer.format(
                            locale,
                            owner == null || name == null
                                    ? MESSAGES.smp().grave().ownerUnknown()
                                    : MESSAGES.smp().grave().owner(PlayerContext.of(PlayerId.of(owner), name)))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    renderer.format(locale, MESSAGES.smp().grave().diedAt(row.created(), row.x(), row.y(), row.z()))
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        });
        return head;
    }

    /**
     * A click inside a grave window.
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
     * Empties a grave into one player's inventory, dropping what does not fit, and closes the window.
     *
     * The head stays: closing settles the grave and hands it over, even for an already empty grave.
     */
    private void takeAll(final Player player, final Inventory inventory, final int contentRows) {
        final Location dropAt = java.util.Objects.requireNonNull(player.getLocation());
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
                    .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
            took = true;
        }

        if (!took) {
            // Nothing was in it, which is not a refusal: the close below settles the grave and gives the head back.
            sounds.play(player, Feedback.SELECT);
            player.closeInventory();
            return;
        }
        sounds.play(player, Feedback.SELECT);
        player.closeInventory();
    }

    /**
     * Called when a grave inventory is closed: whatever is left goes back, and an empty one is finished.
     *
     * Settled a tick later, because Bukkit fires the close before it drops the viewer.
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

        // The CONTENT slots, not the whole window.
        final ItemStack[] left = contentOf(inventory);
        final boolean contentGone = java.util.Arrays.stream(left)
                .allMatch(stack -> stack == null || stack.getType().isAir());

        // The content alone decides: the head is handed over separately, a few lines below.
        if (!contentGone) {
            keepRemainder(graveId, row, left);
            return;
        }

        // The head returns on the main thread before anything async starts.
        returnHeadToPlayer(inventory, player);
        finishLooting(graveId, row, player);
    }

    /** Keeps what is left, in memory and in the database, so anybody can come back for the rest. */
    private void keepRemainder(final UUID graveId, final GraveRow row, final ItemStack[] left) {
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
        // And in the database: the map above is process memory, but restore-on-enable reads the row.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> dao.updateGraveContents(graveId, remaining));
    }

    private void returnHeadToPlayer(final Inventory inventory, final Player player) {
        final int contentRows = inventory.getSize() / 9 - 1;
        final int headSlot = GravePanel.headSlot(contentRows);
        final ItemStack head = inventory.getItem(headSlot);
        if (head != null && !head.getType().isAir()) {
            inventory.setItem(headSlot, null);
            final Location dropAt = java.util.Objects.requireNonNull(player.getLocation());
            player.getInventory()
                    .addItem(head)
                    .values()
                    .forEach(spill -> player.getWorld().dropItemNaturally(dropAt, spill));
        }
    }

    /** Marks the grave looted, erases it and pays out its experience, all once the database confirms the claim. */
    private void finishLooting(final UUID graveId, final GraveRow row, final Player player) {
        // The looter's Discord id, not their Minecraft UUID: looted_by is varchar(32) like every other person column.
        final String looterId = identities
                .discordIdOf(player.getUniqueId())
                .map(DiscordId::value)
                .orElse(null);
        final int experience = row.experience();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (dao.markGraveLooted(graveId, looterId).isEmpty()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                erase(graveId);

                // A world sound so anybody at the grave hears it settle.
                final World graveWorld = Bukkit.getWorld(row.world());
                if (graveWorld != null) {
                    sounds.playAt(
                            new Location(graveWorld, row.x() + 0.5, row.y() + 0.5, row.z() + 0.5), Feedback.RECLAIMED);
                }

                if (experience > 0 && player.isOnline()) {
                    player.giveExp(experience);
                    player.sendMessage(MessageRenderer.of(messages)
                            .format(
                                    identities.languageOf(player.getUniqueId()),
                                    MESSAGES.smp().grave().experience(experience)));
                    sounds.play(player, Feedback.SMALL_SUCCESS);
                }
            });
        });
    }

    /** What is in a grave window's content slots, without its footer. */
    public static ItemStack[] contentOf(final Inventory inventory) {
        final int contentRows = inventory.getSize() / 9 - 1;
        return java.util.Arrays.copyOf(inventory.getContents(), GravePanel.contentSlots(contentRows));
    }
}

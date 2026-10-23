package eu.nordtal.season.smp.grave;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.config.DeathsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.feedback.WorldEffects;
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
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Creates, draws, opens and removes graves.
 * Each grave consists of display entities, an {@link Interaction} and a barrier block placed by {@link GraveBarrier}.
 * Any player may open a grave.
 */
public final class Graves {

    record Left(@Example("PT25M") Duration left) {}

    record Experience(@Example("30") int experience) {}

    static final DocumentSpec<Left> HOLOGRAM =
            DocumentSpec.game("smp", "grave-hologram", "Graves", DocumentSpec.Shape.HOLOGRAM, Left.class);
    static final DocumentSpec<Experience> EXPERIENCE =
            DocumentSpec.game("smp", "grave-experience", "Graves", DocumentSpec.Shape.CHAT, Experience.class);

    private final Plugin plugin;
    private final GraveDao dao;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final SmpSounds sounds;
    private final WorldEffects effects;
    private final DeathsSpec config;

    private final Map<UUID, List<org.bukkit.entity.Entity>> parts = new HashMap<>();

    private final Map<UUID, UUID> byInteraction = new HashMap<>();

    private final Map<UUID, GraveRow> open = new HashMap<>();

    /** Maps a grave id to its countdown hologram. It has no entry if decay is off. */
    private final Map<UUID, TextDisplay> holograms = new HashMap<>();

    /** Maps a grave id to the epoch millisecond at which {@link #tickHolograms()} next updates its hologram. */
    private final Map<UUID, Long> nextHologramUpdate = new HashMap<>();

    /** Maps a grave id to its window. All viewers of a grave share one window. */
    private final Map<UUID, GraveWindow> shown = new HashMap<>();

    private final Clock clock;

    public Graves(
            final Plugin plugin,
            final GraveDao dao,
            final Identities identities,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final SmpSounds sounds,
            final WorldEffects effects,
            final DeathsSpec config,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.dao = dao;
        this.identities = identities;
        this.renderer = renderer;
        this.documents = documents;
        this.sounds = sounds;
        this.effects = effects;
        this.config = config;
    }

    /** Reads the open graves off the main thread and draws them on the main thread. */
    public void restore() {
        PaperScheduler.of(plugin).execute(() -> {
            final List<GraveRow> rows = dao.openGraves();
            PaperScheduler.of(plugin).onMain(() -> draw(rows));
        });
    }

    private void draw(final List<GraveRow> rows) {
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
     * Stores a grave in the database and draws it. The caller has already removed the items from the player.
     *
     * @param at         the death location
     * @param contents   the items of the grave
     * @param experience the experience that the player who empties the grave receives
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

        PaperScheduler.of(plugin).execute(() -> {
            dao.createGrave(
                    ownerId,
                    grave.getWorld().getName(),
                    grave.getBlockX(),
                    grave.getBlockY(),
                    grave.getBlockZ(),
                    bytes,
                    experience);
            final List<GraveRow> rows = dao.openGraves();
            PaperScheduler.of(plugin)
                    .onMain(() -> rows.stream()
                            .filter(row -> !open.containsKey(row.id()))
                            .forEach(row -> {
                                final World world = Bukkit.getWorld(row.world());
                                if (world != null) {
                                    draw(row, new Location(world, row.x() + 0.5, row.y(), row.z() + 0.5));
                                }
                            }));
        });
    }

    private static final double HOLOGRAM_HEIGHT = 1.6;

    /** The remaining time below which the hologram updates every second instead of every minute. */
    private static final Duration HOLOGRAM_FINAL_STRETCH = Duration.ofMinutes(1);

    private static final long HOLOGRAM_REFRESH_MINUTES_MS =
            Duration.ofMinutes(1).toMillis();
    private static final long HOLOGRAM_REFRESH_SECONDS_MS =
            Duration.ofSeconds(1).toMillis();

    /** Spawns the chest, the skull, the {@link Interaction} and, if decay is on, the hologram of {@code row}. */
    private void draw(final GraveRow row, final Location at) {
        final World world = at.getWorld();
        final List<org.bukkit.entity.Entity> entities = new ArrayList<>(4);

        // A BlockDisplay is drawn from its corner, so the translation moves it by half a block.
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
            // The owner is null after the account was unlinked. The head then stays plain.
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(row.ownerUuid())));
        }
        final ItemDisplay skull = world.spawn(at, ItemDisplay.class, display -> {
            display.setItemStack(head);
            display.setPersistent(false);
            display.setBillboard(Display.Billboard.FIXED);
            // GraveHead computes its transformation for NONE.
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setTransformation(GraveHead.transformation());
        });
        entities.add(skull);

        final Interaction click = world.spawn(at, Interaction.class, interaction -> {
            // The width exceeds one block, so a click on the side of the barrier reaches the grave.
            interaction.setInteractionWidth(1.1f);
            interaction.setInteractionHeight(1.2f);
            interaction.setResponsive(true);
            interaction.setPersistent(false);
        });
        entities.add(click);

        spawnHologramIfDecaying(row, at, world, entities);
        blockOf(world, row.x(), row.y(), row.z())
                .filter(block -> GraveBarrier.placesInto(there(block)))
                .ifPresent(block -> block.setType(Material.BARRIER));

        parts.put(row.id(), entities);
        byInteraction.put(click.getUniqueId(), row.id());
        open.put(row.id(), row);
    }

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

    /** Returns the time until {@code row} decays, or zero if decay is off. */
    private Duration timeLeft(final GraveRow row) {
        final int hours = config.graveMaxAgeHours();
        if (hours <= 0) {
            return Duration.ZERO;
        }
        final Duration timeLeft =
                Duration.between(clock.instant(), row.created().plus(Duration.ofHours(hours)));
        return timeLeft.isNegative() ? Duration.ZERO : timeLeft;
    }

    /** Returns the hologram text in the language of the dead player, or English if the owner is null. */
    private Component hologramText(final GraveRow row, final Duration timeLeft) {
        final Locale locale = row.ownerUuid() == null ? Locale.ENGLISH : identities.languageOf(row.ownerUuid());
        // Outside the final stretch the text shows whole minutes.
        final Duration shown =
                timeLeft.compareTo(HOLOGRAM_FINAL_STRETCH) < 0 ? timeLeft : timeLeft.truncatedTo(ChronoUnit.MINUTES);
        return renderer.format(locale, HOLOGRAM.with(new Left(shown)));
    }

    private static long refreshInterval(final Duration timeLeft) {
        return timeLeft.compareTo(HOLOGRAM_FINAL_STRETCH) < 0
                ? HOLOGRAM_REFRESH_SECONDS_MS
                : HOLOGRAM_REFRESH_MINUTES_MS;
    }

    /** Updates the holograms that are due. Call it once a second on the main thread. */
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

    /** Returns the block at the position, or empty if the position is outside the world height. */
    private static Optional<Block> blockOf(final World world, final int x, final int y, final int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return Optional.empty();
        }
        return Optional.of(world.getBlockAt(x, y, z));
    }

    private static GraveBarrier.There there(final Block block) {
        if (block.getType() == Material.BARRIER) {
            return GraveBarrier.There.BARRIER;
        }
        return block.isEmpty() ? GraveBarrier.There.EMPTY : GraveBarrier.There.OTHER;
    }

    /** Sets the block to air if it is a barrier and no other open grave is at the position. */
    private void clearBarrier(final World world, final int x, final int y, final int z) {
        final boolean anotherGraveThere = open.values().stream()
                .anyMatch(other ->
                        other.world().equals(world.getName()) && other.x() == x && other.y() == y && other.z() == z);
        blockOf(world, x, y, z)
                .filter(block -> GraveBarrier.clears(there(block), anotherGraveThere))
                .ifPresent(block -> block.setType(Material.AIR));
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
     * Deletes the graves older than {@code hours} from the database and removes their displays and barriers.
     * Call it off the main thread.
     *
     * @param hours the maximum age in hours. A value of 0 or less does nothing.
     */
    public void expire(final int hours) {
        if (hours <= 0) {
            return;
        }
        final List<ExpiredGrave> gone = dao.expireGravesOlderThan(hours);
        if (gone.isEmpty()) {
            return;
        }
        PaperScheduler.of(plugin).onMain(() -> {
            for (final ExpiredGrave grave : gone) {
                erase(grave.id());
                final World world = Bukkit.getWorld(grave.world());
                if (world != null) {
                    clearBarrier(world, grave.x(), grave.y(), grave.z());
                    sounds.playAt(
                            new Location(world, grave.x() + 0.5, grave.y() + 0.5, grave.z() + 0.5), Feedback.RECLAIMED);
                }
            }
            plugin.getLogger()
                    .info("expired " + gone.size() + " grave(s) older than " + hours + "h, contents included");
        });
    }

    /** Removes all grave displays. The database rows and the barrier blocks stay. */
    public void clearDisplays() {
        List.copyOf(parts.keySet()).forEach(this::erase);
        shown.clear();
    }

    public Optional<UUID> graveOfInteraction(final UUID interactionId) {
        return Optional.ofNullable(byInteraction.get(interactionId));
    }

    /** Opens the grave window for the player. Main thread only. */
    public void open(final Player player, final UUID graveId) {
        final GraveRow row = open.get(graveId);
        if (row == null) {
            return;
        }
        final Locale locale = identities.languageOf(player.getUniqueId());

        shown.computeIfAbsent(graveId, id -> window(row, locale)).open(player);

        // The sound plays at the grave location.
        final org.bukkit.World world = Bukkit.getWorld(row.world());
        if (world != null) {
            effects.graveOpened(new org.bukkit.Location(world, row.x(), row.y(), row.z()));
        }
    }

    private GraveWindow window(final GraveRow row, final Locale locale) {
        final ItemStack[] contents = ItemStack.deserializeItemsFromBytes(row.contents());
        final int contentRows = GravePanel.contentRows(contents.length);
        final GameDocument document = new GameDocument(
                renderer,
                documents.applied(GraveWindow.DOCUMENT),
                Viewer.of(locale),
                new GraveWindow.Window(row.experience()));
        final GraveWindow menu = new GraveWindow(
                this,
                row.id(),
                contentRows,
                document.title(),
                row.experience() > 0 ? document.label("experience-line") : "",
                document.label("take-all-button"));
        final Inventory window = menu.getInventory();

        final int slots = GravePanel.contentSlots(contentRows);
        for (int slot = 0; slot < slots && slot < contents.length; slot++) {
            window.setItem(slot, contents[slot]);
        }

        window.setItem(GravePanel.headSlot(contentRows), head(row, document));

        final ItemStack experience = BlankItem.of(document.item("experience"));
        GravePanel.experienceSlots(contentRows).forEach(slot -> window.setItem(slot, experience));

        final ItemStack takeAll = BlankItem.of(document.item("take-all"));
        GravePanel.takeAllSlots(contentRows).forEach(slot -> window.setItem(slot, takeAll));

        return menu;
    }

    /** Returns the head of the dead player. Its lore holds the time and the place of death. */
    private static ItemStack head(final GraveRow row, final GameDocument document) {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        final UUID owner = row.ownerUuid();
        final String name =
                owner == null ? null : Bukkit.getOfflinePlayer(owner).getName();
        head.editMeta(meta -> {
            if (meta instanceof SkullMeta skull && owner != null) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            }
        });
        final PlayerContext player = owner == null || name == null ? null : PlayerContext.of(PlayerId.of(owner), name);
        return BlankItem.described(
                head,
                document.item(
                        "owner",
                        new GraveWindow.Owner(player != null, player, row.created(), row.x(), row.y(), row.z())));
    }

    /** Moves the content items into the player's inventory and drops what does not fit. */
    void takeAll(final Player player, final GraveWindow window) {
        final Inventory inventory = window.getInventory();
        final Location dropAt = java.util.Objects.requireNonNull(player.getLocation());
        for (int slot = 0; slot < GravePanel.contentSlots(window.contentRows()); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            inventory.setItem(slot, null);
            player.getInventory()
                    .addItem(stack)
                    .values()
                    .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
        }
    }

    /** Calls {@link #settle} one tick after a window closes, when the viewer is no longer listed. */
    void onClosed(final Player player, final GraveWindow window) {
        if (shown.get(window.graveId()) != window) {
            return;
        }
        PaperScheduler.of(plugin).onMain(() -> settle(player, window));
    }

    private void settle(final Player player, final GraveWindow window) {
        final Inventory inventory = window.getInventory();
        if (!inventory.getViewers().isEmpty()) {
            // The last viewer to close the window settles it.
            return;
        }
        final UUID graveId = window.graveId();
        if (!shown.remove(graveId, window)) {
            return;
        }
        final GraveRow row = open.get(graveId);
        if (row == null) {
            return;
        }

        final ItemStack[] left = contentOf(inventory);
        final boolean contentGone = java.util.Arrays.stream(left)
                .allMatch(stack -> stack == null || stack.getType().isAir());

        // The head does not count as content.
        if (!contentGone) {
            keepRemainder(graveId, row, left);
            return;
        }

        returnHeadToPlayer(inventory, player);
        finishLooting(graveId, row, player);
    }

    /** Stores the remaining items in {@link #open} and in the database. */
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
        PaperScheduler.of(plugin).execute(() -> dao.updateGraveContents(graveId, remaining));
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

    /** Marks the grave as looted. If that succeeds, it erases the grave and gives the experience to the player. */
    private void finishLooting(final UUID graveId, final GraveRow row, final Player player) {
        // The database stores the Discord id of the looter.
        final String looterId = identities
                .discordIdOf(player.getUniqueId())
                .map(DiscordId::value)
                .orElse(null);
        final int experience = row.experience();
        PaperScheduler.of(plugin).execute(() -> {
            if (dao.markGraveLooted(graveId, looterId).isEmpty()) {
                return;
            }
            PaperScheduler.of(plugin).onMain(() -> {
                erase(graveId);

                final World graveWorld = Bukkit.getWorld(row.world());
                if (graveWorld != null) {
                    clearBarrier(graveWorld, row.x(), row.y(), row.z());
                    sounds.playAt(
                            new Location(graveWorld, row.x() + 0.5, row.y() + 0.5, row.z() + 0.5), Feedback.RECLAIMED);
                }

                if (experience > 0 && player.isOnline()) {
                    player.giveExp(experience);
                    player.sendMessage(renderer.format(
                            identities.languageOf(player.getUniqueId()), EXPERIENCE.with(new Experience(experience))));
                    sounds.play(player, Feedback.SMALL_SUCCESS);
                }
            });
        });
    }

    public static ItemStack[] contentOf(final Inventory inventory) {
        final int contentRows = inventory.getSize() / 9 - 1;
        return java.util.Arrays.copyOf(inventory.getContents(), GravePanel.contentSlots(contentRows));
    }
}

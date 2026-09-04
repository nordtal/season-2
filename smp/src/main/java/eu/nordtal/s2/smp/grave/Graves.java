package eu.nordtal.s2.smp.grave;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.db.GraveRow;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.db.SmpDao;

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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Graves: what a death leaves behind, everywhere except the duel arena.
 *
 * <h2>They are not made of blocks</h2>
 * Decided 2026-09-01. A grave <em>looks</em> like a double dark oak chest with the player's head on
 * top, and it is three display entities plus an {@link Interaction} to click. Real blocks were the
 * first design and working through the cases sank it: a death in the void, in lava, under the Nether
 * roof or inside somebody's wall would each have to replace two blocks that belong to someone, and
 * graves stand forever, so over a season Nordtal would accumulate a chest at every place anybody
 * ever died.
 *
 * <p>Nothing is lost by it. The custom inventory was needed anyway - taking the items is what credits
 * the experience back - and a display cannot land in a wall, cannot collide with a second grave, and
 * is simply gone when the grave is emptied.
 *
 * <h2>Anyone may open one</h2>
 * No timer, no ownership lock (docs/smp.md#death-and-graves). PvP is on everywhere and a grave is
 * open to everyone, so killing somebody and emptying their grave is mechanically possible. That is
 * accepted rather than closed off: this is a peaceful server by agreement, and locking a grave to its
 * owner would also stop a friend from bringing somebody's things back.
 */
public final class Graves implements InventoryHolder {

    private final Plugin plugin;
    private final SmpDao dao;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;

    /** Grave id -> the entities drawing it, so they can be removed together. */
    private final Map<UUID, List<org.bukkit.entity.Entity>> parts = new HashMap<>();

    /** Interaction entity id -> grave id, which is how a click finds its grave. */
    private final Map<UUID, UUID> byInteraction = new HashMap<>();

    /** Grave id -> what is in it right now. Emptied graves are removed from here. */
    private final Map<UUID, GraveRow> open = new HashMap<>();

    /** Open inventory -> the grave it is showing. */
    private final Map<Inventory, UUID> viewing = new HashMap<>();

    public Graves(final Plugin plugin, final SmpDao dao, final Messages messages,
                  final PlayerLocales locales, final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    /**
     * Whether {@code inventory} is a grave standing open right now.
     *
     * <p>A grave inventory is created with a null holder and recognised by identity here, which is
     * why {@code SurfaceListener} takes this as a predicate rather than checking for a marker
     * interface.
     */
    public boolean isShowingGrave(final Inventory inventory) {
        return viewing.containsKey(inventory);
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
    public void create(final String ownerId, final UUID ownerUuid, final Location at,
                       final ItemStack[] contents, final int experience) {
        final byte[] bytes = ItemStack.serializeItemsAsBytes(contents);
        final Location grave = at.getBlock().getLocation().add(0.5, 0, 0.5);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            dao.createGrave(ownerId, grave.getWorld().getName(), grave.getBlockX(), grave.getBlockY(),
                    grave.getBlockZ(), bytes, experience);
            // Read back rather than invented locally, so the id is the database's and a restart
            // draws exactly what a fresh death drew.
            final List<GraveRow> rows = dao.openGraves();
            Bukkit.getScheduler().runTask(plugin, () -> rows.stream()
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

    private void draw(final GraveRow row, final Location at) {
        final World world = at.getWorld();
        final List<org.bukkit.entity.Entity> entities = new ArrayList<>(3);

        final BlockDisplay chest = world.spawn(at, BlockDisplay.class, display -> {
            display.setBlock(Material.DARK_OAK_PLANKS.createBlockData());
            display.setPersistent(false);
            display.setTransformation(new Transformation(
                    new Vector3f(-0.5f, 0f, -0.5f), new AxisAngle4f(),
                    new Vector3f(1f, 0.6f, 1f), new AxisAngle4f()));
        });
        entities.add(chest);

        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (row.ownerUuid() != null) {
            // The face on top is the person who died. Null only when the account link is gone, which
            // means somebody was unlinked after dying - a plain head is the right answer there, not
            // a guess.
            head.editMeta(SkullMeta.class,
                    meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(row.ownerUuid())));
        }
        final ItemDisplay skull = world.spawn(at.clone().add(0, 0.6, 0), ItemDisplay.class, display -> {
            display.setItemStack(head);
            display.setPersistent(false);
            display.setBillboard(Display.Billboard.FIXED);
        });
        entities.add(skull);

        final Interaction click = world.spawn(at, Interaction.class, interaction -> {
            interaction.setInteractionWidth(1.0f);
            interaction.setInteractionHeight(1.2f);
            interaction.setResponsive(true);
            interaction.setPersistent(false);
        });
        entities.add(click);

        parts.put(row.id(), entities);
        byInteraction.put(click.getUniqueId(), row.id());
        open.put(row.id(), row);
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
    }

    /** Removes every display this plugin drew. Called at disable; the rows stay in the database. */
    public void clearDisplays() {
        List.copyOf(parts.keySet()).forEach(this::erase);
        viewing.clear();
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
        final ItemStack[] contents = ItemStack.deserializeItemsFromBytes(row.contents());

        final int size = Math.max(9, ((contents.length + 8) / 9) * 9);
        final Inventory inventory = Bukkit.createInventory(null, Math.min(54, size),
                MessageRenderer.of(messages).get(locale, "smp.grave.title"));
        inventory.setContents(java.util.Arrays.copyOf(contents, inventory.getSize()));

        viewing.put(inventory, graveId);
        player.openInventory(inventory);
    }

    /**
     * Called when a grave inventory is closed: whatever is left goes back, and an empty one is
     * finished.
     *
     * <p>The experience is credited on the grave becoming empty rather than on each item taken -
     * one death, one refund, whoever finished the job.
     */
    public void onClosed(final Player player, final Inventory inventory) {
        final UUID graveId = viewing.remove(inventory);
        if (graveId == null) {
            return;
        }
        final GraveRow row = open.get(graveId);
        if (row == null) {
            return;
        }

        final boolean empty = java.util.Arrays.stream(inventory.getContents())
                .allMatch(stack -> stack == null || stack.getType().isAir());
        if (!empty) {
            // Not finished. Keep what is left, so somebody can come back for the rest - or somebody
            // else can.
            final byte[] remaining = ItemStack.serializeItemsAsBytes(inventory.getContents());
            open.put(graveId, new GraveRow(row.id(), row.ownerId(), row.ownerUuid(), row.world(),
                    row.x(), row.y(), row.z(), remaining, row.experience()));
            return;
        }

        final String looterId = player.getUniqueId().toString();
        final int experience = row.experience();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (dao.markGraveLooted(graveId, looterId).isEmpty()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                erase(graveId);
                if (experience > 0 && player.isOnline()) {
                    player.giveExp(experience);
                    player.sendMessage(MessageRenderer.of(messages).format(
                            locales.of(player.getUniqueId()), "smp.grave.experience",
                            "experience", experience));
                    sounds.play(player, Feedback.SMALL_SUCCESS);
                }
            });
        });
    }

    /** Forgets every grave in a world, for the daily farm-world reset. Main thread. */
    public void forgetWorld(final String world) {
        List.copyOf(open.values()).stream()
                .filter(row -> row.world().equals(world))
                .forEach(row -> erase(row.id()));
    }
}

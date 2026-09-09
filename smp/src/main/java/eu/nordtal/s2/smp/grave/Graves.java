package eu.nordtal.s2.smp.grave;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.db.GraveRow;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.WorldEffects;
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
    private final eu.nordtal.s2.smp.player.Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;
    private final WorldEffects effects;

    /** Grave id -> the entities drawing it, so they can be removed together. */
    private final Map<UUID, List<org.bukkit.entity.Entity>> parts = new HashMap<>();

    /** Interaction entity id -> grave id, which is how a click finds its grave. */
    private final Map<UUID, UUID> byInteraction = new HashMap<>();

    /** Grave id -> what is in it right now. Emptied graves are removed from here. */
    private final Map<UUID, GraveRow> open = new HashMap<>();

    /**
     * Grave id -> the one window showing it, however many people are looking.
     *
     * <h2>Why one and not one per viewer</h2>
     * A grave is open to anybody (see the class comment), so two people can right-click the same
     * one in the same second. While each got an inventory of their own, both were filled from the
     * same stored contents and each close wrote its own whole snapshot back: two looters each took
     * the lot, and the grave held every item twice. Not a lost write - a duplicated one, of
     * whatever the dead player was carrying (CodeRabbit, PR #8).
     *
     * <p>One shared inventory is what a vanilla chest does, and it needs no rule of its own: both
     * looters watch the same slots empty, and taking an item is a main-thread click on one object.
     * The grave is settled when the last of them closes it - see {@link #onClosed}.</p>
     */
    private final Map<UUID, Inventory> shown = new HashMap<>();

    public Graves(final Plugin plugin, final SmpDao dao,
                  final eu.nordtal.s2.smp.player.Identities identities, final Messages messages,
                  final PlayerLocales locales, final SmpSounds sounds, final WorldEffects effects) {
        this.plugin = plugin;
        this.dao = dao;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
        this.effects = effects;
    }

    /**
     * Whether {@code inventory} is a grave standing open right now.
     *
     * <p>A grave inventory is created with a null holder and recognised by identity here, which is
     * why {@code SurfaceListener} takes this as a predicate rather than checking for a marker
     * interface.
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
        shown.remove(graveId);
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

        // The window this grave already has, if somebody else is standing in it. Building a second
        // one from the same stored contents is what duplicated them - see the field.
        //
        // The cost is that the second looter reads the first looter's title: it carries no name and
        // no number, only the word for "grave", so the whole of the difference is the language it
        // is written in. A shared window in one of two languages is a better trade than a private
        // window that doubles the loot.
        final Inventory inventory = shown.computeIfAbsent(graveId, id -> window(row, locale));
        player.openInventory(inventory);

        // At the grave, not at the player: the person opening it is standing next to it, and
        // anybody else nearby is being told a grave has just been disturbed.
        final org.bukkit.World world = Bukkit.getWorld(row.world());
        if (world != null) {
            effects.graveOpened(new org.bukkit.Location(world, row.x(), row.y(), row.z()));
        }
    }

    /**
     * Builds the one window a grave is shown in - design {@code G1}, drawn by {@link GravePanel}.
     *
     * <p>The footer is not decoration. It is what makes the waiting experience visible - it used to
     * be credited silently on the last item leaving, so somebody who took one stack and walked away
     * never learned there was more to come - and every one of its nine cells holds an item, because
     * a shift-click from the player's own inventory goes into the first free slot of the window and
     * a free footer cell is a slot outside everything {@link #settle} writes back.</p>
     */
    private Inventory window(final GraveRow row, final Locale locale) {
        final ItemStack[] contents = ItemStack.deserializeItemsFromBytes(row.contents());
        final int contentRows = GravePanel.contentRows(contents.length);
        final MessageRenderer renderer = MessageRenderer.of(messages);

        final Inventory window = Bukkit.createInventory(null,
                GravePanel.rows(contentRows) * 9,
                GravePanel.title(renderer.get(locale, "smp.grave.title"), contentRows,
                        row.experience() > 0
                                ? messages.format(locale, "smp.grave.experience-line",
                                        Map.of("experience", row.experience()))
                                : "",
                        messages.get(locale, "smp.grave.take-all-button")));

        final int slots = GravePanel.contentSlots(contentRows);
        for (int slot = 0; slot < slots && slot < contents.length; slot++) {
            window.setItem(slot, contents[slot]);
        }

        window.setItem(GravePanel.headSlot(contentRows), head(row, renderer, locale));

        // The name is on the head rather than in the window's title, which is the artifact's own
        // fallback for E7: one window is shared by everybody standing in the grave, so a title
        // carrying a name would also carry the first opener's language for the second opener.
        final ItemStack experience = eu.nordtal.s2.papercommon.menu.BlankItem.of(
                renderer.get(locale, "smp.grave.experience-tooltip"),
                List.of(renderer.format(locale, "smp.grave.experience-hint",
                        "experience", row.experience())));
        GravePanel.experienceSlots(contentRows).forEach(slot -> window.setItem(slot, experience));

        final ItemStack takeAll = eu.nordtal.s2.papercommon.menu.BlankItem.of(
                renderer.get(locale, "smp.grave.take-all"),
                List.of(renderer.get(locale, "smp.grave.take-all-hint")));
        GravePanel.takeAllSlots(contentRows).forEach(slot -> window.setItem(slot, takeAll));

        return window;
    }

    /** The dead player's own head, which is where their name is (see {@link #window}). */
    private ItemStack head(final GraveRow row, final MessageRenderer renderer, final Locale locale) {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        final String name = row.ownerUuid() == null ? null
                : Bukkit.getOfflinePlayer(row.ownerUuid()).getName();
        head.editMeta(meta -> {
            if (meta instanceof SkullMeta skull && row.ownerUuid() != null) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(row.ownerUuid()));
            }
            meta.displayName(renderer.format(locale,
                            name == null ? "smp.grave.owner-unknown" : "smp.grave.owner",
                            "player", name == null ? "" : name)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(List.of(renderer.get(locale, "smp.grave.owner-hint")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        });
        return head;
    }

    /**
     * A click inside a grave window.
     *
     * <p>The content rows stay free - taking things out is the whole point, and putting something
     * in is a thing people do with a friend's grave. The footer is locked, and one of its cells is
     * the button that empties the grave in one go.</p>
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
     * <p>Closing the window is what settles the grave, so this does not credit the experience or
     * erase anything itself - it moves the items and closes, and the path every other loot takes
     * does the rest. Two people standing in the same grave are safe by the same mechanism: the
     * slots are emptied here on the main thread, so the second one's click finds nothing.</p>
     */
    private void takeAll(final Player player, final Inventory inventory, final int contentRows) {
        boolean took = false;
        for (int slot = 0; slot < GravePanel.contentSlots(contentRows); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            inventory.setItem(slot, null);
            player.getInventory().addItem(stack).values().forEach(left ->
                    player.getWorld().dropItemNaturally(player.getLocation(), left));
            took = true;
        }
        if (!took) {
            sounds.play(player, Feedback.REFUSED);
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
     * <p><b>Settled a tick later, when the window is empty of people.</b> One window can have
     * several viewers (see {@link #shown}), and writing its contents back while somebody is still
     * taking things out of it would store a snapshot that is already out of date. Bukkit fires the
     * close before it drops the viewer, so "is anybody left?" has no honest answer inside the
     * event - one tick later it has exactly one.</p>
     */
    public void onClosed(final Player player, final Inventory inventory) {
        if (!shown.containsValue(inventory)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> settle(player, inventory));
    }

    private void settle(final Player player, final Inventory inventory) {
        if (!inventory.getViewers().isEmpty()) {
            // Somebody else still has it open, and they will come through here when they close it.
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

        // The CONTENT slots and not the whole window: the footer holds a head and five blanks that
        // are furniture, so reading the whole array would find a grave that is never empty and
        // would write those five into the row as if somebody had left them there.
        final ItemStack[] left = contentOf(inventory);
        final boolean empty = java.util.Arrays.stream(left)
                .allMatch(stack -> stack == null || stack.getType().isAir());
        if (!empty) {
            // Not finished. Keep what is left, so somebody can come back for the rest - or somebody
            // else can.
            final byte[] remaining = ItemStack.serializeItemsAsBytes(left);
            open.put(graveId, new GraveRow(row.id(), row.ownerId(), row.ownerUuid(), row.world(),
                    row.x(), row.y(), row.z(), remaining, row.experience()));
            // AND IN THE DATABASE, which is the half that was missing. The map above is this
            // process's memory; the enable-time restore reads the row. So a half-emptied grave came
            // back full after any restart while the items already taken stayed in the looter's
            // inventory - the same stack twice (finding 133).
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> dao.updateGraveContents(graveId, remaining));
            return;
        }

        // The looter's DISCORD id, and not their Minecraft UUID.
        //
        // `looted_by` is varchar(32), the same shape as `owner_id` beside it, because every person
        // in this schema is a discord id - a UUID's 36 characters do not fit. Passing
        // getUniqueId().toString() made `markGraveLooted` throw
        // "value too long for type character varying(32)" on EVERY loot, which killed the async
        // task that follows it: no row was ever marked, no grave was ever erased, and nobody ever
        // got their experience back. It surfaced only by reading the table after a real loot -
        // in the game the items come out and the window closes, which is what a player checks
        // (finding 132).
        //
        // Null is a legitimate answer: an unlinked looter cannot be named, and the grave still has
        // to close. The column is nullable for exactly that.
        final String looterId = identities.discordIdOf(player.getUniqueId()).orElse(null);
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

    /**
     * What is in a grave window's content slots, without its footer.
     *
     * <p>Public so {@code OneGraveOneWindowTest} and the grave's own panel test can hold the two
     * halves apart by the same rule the settle uses, rather than by two copies of the arithmetic.
     */
    public static ItemStack[] contentOf(final Inventory inventory) {
        final int contentRows = inventory.getSize() / 9 - 1;
        return java.util.Arrays.copyOf(inventory.getContents(),
                GravePanel.contentSlots(contentRows));
    }

    /** Forgets every grave in a world, for the daily farm-world reset. Main thread. */
    public void forgetWorld(final String world) {
        List.copyOf(open.values()).stream()
                .filter(row -> row.world().equals(world))
                .forEach(row -> erase(row.id()));
    }
}

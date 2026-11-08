package eu.nordtal.season.bridgemod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Util;
import net.minecraft.world.BossEvent;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/** Carries each request out on the client thread; the future fails with a message or yields fields for the reply. */
final class ClientActions implements Function<Request, CompletableFuture<?>> {

    private static final UUID BOSS_BAR = UUID.nameUUIDFromBytes("nordtal-bridge".getBytes(StandardCharsets.UTF_8));
    private static final MenuType<?>[] CHESTS = {
        MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
        MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6
    };

    private static final int NO_CONTAINER = -1;

    /** The first id of an entity the bridge adds; a server counts its entities up from 1, so none meets it. */
    private static final int FIRST_ENTITY = -1_000_000;

    private final Minecraft minecraft;
    private final Map<BlockPos, BlockState> replaced = new HashMap<>();
    private @Nullable ClientLevel replacedIn;
    private final Map<BlockPos, Integer> displays = new HashMap<>();
    private int nextEntity = FIRST_ENTITY;
    private boolean bossBarShown;

    ClientActions(final Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public CompletableFuture<?> apply(final Request request) {
        if (request instanceof Request.Reload) {
            return CompletableFuture.completedFuture(null)
                    .thenComposeAsync(_ -> minecraft.reloadResourcePacks(), minecraft);
        }
        if (request instanceof Request.Screenshot) {
            return CompletableFuture.completedFuture(null).thenComposeAsync(_ -> screenshot(), minecraft);
        }
        return minecraft.submit(() -> run(request));
    }

    private void run(final Request request) {
        final Hud hud = minecraft.gui.hud;
        switch (request) {
            case Request.Chat chat -> hud.getChat().addClientSystemMessage(component(chat.text()));
            case Request.Actionbar actionbar -> hud.setOverlayMessage(component(actionbar.text()), false);
            case Request.Title title -> {
                final JsonElement subtitle = title.subtitle();
                hud.resetTitleTimes();
                hud.setSubtitle(subtitle == null ? Component.empty() : component(subtitle));
                hud.setTitle(component(title.text()));
            }
            case Request.BossBar bar -> bossBar(hud, bar);
            case Request.Chest chest -> chest(chest);
            case Request.Give give -> give(give);
            case Request.Block block -> place(position(block.pos()), blockState(block.block()));
            case Request.Sound sound -> sound(sound);
            case Request.Rail rail -> rail(rail);
            case Request.Dialog dialog -> {
                final ClientPacketListener connection = connection();
                connection.handleShowDialog(
                        new ClientboundShowDialogPacket(dialog(connection.registryAccess(), dialog.dialog())));
            }
            case Request.Clear _ -> {
                removeBossBar(hud);
                hud.clearTitles();
                final ClientLevel level = minecraft.level;
                if (level != null && level.equals(replacedIn)) {
                    replaced.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_ALL));
                    displays.values().forEach(id -> level.removeEntity(id, Entity.RemovalReason.DISCARDED));
                }
                replaced.clear();
                displays.clear();
                replacedIn = null;
            }
            case Request.Reload _ -> throw new IllegalStateException("A reload runs through apply.");
            case Request.Screenshot _ -> throw new IllegalStateException("A screenshot runs through apply.");
        }
    }

    /** Reads a dialog, or the id of one in the registry, as `/dialog show` reads it. */
    static Holder<Dialog> dialog(final HolderLookup.Provider registries, final JsonElement json) {
        return Dialog.CODEC
                .parse(registries.createSerializationContext(JsonOps.INSTANCE), json)
                .getOrThrow(IllegalArgumentException::new);
    }

    /** Saves the frame into the screenshots folder as F2 does and yields the reply's {@code file}. */
    private CompletableFuture<JsonObject> screenshot() {
        final CompletableFuture<JsonObject> saved = new CompletableFuture<>();
        Screenshot.takeScreenshot(
                minecraft.gameRenderer.mainRenderTarget(),
                image -> Util.ioPool().execute(() -> {
                    try (image) {
                        final Path file =
                                screenshotFile(minecraft.gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR));
                        image.writeToFile(file);
                        final JsonObject reply = new JsonObject();
                        reply.addProperty("file", file.toAbsolutePath().toString());
                        saved.complete(reply);
                    } catch (Exception e) {
                        saved.completeExceptionally(e);
                    }
                }));
        return saved;
    }

    /** Creates an empty file in {@code folder} that no other screenshot has, named after the time as F2 names its. */
    static Path screenshotFile(final Path folder) throws IOException {
        Files.createDirectories(folder);
        final String name = Util.getFilenameFormattedDateTime();
        for (int count = 1; ; count++) {
            final Path file = folder.resolve(count == 1 ? name + ".png" : name + "_" + count + ".png");
            try {
                return Files.createFile(file);
            } catch (FileAlreadyExistsException taken) {
                // the next count
            }
        }
    }

    private void sound(final Request.Sound sound) {
        final Identifier id = Identifier.tryParse(sound.sound());
        if (id == null || minecraft.getSoundManager().getSoundEvent(id) == null) {
            throw new IllegalArgumentException("The loaded packs have no sound event '" + sound.sound() + "'.");
        }
        minecraft
                .getSoundManager()
                .play(SimpleSoundInstance.forUI(
                        SoundEvent.createVariableRangeEvent(id), sound.pitch(), sound.volume()));
    }

    private void bossBar(final Hud hud, final Request.BossBar bar) {
        removeBossBar(hud);
        final JsonElement text = bar.text();
        if (text == null) {
            return;
        }
        final ServerBossEvent event =
                new ServerBossEvent(BOSS_BAR, component(text), color(bar.color()), overlay(bar.overlay()));
        event.setProgress(bar.progress());
        hud.getBossOverlay().update(ClientboundBossEventPacket.createAddPacket(event));
        bossBarShown = true;
    }

    private void removeBossBar(final Hud hud) {
        if (bossBarShown) {
            hud.getBossOverlay().update(ClientboundBossEventPacket.createRemovePacket(BOSS_BAR));
            bossBarShown = false;
        }
    }

    private void chest(final Request.Chest chest) {
        final Inventory inventory = player().getInventory();
        minecraft.gui.setScreen(
                new ChestScreen(chestMenu(inventory, chest.rows()), inventory, component(chest.text())));
    }

    /**
     * A menu under an id that the game never uses, so a click on it reaches nothing.
     * The inventory is 0, and a server counts its menus from 1 to 100.
     */
    static ChestMenu chestMenu(final Inventory inventory, final int rows) {
        return new ChestMenu(CHESTS[rows - 1], NO_CONTAINER, inventory, new SimpleContainer(9 * rows), rows);
    }

    /** Shows the chest and ignores every click, so nothing reaches the inventory or the server. */
    private static final class ChestScreen extends ContainerScreen {

        ChestScreen(final ChestMenu menu, final Inventory inventory, final Component title) {
            super(menu, inventory, title);
        }

        @Override
        protected void slotClicked(
                final @Nullable Slot slot, final int index, final int button, final ContainerInput input) {}
    }

    private void give(final Request.Give give) {
        final LocalPlayer player = player();
        if (!player.hasInfiniteMaterials() || minecraft.gameMode == null) {
            throw new IllegalArgumentException("give needs creative mode.");
        }
        final ItemStack stack = stack(connection().registryAccess(), give.item(), give.count());
        final int slot = player.getInventory().getSelectedSlot();
        player.getInventory().setItem(slot, stack);
        minecraft.gameMode.handleCreativeModeItemAdd(stack, InventoryMenu.USE_ROW_SLOT_START + slot);
    }

    /** Reads the item as /give does, which refuses a count above the item's stack size. */
    static ItemStack stack(final HolderLookup.Provider registries, final String item, final int count) {
        try {
            return new ItemParser(registries).parse(new StringReader(item)).createItemStack(count);
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private BlockState blockState(final String block) {
        try {
            return BlockStateParser.parseForBlock(
                            connection().registryAccess().lookupOrThrow(Registries.BLOCK), block, false)
                    .blockState();
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Puts {@code state} at {@code pos} and keeps the block it replaced, which `clear` puts back.
     * A display that the bridge showed there goes.
     */
    private ClientLevel place(final BlockPos pos, final BlockState state) {
        final ClientLevel level = minecraft.level;
        if (level == null) {
            throw new IllegalArgumentException("This request needs a world.");
        }
        if (!level.equals(replacedIn)) {
            // A new world or dimension has new blocks and entities, so the old ones are not put back there.
            replaced.clear();
            displays.clear();
            replacedIn = level;
        }
        final Integer shown = displays.remove(pos);
        if (shown != null) {
            level.removeEntity(shown, Entity.RemovalReason.DISCARDED);
        }
        replaced.putIfAbsent(pos, level.getBlockState(pos));
        level.setBlock(pos, state, Block.UPDATE_ALL);
        return level;
    }

    /** Places a straight rail and the item on it as an item display, the way the server draws a station rail. */
    private void rail(final Request.Rail rail) {
        final ItemStack stack = stack(connection().registryAccess(), rail.item(), 1);
        final RailShape shape = shape(rail.axis(), player().getDirection());
        final BlockPos pos = position(rail.pos());
        final ClientLevel level = place(pos, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape));
        final Display.ItemDisplay display = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
        final SlotAccess slot = display.getSlot(0);
        if (slot == null) {
            throw new IllegalStateException("An item display has no slot for its item.");
        }
        slot.set(stack);
        display.setId(nextEntity--);
        display.snapTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, yaw(shape), 0);
        level.addEntity(display);
        displays.put(pos, display.getId());
    }

    /** The shape of a straight rail along {@code axis}, or along the way {@code facing} looks when it is null. */
    static RailShape shape(final @Nullable String axis, final Direction facing) {
        if (axis != null) {
            return axis.equals("east_west") ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        }
        return facing.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
    }

    /** The yaw the server gives the display of a station rail of {@code shape}. */
    static float yaw(final RailShape shape) {
        return shape == RailShape.EAST_WEST ? 90f : 0f;
    }

    private BlockPos position(final Request.@Nullable Position pos) {
        if (pos != null) {
            return new BlockPos(pos.x(), pos.y(), pos.z());
        }
        if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().relative(hit.getDirection());
        }
        throw new IllegalArgumentException("This request needs 'pos' or a block under the crosshair.");
    }

    private Component component(final JsonElement json) {
        final RegistryAccess registries = minecraft.getConnection() == null
                ? RegistryAccess.EMPTY
                : minecraft.getConnection().registryAccess();
        return ComponentSerialization.CODEC
                .parse(registries.createSerializationContext(JsonOps.INSTANCE), json)
                .getOrThrow(IllegalArgumentException::new);
    }

    private LocalPlayer player() {
        final LocalPlayer player = minecraft.player;
        if (player == null) {
            throw new IllegalArgumentException("This request needs a world.");
        }
        return player;
    }

    private ClientPacketListener connection() {
        final ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            throw new IllegalArgumentException("This request needs a world.");
        }
        return connection;
    }

    private static BossEvent.BossBarColor color(final String name) {
        return Arrays.stream(BossEvent.BossBarColor.values())
                .filter(color -> color.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("bossbar takes the color "
                        + Arrays.stream(BossEvent.BossBarColor.values())
                                .map(BossEvent.BossBarColor::getName)
                                .collect(Collectors.joining(", "))
                        + ", not '" + name + "'."));
    }

    private static BossEvent.BossBarOverlay overlay(final String name) {
        return Arrays.stream(BossEvent.BossBarOverlay.values())
                .filter(overlay -> overlay.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("bossbar takes the overlay "
                        + Arrays.stream(BossEvent.BossBarOverlay.values())
                                .map(BossEvent.BossBarOverlay::getName)
                                .collect(Collectors.joining(", "))
                        + ", not '" + name + "'."));
    }
}

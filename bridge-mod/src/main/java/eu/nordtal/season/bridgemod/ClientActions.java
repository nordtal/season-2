package eu.nordtal.season.bridgemod;

import com.google.gson.JsonElement;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/** Carries each request out on the client thread; the future fails with a message the reply passes on. */
final class ClientActions implements Function<Request, CompletableFuture<?>> {

    private static final UUID BOSS_BAR = UUID.nameUUIDFromBytes("nordtal-bridge".getBytes(StandardCharsets.UTF_8));
    private static final MenuType<?>[] CHESTS = {
        MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
        MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6
    };

    private static final int NO_CONTAINER = -1;

    private final Minecraft minecraft;
    private final Map<BlockPos, BlockState> replaced = new HashMap<>();
    private @Nullable ClientLevel replacedIn;
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
            case Request.Block block -> block(block);
            case Request.Clear _ -> {
                removeBossBar(hud);
                hud.clearTitles();
                final ClientLevel level = minecraft.level;
                if (level != null && level.equals(replacedIn)) {
                    replaced.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_ALL));
                }
                replaced.clear();
                replacedIn = null;
            }
            case Request.Reload _ -> throw new IllegalStateException("A reload runs through apply.");
        }
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

    private void block(final Request.Block block) {
        final ClientLevel level = minecraft.level;
        if (level == null) {
            throw new IllegalArgumentException("block needs a world.");
        }
        final BlockState state;
        try {
            state = BlockStateParser.parseForBlock(
                            connection().registryAccess().lookupOrThrow(Registries.BLOCK), block.block(), false)
                    .blockState();
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (!level.equals(replacedIn)) {
            // A new world or dimension has new blocks, so the old ones are not put back there.
            replaced.clear();
            replacedIn = level;
        }
        final BlockPos pos = position(block.pos());
        replaced.putIfAbsent(pos, level.getBlockState(pos));
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    private BlockPos position(final Request.@Nullable Position pos) {
        if (pos != null) {
            return new BlockPos(pos.x(), pos.y(), pos.z());
        }
        if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().relative(hit.getDirection());
        }
        throw new IllegalArgumentException("block needs 'pos' or a block under the crosshair.");
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

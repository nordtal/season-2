package eu.nordtal.s2.smp.headstart;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.config.WheelPrizeSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.player.PlayerSurfaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * What the winner of the start event carries into the season, paid on their first SMP join.
 *
 * {@link SmpDao#grantHeadStart} claims the flag and books the aura in one transaction before the items are handed over.
 */
public final class HeadStart implements Listener {

    private final Plugin plugin;
    private final SmpDao dao;
    private final Identities identities;
    private final PlayerSurfaces surfaces;
    private final SmpSpec config;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;

    public HeadStart(
            final Plugin plugin,
            final SmpDao dao,
            final Identities identities,
            final PlayerSurfaces surfaces,
            final SmpSpec config,
            final Messages messages,
            final PlayerLocales locales,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.identities = identities;
        this.surfaces = surfaces;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    /** Checked on every join, off the main thread, because only the winner's first join pays. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // Identities is filled at pre-login by JoinGate, so this is a map read.
        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            return;
        }
        final java.util.UUID mcUuid = player.getUniqueId();
        final String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> grant(mcUuid, name, discordId.get()));
    }

    private void grant(final java.util.UUID mcUuid, final String name, final String discordId) {
        if (!dao.startEventWinner().filter(discordId::equals).isPresent()) {
            return;
        }
        final int aura = config.hgWinnerAura();
        if (!dao.grantHeadStart(discordId, aura, AuraReason.HG_WINNER.stored())) {
            // Already paid: the ordinary answer on every join after the first.
            return;
        }
        final Integer balance = dao.auraOf(discordId).orElse(null);
        final List<ItemStack> items = items();

        // By uuid, not the Player captured at join.
        Bukkit.getScheduler().runTask(plugin, () -> hand(mcUuid, name, discordId, aura, balance, items));
    }

    private void hand(
            final java.util.UUID mcUuid,
            final String name,
            final String discordId,
            final int aura,
            final @Nullable Integer balance,
            final List<ItemStack> items) {
        if (balance != null) {
            identities.recordAura(mcUuid, balance);
        }
        final Player player = Bukkit.getPlayer(mcUuid);
        if (player == null) {
            // There is nothing to give back to, so the log names everything needed to finish this by hand.
            plugin.getLogger()
                    .warning(name + " left in the tick after their own join,"
                            + " so the start event's head start (" + aura + " aura and " + describe(items)
                            + ") was booked but the items were not handed over. The aura is in the books."
                            + " To offer the items again:"
                            + " UPDATE smp_player SET hg_winner_reward_granted = false WHERE discord_id ="
                            + " '" + discordId + "'; - which also books the aura a second time, so correct"
                            + " that with /smp aura. The id is spelled out because a Minecraft name is not"
                            + " a key in any of these tables, and this line is the whole of what somebody"
                            + " has to work from.");
            return;
        }

        // Whatever does not fit goes on the floor.
        final Location dropAt = java.util.Objects.requireNonNull(player.getLocation());
        for (final ItemStack stack : items) {
            player.getInventory()
                    .addItem(stack)
                    .values()
                    .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
        }

        final Locale locale = locales.of(mcUuid);
        // Two lines: items() drops unknown materials, so it must not claim spoils never handed over.
        player.sendMessage(MessageRenderer.of(messages)
                .format(
                        locale,
                        items.isEmpty()
                                ? MESSAGES.smp().headstart().grantedAuraOnly(aura)
                                : MESSAGES.smp().headstart().granted(aura)));
        sounds.play(player, Feedback.BIG_SUCCESS);
        // The prize is that the number is visible, so everybody is redrawn rather than only the winner.
        surfaces.refreshAll();
    }

    /** The configured items, skipping any this server does not know. */
    private List<ItemStack> items() {
        final List<ItemStack> stacks = new ArrayList<>();
        for (final WheelPrizeSpec entry : config.hgWinnerItems()) {
            final Material material = Material.matchMaterial(entry.item());
            if (material == null || !material.isItem()) {
                plugin.getLogger()
                        .warning("config.yml#hg-winner-items names '" + entry.item()
                                + "', which is not an item on this server - the rest of the head start is"
                                + " unaffected");
                continue;
            }
            stacks.add(new ItemStack(material, Math.max(1, Math.min(material.getMaxStackSize(), entry.amount()))));
        }
        return stacks;
    }

    private static String describe(final List<ItemStack> items) {
        if (items.isEmpty()) {
            return "no items";
        }
        final StringBuilder text = new StringBuilder();
        for (final ItemStack stack : items) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(stack.getAmount()).append("x ").append(stack.getType().name());
        }
        return text.toString();
    }
}

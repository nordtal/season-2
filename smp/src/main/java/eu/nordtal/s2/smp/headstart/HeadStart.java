package eu.nordtal.s2.smp.headstart;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.player.PlayerSurfaces;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the winner of the start event carries into the season.
 *
 * <p>The SMP grants it and {@code hunger-games} writes nothing: this module reads
 * {@code hg_game.winner_member_id}, resolves it to a Discord id, and pays out from its own config
 * the first time that player joins the SMP. Paying at the moment of the decision instead would let
 * a winner who never turns up be paid, and the head start is meant to be seen.
 *
 * <p>{@link SmpDao#grantHeadStart} claims the flag and books the aura in one transaction before the
 * items are handed over, so a player reconnecting twice in a second cannot win two elytras. Unlike
 * the wheel, this cannot be put back - {@code hg_winner_reward_granted} is a single boolean - so
 * the one path that can lose the items is logged with the {@code UPDATE} that retries it.
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

    public HeadStart(final Plugin plugin, final SmpDao dao, final Identities identities,
                     final PlayerSurfaces surfaces, final SmpSpec config, final Messages messages,
                     final PlayerLocales locales, final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.identities = identities;
        this.surfaces = surfaces;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    /**
     * Checked on every join, because the winner's first one is the only one that can be recognised
     * as first.
     *
     * <p>{@link EventPriority#MONITOR} and off the main thread: nothing here changes the join, and
     * the two queries behind it must not run on the server thread.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // Identities is filled at pre-login by JoinGate, so this is a map read. An unlinked player
        // cannot be the winner: the head start is booked against a Discord id.
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
            // Already paid - the ordinary answer on every join after the first.
            return;
        }
        final Integer balance = dao.auraOf(discordId).orElse(null);
        final List<ItemStack> items = items();

        // By uuid, not the Player captured at join: the winner may have reconnected between the
        // claim committing and this task running, and a captured instance of a reconnected player
        // answers isOnline() false for ever.
        Bukkit.getScheduler().runTask(plugin, () -> hand(mcUuid, name, discordId, aura, balance, items));
    }

    /** The main-thread half: the items, the number, the line and the sound. */
    private void hand(final java.util.UUID mcUuid, final String name, final String discordId,
                      final int aura, final Integer balance, final List<ItemStack> items) {
        if (balance != null) {
            identities.recordAura(mcUuid, balance);
        }
        final Player player = Bukkit.getPlayer(mcUuid);
        if (player == null) {
            // There is nothing to give back to, so the log names everything needed to finish this
            // by hand.
            plugin.getLogger().warning(name + " left in the tick after their own join,"
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

        // Whatever does not fit goes on the floor at their feet: this payout cannot be earned
        // again, so a full inventory must not swallow it.
        for (final ItemStack stack : items) {
            player.getInventory().addItem(stack).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }

        final Locale locale = locales.of(mcUuid);
        // Two lines, because items() drops any material this server does not know - the other
        // wording would claim spoils that were never handed over.
        player.sendMessage(MessageRenderer.of(messages).format(locale, items.isEmpty()
                ? "smp.headstart.granted-aura-only" : "smp.headstart.granted", "aura", aura));
        sounds.play(player, Feedback.BIG_SUCCESS);
        // The prize is that the number is visible, so everybody is redrawn rather than only the
        // winner.
        surfaces.refreshAll();
    }

    /**
     * The configured items, skipping any this server does not know.
     *
     * <p>The aura is already booked by the time this is read, so one bad line must not stop the
     * rest of the head start.</p>
     */
    private List<ItemStack> items() {
        final List<ItemStack> stacks = new ArrayList<>();
        for (final SmpSpec.WheelPrizeSpec entry : config.hgWinnerItems()) {
            final Material material = Material.matchMaterial(entry.item());
            if (material == null || !material.isItem()) {
                plugin.getLogger().warning("config.yml#hg-winner-items names '" + entry.item()
                        + "', which is not an item on this server - the rest of the head start is"
                        + " unaffected");
                continue;
            }
            stacks.add(new ItemStack(material, Math.max(1, Math.min(material.getMaxStackSize(),
                    entry.amount()))));
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

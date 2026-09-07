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
 * <h2>The SMP grants it, and {@code hunger-games} writes nothing</h2>
 * Decided 2026-09-01 (docs/smp.md#the-hunger-games-winners-head-start). The winner is recorded
 * exactly once, in {@code hg_game.winner_member_id}; this module reads that row, resolves it to a
 * Discord id through {@code hg_member}, and pays out from its own config the first time that player
 * joins the SMP. The alternative - the event plugin booking aura into {@code smp_aura_event} at the
 * moment of the decision - was dropped for two reasons that both still hold: it points a dependency
 * from the event at a module it otherwise shares only {@code :common} with, and it pays a winner who
 * never turns up for the season at all. The head start is meant to be <em>seen</em> by the people it
 * is a head start over.
 *
 * <h2>Aura buys nothing, so this is recognition and not power</h2>
 * The whole prize is a visible number in the tab list and at the top of the leaderboard, on a scale
 * where a top contributor finishes the season around 350. The items are the other half, and they
 * are deliberately things that are spent rather than things that compound.
 *
 * <h2>Spent before it is handed over</h2>
 * {@link SmpDao#grantHeadStart} claims the flag and books the aura in one transaction, and only
 * then are the items handed over - the same ordering the wheel uses, and for the same reason: a
 * player who reconnects twice in a second must not be able to win two elytras. The wheel can put a
 * spin back when the handover fails; this cannot, because {@code hg_winner_reward_granted} is a
 * single boolean and there is nothing to put back <em>to</em>. So the one path that can lose the
 * items - the winner logging off inside the tick after their own join - is logged loudly, by name,
 * with the exact items and the one {@code UPDATE} that lets it be tried again.
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
     * the two queries behind it are exactly the kind {@code CLAUDE.md} forbids on the server
     * thread. The cost on an ordinary join is one indexed lookup that returns a Discord id which is
     * not this player's, and then nothing.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // Identities is filled at pre-login by JoinGate, on the thread that is allowed to wait,
        // so this is a map read. An unlinked player cannot be the winner: a hunger games member row
        // requires a discord_user, and the head start is booked against a Discord id.
        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> grant(player, discordId.get()));
    }

    private void grant(final Player player, final String discordId) {
        if (!dao.startEventWinner().filter(discordId::equals).isPresent()) {
            return;
        }
        final int aura = config.hgWinnerAura();
        if (!dao.grantHeadStart(discordId, aura, AuraReason.HG_WINNER.stored())) {
            // Already paid. This is the ordinary answer on every join after the first, and on the
            // losing side of a double join - it is not a failure and says nothing.
            return;
        }
        final Integer balance = dao.auraOf(discordId).orElse(null);
        final List<ItemStack> items = items();

        Bukkit.getScheduler().runTask(plugin, () -> hand(player, aura, balance, items));
    }

    /** The main-thread half: the items, the number, the line and the sound. */
    private void hand(final Player player, final int aura, final Integer balance,
                      final List<ItemStack> items) {
        if (balance != null) {
            identities.recordAura(player.getUniqueId(), balance);
        }
        if (!player.isOnline()) {
            // See the class comment: there is nothing to give back to. Name everything a person
            // needs in order to finish this by hand, because the alternative is a winner who was
            // told nothing and got nothing.
            plugin.getLogger().warning(player.getName() + " left in the tick after their own join,"
                    + " so the start event's head start (" + aura + " aura and " + describe(items)
                    + ") was booked but the items were not handed over. The aura is in the books."
                    + " To offer the items again:"
                    + " UPDATE smp_player SET hg_winner_reward_granted = false WHERE discord_id ="
                    + " '<their discord id>'; - which also books the aura a second time, so correct"
                    + " that with /smp aura.");
            return;
        }

        // Whatever does not fit goes on the floor at their feet, exactly as the wheel does it:
        // this is the one payout in the season that cannot be earned again, and losing an elytra to
        // a full inventory is the kind of thing that is remembered for a season.
        for (final ItemStack stack : items) {
            player.getInventory().addItem(stack).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }

        final Locale locale = locales.of(player.getUniqueId());
        player.sendMessage(MessageRenderer.of(messages).format(locale, "smp.headstart.granted",
                "aura", aura));
        sounds.play(player, Feedback.BIG_SUCCESS);
        // The number is on the nametag, in the tab list and on the leaderboard board, and the whole
        // prize is that it is visible. Redrawing everybody is what makes it visible to everybody
        // else in the same second rather than at their next relog.
        surfaces.refreshAll();
    }

    /**
     * The configured items, skipping any this server does not know.
     *
     * <p>A material that does not resolve is a typo in {@code config.yml}, and it must not be able
     * to stop the rest of the head start: the aura is already booked by the time this is read, and
     * refusing here would hand over nothing at all because of one bad line.</p>
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

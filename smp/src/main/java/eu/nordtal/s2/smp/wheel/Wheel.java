package eu.nordtal.s2.smp.wheel;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.player.Identities;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * The wheel of fortune in the tavern: one free spin a day, plus whatever contributing has earned.
 *
 * <p><b>It costs no aura.</b> Aura is recognition, not currency, and the moment it buys something it
 * stops being recognition - that is the single rule this whole feature has to respect.
 *
 * <p>The pool and its weights are {@code config.yml}'s and are meant to be retuned without a
 * release; the arithmetic is {@link PrizeDraw}'s and is tested there. What is here is the spending
 * of a spin, and the guard that makes it happen exactly once lives in SQL rather than in Java: two
 * clicks in the same second both see a free spin, and only the update that changes a row gets a
 * prize.
 */
public final class Wheel {

    private final Plugin plugin;
    private final SmpDao dao;
    private final SmpSpec config;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final Random random = new Random();

    public Wheel(final Plugin plugin, final SmpDao dao, final SmpSpec config,
                 final Identities identities, final Messages messages, final PlayerLocales locales) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
    }

    /** Spins once for a player, if they have a spin. Safe to call from the main thread. */
    public void spin(final Player player) {
        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());
        final Locale locale = locales.of(player.getUniqueId());
        if (discordId.isEmpty()) {
            player.sendMessage(Component.text(messages.get(locale, "smp.error.no-account-link")));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final LocalDate today = LocalDate.now();
            final Spins spins = dao.spinsOf(discordId.get()).orElse(new Spins(0, 0, null));

            // Free first: an earned spin kept is still an earned spin, but a free one not taken
            // today is gone at midnight.
            final boolean took = spins.hasFree(today)
                    ? dao.takeFreeSpin(discordId.get(), today).isPresent()
                    : dao.takeEarnedSpin(discordId.get()).isPresent();

            if (!took) {
                tell(player, messages.format(locale, "smp.wheel.none",
                        "extras", spins.extras()));
                return;
            }
            award(player, locale);
        });
    }

    /** Tells a player what they have without spending anything. */
    public void describe(final Player player) {
        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());
        final Locale locale = locales.of(player.getUniqueId());
        if (discordId.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Spins spins = dao.spinsOf(discordId.get()).orElse(new Spins(0, 0, null));
            tell(player, messages.format(locale, "smp.wheel.available",
                    "count", spins.available(LocalDate.now())));
        });
    }

    private void award(final Player player, final Locale locale) {
        final List<SmpSpec.WheelPrizeSpec> pool = config.wheelPrizes();
        final List<Integer> weights = new ArrayList<>(pool.size());
        pool.forEach(prize -> weights.add(prize.weight()));

        final SmpSpec.WheelPrizeSpec prize = pool.get(PrizeDraw.draw(weights, random));
        final Material material = materialOf(prize.item());
        if (material == null) {
            plugin.getLogger().warning("wheel-prizes names '" + prize.item()
                    + "', which is not a material - the spin was spent and nothing was given");
            tell(player, messages.get(locale, "smp.wheel.broken-prize"));
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            final ItemStack stack = new ItemStack(material, Math.max(1, prize.amount()));
            // Whatever does not fit goes on the floor at their feet rather than vanishing: the
            // wheel is the one channel that pays out real items, and losing one to a full inventory
            // is the kind of thing that is remembered for a season.
            player.getInventory().addItem(stack).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            player.sendMessage(Component.text(messages.format(locale, "smp.wheel.won",
                    "amount", prize.amount(), "item", material.name())));
        });
    }

    private static Material materialOf(final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
    }

    private void tell(final Player player, final String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(Component.text(message));
            }
        });
    }
}

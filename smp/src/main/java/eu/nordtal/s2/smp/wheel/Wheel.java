package eu.nordtal.s2.smp.wheel;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
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
 *
 * <h2>The order the three halves run in</h2>
 * Spending the spin and drawing the prize are decisions and happen off the main thread; showing it
 * is a window and happens on it. {@link WheelGui} is the five seconds in between, added 2026-09-04 -
 * before that a spin was a chat line, which is a lottery ticket read out to you. Nothing in the
 * animation decides anything, and {@link WheelStrip} is where that is argued rather than assumed.
 */
public final class Wheel {

    private final Plugin plugin;
    private final SmpDao dao;
    private final SmpSpec config;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;
    private final Random random = new Random();

    public Wheel(final Plugin plugin, final SmpDao dao, final SmpSpec config,
                 final Identities identities, final Messages messages, final PlayerLocales locales,
                 final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    /** Spins once for a player, if they have a spin. Safe to call from the main thread. */
    public void spin(final Player player) {
        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());
        final Locale locale = locales.of(player.getUniqueId());
        if (discordId.isEmpty()) {
            player.sendMessage(MessageRenderer.of(messages).get(locale, "smp.error.no-account-link"));
            sounds.play(player, Feedback.REFUSED);
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
                tell(player, MessageRenderer.of(messages).format(locale, "smp.wheel.none",
                        "extras", spins.extras()), Feedback.REFUSED);
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
            tell(player, MessageRenderer.of(messages).format(locale, "smp.wheel.available",
                    "count", spins.available(LocalDate.now())));
        });
    }

    private void award(final Player player, final Locale locale) {
        final List<SmpSpec.WheelPrizeSpec> pool = config.wheelPrizes();
        final List<Integer> weights = new ArrayList<>(pool.size());
        pool.forEach(prize -> weights.add(prize.weight()));

        final int index = PrizeDraw.draw(weights, random);
        final SmpSpec.WheelPrizeSpec prize = pool.get(index);
        final Material material = materialOf(prize.item());
        if (material == null) {
            plugin.getLogger().warning("wheel-prizes names '" + prize.item()
                    + "', which is not a material - the spin was spent and nothing was given");
            // LOSS rather than REFUSED: nothing said no to them. The spin was spent, the prize
            // was misconfigured, and what they are actually out is the spin - which is exactly what
            // "something was taken" is for.
            tell(player, MessageRenderer.of(messages).get(locale, "smp.wheel.broken-prize"),
                    Feedback.LOSS);
            return;
        }

        // Everything above is a decision and runs off the main thread; everything below is the
        // window, and has to be on it. The prize is already settled here - the animation shows it
        // arriving, it does not choose it. See WheelStrip.
        final WheelStrip strip = WheelStrip.landingOn(pool.size(), index, random);
        final List<ItemStack> icons = icons(pool);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                // Nobody to show it to and nobody to give it to. The spin is spent; say so where an
                // admin will see it, because the alternative is a silently swallowed prize.
                give(player, material, prize.amount(), locale);
                return;
            }
            new WheelGui(messages, locale, strip, icons, sounds,
                    winner -> give(winner, material, prize.amount(), locale))
                    .start(plugin, player);
        });
    }

    /**
     * Hands over what was won, or says loudly that it could not.
     *
     * <p>Called from exactly one place - {@code WheelGui#finish}, which is a one-shot latch - so a
     * spin pays once however it ended: the wheel running down, the window closed early, or the
     * player logging off mid-spin. That last one is the window the animation introduced and the
     * instant payout did not have, which is why it is logged rather than dropped: the row is spent
     * in the database and nothing else will ever notice.
     */
    private void give(final Player player, final Material material, final int amount,
                      final Locale locale) {
        final int count = Math.max(1, amount);
        if (!player.isOnline()) {
            plugin.getLogger().warning(player.getName() + " left mid-spin; the spin is spent and "
                    + count + "x " + material.name() + " was not handed over");
            return;
        }
        final ItemStack stack = new ItemStack(material, count);
        // Whatever does not fit goes on the floor at their feet rather than vanishing: the
        // wheel is the one channel that pays out real items, and losing one to a full inventory
        // is the kind of thing that is remembered for a season.
        player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.sendMessage(MessageRenderer.of(messages).format(locale, "smp.wheel.won",
                "amount", count, "item", material.translationKey()));
    }

    /**
     * One icon per prize, in pool order, for the strip to travel through.
     *
     * <p>A prize whose material does not resolve gets a barrier rather than stopping the spin: the
     * winner has already been checked, so a broken entry here is one that is only ever passed by -
     * and a wheel that refuses to open because of a prize nobody won would be a worse answer than a
     * visibly wrong icon flying past.
     */
    private static List<ItemStack> icons(final List<SmpSpec.WheelPrizeSpec> pool) {
        final List<ItemStack> out = new ArrayList<>(pool.size());
        for (final SmpSpec.WheelPrizeSpec prize : pool) {
            final Material material = materialOf(prize.item());
            out.add(new ItemStack(material == null ? Material.BARRIER : material,
                    Math.max(1, Math.min(64, prize.amount()))));
        }
        return out;
    }

    private static Material materialOf(final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
    }

    /** Sends one already-rendered message on the main thread, from wherever it is called. */
    private void tell(final Player player, final Component message) {
        tell(player, message, null);
    }

    /**
     * The same, plus a sound.
     *
     * <p>Both in the one hop back to the main thread: the message and its sound belong to the same
     * moment, and scheduling them separately is how they end up a tick apart.
     */
    private void tell(final Player player, final Component message, final Feedback feedback) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(message);
                if (feedback != null) {
                    sounds.play(player, feedback);
                }
            }
        });
    }
}

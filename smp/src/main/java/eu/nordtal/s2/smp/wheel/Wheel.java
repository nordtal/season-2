package eu.nordtal.s2.smp.wheel;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.game.GameKeys;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.config.WheelPrizeSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.db.Spins;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.player.Identities;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The wheel of fortune in the tavern: one free spin a day, plus whatever contributing has earned.
 *
 * A spin is spent in SQL off the main thread, so two clicks yield one prize; {@link WheelGui} only shows it.
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

    private final Clock clock;

    public Wheel(
            final Plugin plugin,
            final SmpDao dao,
            final SmpSpec config,
            final Identities identities,
            final Messages messages,
            final PlayerLocales locales,
            final SmpSounds sounds,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
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
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        final Locale locale = locales.of(player.getUniqueId());
        if (discordId.isEmpty()) {
            player.sendMessage(MessageRenderer.of(messages)
                    .format(locale, MESSAGES.smp().error().noAccountLink()));
            sounds.play(player, Feedback.REFUSED);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final LocalDate today = LocalDate.now(clock);
            final Spins spins = dao.spinsOf(discordId.get()).orElse(new Spins(0, 0, null));

            // Free first: an earned spin kept is still earned, but a free one not taken today is gone at midnight.
            final boolean free = spins.hasFree(today);
            final LocalDate previousFree = spins.lastFree();
            final boolean took = free
                    ? dao.takeFreeSpin(discordId.get(), today).isPresent()
                    : dao.takeEarnedSpin(discordId.get()).isPresent();

            if (!took) {
                // Three keys rather than "spin(s)".
                final int extras = spins.extras();
                final MessageRef none = extras == 0
                        ? MESSAGES.smp().wheel().none()
                        : extras == 1
                                ? MESSAGES.smp().wheel().noneSection().one()
                                : MESSAGES.smp().wheel().noneSection().many(extras);
                tell(player, MessageRenderer.of(messages).format(locale, none), Feedback.REFUSED);
                return;
            }
            // How to undo exactly the row this spin changed; built here since only this call site knows which.
            final String id = discordId.get().value();
            final Runnable refund = free
                    ? () -> offThread(() -> dao.restoreFreeSpin(DiscordId.of(id), previousFree, today))
                    : () -> offThread(() -> dao.restoreEarnedSpin(DiscordId.of(id)));
            // What is left after this spin, read from the row already in hand rather than queried again.
            award(player, locale, refund, Math.max(0, spins.available(today) - 1));
        });
    }

    /** Tells a player what they have without spending anything. */
    public void describe(final Player player) {
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        final Locale locale = locales.of(player.getUniqueId());
        if (discordId.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Spins spins = dao.spinsOf(discordId.get()).orElse(new Spins(0, 0, null));
            final int count = spins.available(LocalDate.now(clock));
            final MessageRef available = count == 0
                    ? MESSAGES.smp().wheel().available()
                    : count == 1
                            ? MESSAGES.smp().wheel().availableSection().one()
                            : MESSAGES.smp().wheel().availableSection().many(count);
            tell(player, MessageRenderer.of(messages).format(locale, available));
        });
    }

    private void award(final Player player, final Locale locale, final Runnable refund, final int spinsLeft) {
        final List<WheelPrizeSpec> pool = config.wheelPrizes();
        final List<Integer> weights = new ArrayList<>(pool.size());
        pool.forEach(prize -> weights.add(prize.weight()));

        final int index = PrizeDraw.draw(weights, random);
        final WheelPrizeSpec prize = pool.get(index);
        final Material material = materialOf(prize.item());
        if (material == null) {
            plugin.getLogger()
                    .warning("wheel-prizes names '" + prize.item()
                            + "', which is not a material - the spin is being put back and nothing was given");
            // The spin goes back: a bad item name in the config group is an operator's typo, not bad luck.
            refund.run();
            tell(
                    player,
                    MessageRenderer.of(messages)
                            .format(locale, MESSAGES.smp().wheel().brokenPrize()),
                    Feedback.LOSS);
            return;
        }

        // Everything above is a decision off the main thread.
        final WheelStrip strip = WheelStrip.landingOn(pool.size(), index, random, WheelPanel.shape());
        final List<ItemStack> icons = icons(pool);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                // Nobody to show it to and nobody to give it to. give() puts the spin back.
                give(player, material, prize.amount(), locale, refund);
                return;
            }
            new WheelGui(
                            messages,
                            locale,
                            strip,
                            icons,
                            sounds,
                            spinsLeft,
                            earnAt(),
                            // Another spin runs spin() again. Null when nothing is left to spend.
                            spinsLeft > 0 ? () -> spin(player) : null,
                            winner -> give(winner, material, prize.amount(), locale, refund))
                    .start(plugin, player);
        });
    }

    /** The lowest contribution share that earns an extra spin, or zero when the list is empty. */
    private int earnAt() {
        return config.wheelExtraSpinPercents().stream()
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .min()
                .orElse(0);
    }

    /**
     * Hands over what was won once, from {@code WheelGui#finish}, running {@code refund} when nothing was handed over.
     */
    private void give(
            final Player player,
            final Material material,
            final int amount,
            final Locale locale,
            final Runnable refund) {
        final int count = Math.max(1, amount);
        if (!player.isOnline()) {
            plugin.getLogger()
                    .warning(player.getName() + " left mid-spin; " + count + "x " + material.name()
                            + " could not be handed over, so the spin goes back");
            refund.run();
            return;
        }
        final ItemStack stack = new ItemStack(material, count);
        // Whatever does not fit goes on the floor rather than vanishing.
        final Location dropAt = Objects.requireNonNull(player.getLocation());
        player.getInventory()
                .addItem(stack)
                .values()
                .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
        player.sendMessage(MessageRenderer.of(messages)
                .format(locale, MESSAGES.smp().wheel().won(count, material.translationKey())));
    }

    /** One icon per prize, in pool order, with a barrier for a material that does not resolve. */
    private static List<ItemStack> icons(final List<WheelPrizeSpec> pool) {
        final List<ItemStack> out = new ArrayList<>(pool.size());
        for (final WheelPrizeSpec prize : pool) {
            final Material material = materialOf(prize.item());
            out.add(new ItemStack(
                    material == null ? Material.BARRIER : material, Math.max(1, Math.min(64, prize.amount()))));
        }
        return out;
    }

    private static @Nullable Material materialOf(final @Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return GameKeys.material(name).orElse(null);
    }

    /** Runs database work off the main thread from anywhere; a refund lost to a shutdown is logged, not thrown. */
    private void offThread(final Runnable work) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, work);
        } catch (final IllegalStateException | IllegalArgumentException refused) {
            plugin.getLogger()
                    .warning("could not put a wheel spin back - the server is shutting" + " down: "
                            + refused.getMessage());
        }
    }

    /** Sends one already-rendered message on the main thread, from wherever it is called. */
    private void tell(final Player player, final Component message) {
        tell(player, message, null);
    }

    /** The same, plus a sound, in one hop so the two land in the same tick. */
    private void tell(final Player player, final Component message, final @Nullable Feedback feedback) {
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

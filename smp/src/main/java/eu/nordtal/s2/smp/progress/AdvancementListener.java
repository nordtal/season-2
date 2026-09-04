package eu.nordtal.s2.smp.progress;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.player.Identities;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Advancements, which do two separate things in this design and are easy to confuse.
 *
 * <ul>
 *   <li><b>An {@code ADVANCEMENT} objective</b> counts how many distinct players have earned a given
 *       advancement. Each player contributes 1, once.</li>
 *   <li><b>The advancement awards</b> in {@code config.yml} pay a player 2-10 aura for a curated
 *       list of achievements, and have nothing to do with the track.</li>
 * </ul>
 *
 * <p>One event feeds both, which is why they are in one listener - and why the two are spelled out
 * above, because a reader who conflates them will eventually make one pay twice.
 */
public final class AdvancementListener implements Listener {

    private final Plugin plugin;
    private final SmpDao dao;
    private final ObjectiveEngine engine;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;

    /** The curated award list, flattened once at construction rather than scanned per advancement. */
    private final Map<String, Integer> awards = new HashMap<>();

    public AdvancementListener(final Plugin plugin, final SmpDao dao, final ObjectiveEngine engine,
                               final Identities identities, final SmpSpec config,
                               final Messages messages, final PlayerLocales locales,
                               final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.engine = engine;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
        for (final SmpSpec.AdvancementAwardSpec award : config.advancementAwards()) {
            awards.put(award.advancement().toLowerCase(Locale.ROOT), award.aura());
        }
    }

    @EventHandler
    public void onAdvancement(final PlayerAdvancementDoneEvent event) {
        final Player player = event.getPlayer();
        final String key = event.getAdvancement().getKey().toString().toLowerCase(Locale.ROOT);

        // Recipes are advancements too, and there are hundreds of them. Nobody is being paid for
        // unlocking the recipe for a wooden pickaxe.
        if (key.contains("/recipes/")) {
            return;
        }

        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            return;
        }

        final Integer award = awards.get(key);
        final String objectiveKey = key;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (award != null && award > 0) {
                dao.addAura(discordId.get(), award, AuraReason.ADVANCEMENT.stored(), key);
                final Locale locale = locales.of(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(MessageRenderer.of(messages).format(locale,
                                "smp.aura.advancement", "aura", award));
                        sounds.play(player, Feedback.SMALL_SUCCESS);
                    }
                });
            }
            // An ADVANCEMENT objective is keyed by the advancement it wants, so the key is the
            // lookup. One player, one credit - the objective's target is a headcount.
            engine.credit(discordId.get(), objectiveKey, 1L, player.getUniqueId());
        });
    }
}

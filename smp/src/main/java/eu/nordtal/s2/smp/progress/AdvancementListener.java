package eu.nordtal.s2.smp.progress;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.config.AdvancementAwardSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;

/**
 * Advancements, which feed {@code ADVANCEMENT} objectives and, separately, the curated aura awards.
 *
 * An objective counts each player once; an award pays 2 to 10 aura and has nothing to do with the track.
 */
public final class AdvancementListener implements Listener {

    private final Plugin plugin;
    private final SmpDao dao;
    private final ObjectiveEngine engine;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;

    /** The curated award list, flattened once at construction. */
    private final Map<String, Integer> awards = new HashMap<>();

    public AdvancementListener(
            final Plugin plugin,
            final SmpDao dao,
            final ObjectiveEngine engine,
            final Identities identities,
            final SmpSpec config,
            final MessageRenderer renderer,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.engine = engine;
        this.identities = identities;
        this.renderer = renderer;
        this.sounds = sounds;
        for (final AdvancementAwardSpec award : config.advancementAwards()) {
            awards.put(award.advancement().toLowerCase(Locale.ROOT), award.aura());
        }
    }

    @EventHandler
    public void onAdvancement(final PlayerAdvancementDoneEvent event) {
        final Player player = event.getPlayer();
        final String key = event.getAdvancement().getKey().toString().toLowerCase(Locale.ROOT);

        // Recipes are advancements too, and there are hundreds; nobody gets paid for unlocking a wooden pickaxe recipe.
        if (key.contains("/recipes/")) {
            return;
        }

        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            return;
        }

        final Integer award = awards.get(key);
        final NamespacedKey advancement = event.getAdvancement().getKey();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (award != null && award > 0) {
                dao.addAura(discordId.get(), award, AuraReason.ADVANCEMENT.stored(), key);
                final Locale locale = identities.languageOf(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(
                                renderer.format(locale, MESSAGES.smp().aura().advancement(award)));
                        sounds.play(player, Feedback.SMALL_SUCCESS);
                    }
                });
            }
            engine.creditAdvancement(discordId.get(), advancement, player.getUniqueId());
        });
    }
}

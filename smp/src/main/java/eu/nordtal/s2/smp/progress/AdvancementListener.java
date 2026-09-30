package eu.nordtal.s2.smp.progress;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.config.AdvancementAwardSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.player.Identities;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
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
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;

    /** The curated award list, flattened once at construction. */
    private final Map<String, Integer> awards = new HashMap<>();

    public AdvancementListener(
            final Plugin plugin,
            final SmpDao dao,
            final ObjectiveEngine engine,
            final Identities identities,
            final SmpSpec config,
            final Messages messages,
            final PlayerLocales locales,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.engine = engine;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
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
        final String objectiveKey = key;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (award != null && award > 0) {
                dao.addAura(discordId.get(), award, AuraReason.ADVANCEMENT.stored(), key);
                final Locale locale = locales.of(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(MessageRenderer.of(messages)
                                .format(locale, MESSAGES.smp().aura().advancement(award)));
                        sounds.play(player, Feedback.SMALL_SUCCESS);
                    }
                });
            }
            // An ADVANCEMENT objective is keyed by the advancement it wants.
            engine.credit(discordId.get(), objectiveKey, 1L, player.getUniqueId());
        });
    }
}

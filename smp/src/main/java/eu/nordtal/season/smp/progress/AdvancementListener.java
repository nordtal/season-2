package eu.nordtal.season.smp.progress;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.aura.AuraCause;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.config.AdvancementAwardSpec;
import eu.nordtal.season.smp.config.AdvancementAwardsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;

/**
 * Credits {@code ADVANCEMENT} objectives and pays the aura of the configured advancement awards.
 * It ignores advancements whose key contains {@code /recipes/}.
 */
public final class AdvancementListener implements Listener {

    private final Plugin plugin;
    private final AuraDao aura;
    private final ObjectiveEngine engine;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;

    private final AdvancementAwardsSpec config;

    public AdvancementListener(
            final Plugin plugin,
            final AuraDao aura,
            final ObjectiveEngine engine,
            final Identities identities,
            final AdvancementAwardsSpec config,
            final MessageRenderer renderer,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.aura = aura;
        this.engine = engine;
        this.identities = identities;
        this.renderer = renderer;
        this.sounds = sounds;
        this.config = config;
    }

    @EventHandler
    public void onAdvancement(final PlayerAdvancementDoneEvent event) {
        final Player player = event.getPlayer();
        final String key = event.getAdvancement().getKey().toString().toLowerCase(Locale.ROOT);

        if (key.contains("/recipes/")) {
            return;
        }

        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            return;
        }

        final Integer award = config.advancementAwards().stream()
                .filter(entry -> entry.advancement().toLowerCase(Locale.ROOT).equals(key))
                .findFirst()
                .map(AdvancementAwardSpec::aura)
                .orElse(null);
        final NamespacedKey advancement = event.getAdvancement().getKey();

        PaperScheduler.of(plugin).execute(() -> {
            if (award != null && award > 0) {
                aura.addAura(discordId.get(), award, new AuraCause.Advancement(key));
                final Locale locale = identities.languageOf(player.getUniqueId());
                PaperScheduler.of(plugin).onMain(() -> {
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

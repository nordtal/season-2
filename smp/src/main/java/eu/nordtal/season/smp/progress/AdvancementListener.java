package eu.nordtal.season.smp.progress;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.aura.AuraCause;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.config.AdvancementAwardSpec;
import eu.nordtal.season.smp.config.AdvancementAwardsSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.port.PrizeSource;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;
import org.jdbi.v3.core.Jdbi;

/**
 * Credits {@code ADVANCEMENT} objectives and pays the aura and spins of the configured advancement awards.
 * It ignores advancements whose key contains {@code /recipes/}.
 */
public final class AdvancementListener implements Listener {

    record Award(@Example("50") int aura, @Example("1") int spins) {}

    static final DocumentSpec<Award> AWARDED = DocumentSpec.game(
                    "smp", "aura-advancement", "Aura", DocumentSpec.Shape.CHAT, Award.class)
            .titled("Aura for an advancement");

    private final Plugin plugin;
    private final Jdbi jdbi;
    private final AuraDao aura;
    private final PrizeSource spins;
    private final ObjectiveEngine engine;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;

    private final AdvancementAwardsSpec config;

    /** @param jdbi the {@code Jdbi} whose transaction the aura and the spins of one award share */
    public AdvancementListener(
            final Plugin plugin,
            final Jdbi jdbi,
            final AuraDao aura,
            final PrizeSource spins,
            final ObjectiveEngine engine,
            final Identities identities,
            final AdvancementAwardsSpec config,
            final MessageRenderer renderer,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.jdbi = jdbi;
        this.aura = aura;
        this.spins = spins;
        this.engine = engine;
        this.identities = identities;
        this.renderer = renderer;
        this.sounds = sounds;
        this.config = config;
    }

    /** The award of the advancement {@code key}, matched without case, or none when it pays nothing. */
    static Optional<Award> awardOf(final List<AdvancementAwardSpec> awards, final String key) {
        return awards.stream()
                .filter(entry -> entry.advancement().toLowerCase(Locale.ROOT).equals(key))
                .findFirst()
                .map(entry -> new Award(Math.max(0, entry.aura()), Math.max(0, entry.spins())))
                .filter(award -> award.aura() > 0 || award.spins() > 0);
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

        final Optional<Award> award = awardOf(config.advancementAwards(), key);
        final NamespacedKey advancement = event.getAdvancement().getKey();

        PaperScheduler.of(plugin).execute(() -> {
            award.ifPresent(paid -> {
                jdbi.useTransaction(handle -> {
                    if (paid.aura() > 0) {
                        aura.addAura(discordId.get(), paid.aura(), new AuraCause.Advancement(key));
                    }
                    if (paid.spins() > 0) {
                        spins.grant(discordId.get(), paid.spins());
                    }
                });
                final Locale locale = identities.languageOf(player.getUniqueId());
                PaperScheduler.of(plugin).onMain(() -> {
                    if (player.isOnline()) {
                        player.sendMessage(renderer.format(locale, AWARDED.with(paid)));
                        sounds.play(player, Feedback.SMALL_SUCCESS);
                    }
                });
            });
            engine.creditAdvancement(discordId.get(), advancement, player.getUniqueId());
        });
    }
}

package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /aura}: where you stand, and the ten highest.
 *
 * <h2>Chat and not a menu (Till, 2026-09-08)</h2>
 * The aura board already exists in the world and is the place to look at the leaderboard properly.
 * What this answers is the question somebody has while they are doing something else - "where am I
 * now" - and a menu takes the screen away to answer it. It is also the one form that works from any
 * of the three servers, since the command travels to the SMP through a request row when it is typed
 * anywhere else.
 *
 * <h2>Why the read is one call and not three</h2>
 * Rank, total and the top ten come back together, so they describe one instant. Read separately,
 * somebody's own line could disagree with the line about them in the list below it - which is the
 * kind of inconsistency nobody reports as a bug and everybody notices.
 *
 * <h2>The colours are tones, not markup</h2>
 * These keys live in {@code :commands}' shared bundle, which carries no markup at all, so the
 * asker's own line is told apart from the rest by {@link Tone} rather than by a second key with the
 * same words in a different colour. Discord ignores the tone and reads the same sentences.
 */
public final class ShowAura implements NordtalCommand<SmpEffects> {

    @Override
    public Declaration declaration() {
        return SmpCommands.OWN_AURA;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final SmpEffects effects) {
        final Optional<UUID> self = user.minecraftUuid();
        if (self.isEmpty()) {
            // The console, and only the console: this command is declared on GAME alone, so the
            // adapter has already refused one. Kept as a belt: a NordtalUser with no Minecraft
            // account is a shape this module is written to expect everywhere else too.
            user.reply("smp.aura.nobody", Map.of(), Feedback.REFUSED);
            return;
        }
        effects.async(() -> {
            final Optional<SmpEffects.AuraStanding> standing;
            try {
                standing = effects.auraStanding(self.get());
            } catch (final RuntimeException failure) {
                effects.warn("/aura failed", failure);
                user.reply("smp.aura.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            if (standing.isEmpty()) {
                user.reply("smp.aura.unlinked", Map.of(), Feedback.REFUSED);
                return;
            }
            final SmpEffects.AuraStanding shown = standing.get();
            user.reply("smp.aura.own", Map.of(
                    "aura", shown.aura(), "rank", shown.rank(), "total", shown.total()), Tone.GOOD);
            if (shown.top().isEmpty()) {
                user.reply("smp.aura.empty", Map.of(), Tone.MUTED);
                return;
            }
            user.reply("smp.aura.top", Map.of("count", shown.top().size()), Tone.NEUTRAL);
            for (final SmpEffects.AuraLine line : shown.top()) {
                // One key, two tones. A second key with the same words in a different colour is two
                // strings to translate and one of them eventually says something else.
                user.reply("smp.aura.line", Map.of(
                                "place", line.place(), "player", line.player(), "aura", line.aura()),
                        line.you() ? Tone.GOOD : Tone.MUTED);
            }
        });
    }
}

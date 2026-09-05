package eu.nordtal.s2.commands.hungergames;

import eu.nordtal.s2.commands.Confirmations;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;
import java.util.Optional;

/**
 * {@code /hg start} - the one command that decides the whole event.
 *
 * <h2>Two minimums, and only one of them can be argued with</h2>
 * Below the <b>hard</b> minimum the command refuses outright: the border step divides by the
 * participant count, so a game below it is not a bad game but a crash. Below the <b>soft</b> one it
 * warns, names the numbers, and waits to be told again - which is the confirmation this command
 * keeps instead of the catalogue's generic one, because "only 4 of the recommended 8 are ready" is
 * worth more than "this cannot be undone".
 *
 * <h2>{@code consume}, never {@code confirm}, on the second step</h2>
 * {@link Confirmations#confirm} arms on a miss, which is right when the confirmation is the same
 * command typed again and wrong here: a bare {@code /hg start confirm} typed twice would arm itself
 * and go through on the second attempt, having never shown the warning this whole branch exists for.
 */
public final class StartGame implements NordtalCommand<HungerGamesEffects> {

    /**
     * Keyed on the person <em>and</em> on {@link #KEY} - both, which is what
     * {@link Confirmations#key} builds - so a warning shown to one admin cannot be spent by another,
     * and so the two halves of one flow see one entry rather than two.
     */
    private final Confirmations confirmations = new Confirmations();

    @Override
    public Declaration declaration() {
        return HungerGamesCommands.START;
    }

    @Override
    public void run(final NordtalUser user, final Values values,
                    final HungerGamesEffects effects) {
        // The optional trailing word IS the second step. `/hg start confirm` in chat, a dropdown
        // with one value in Discord - one command either way, which is what stops the two halves
        // from having two confirmation maps between them.
        attempt(user, effects, values.optionalString("confirm").isPresent());
    }

    private void attempt(final NordtalUser user, final HungerGamesEffects effects,
                         final boolean isConfirmation) {
        effects.async(() -> {
            final Optional<HungerGamesEffects.Registration> registration;
            try {
                registration = effects.registration();
            } catch (final RuntimeException failure) {
                effects.warn("/hg start could not read the registration", failure);
                user.reply("hg.start.read-failed", Map.of(), Feedback.REFUSED);
                return;
            }

            if (registration.isEmpty()) {
                user.reply("hg.start.no-game", Map.of(), Feedback.REFUSED);
                return;
            }
            final HungerGamesEffects.Registration game = registration.get();
            if (!"REGISTRATION".equals(game.state())) {
                user.reply("hg.start.wrong-state", Map.of("state", game.state()), Feedback.REFUSED);
                return;
            }
            if (game.participants() < HungerGamesCommands.HARD_MINIMUM_PARTICIPANTS) {
                user.reply("hg.start.below-hard-minimum",
                        Map.of("minimum", HungerGamesCommands.HARD_MINIMUM_PARTICIPANTS,
                                "count", game.participants()),
                        Feedback.REFUSED);
                return;
            }

            // Both halves ask the same question, and the order matters: an admin who always types
            // the second step must not be refused on a game that never needed one. Until 2026-09-05
            // `/hg start confirm` on a healthy registration answered "that confirmation expired" -
            // nothing had expired, nothing had ever been armed, and the game did not start.
            final boolean needsConfirming =
                    game.participants() < effects.softMinimumParticipants();
            if (isConfirmation && needsConfirming) {
                if (!confirmations.consume(user, KEY)) {
                    user.reply("hg.start.confirm-expired", Map.of(), Feedback.REFUSED);
                    return;
                }
            } else if (needsConfirming) {
                confirmations.arm(user, KEY);
                // REFUSED rather than nothing: the command did not do what was asked, and this is
                // the one place an admin about to start the season's flagship event should stop and
                // read rather than type the next thing.
                user.reply("hg.start.below-soft-minimum",
                        Map.of("count", game.participants(),
                                "minimum", effects.softMinimumParticipants(),
                                "seconds", Confirmations.WINDOW.toSeconds()),
                        Feedback.REFUSED);
                return;
            } else {
                // A start that needed no confirmation clears any stale one, so a warning from a
                // minute ago cannot be spent on a later game.
                confirmations.forget(user, KEY);
            }

            // SMALL_SUCCESS, and this is a deliberate departure from smp, where an admin's
            // confirmation of their own command is silent. Two things make it different: it is
            // irreversible, and an admin who is not themselves a participant hears NOTHING else
            // during the entire start - the TRAVEL, the countdown and the release all go to
            // participants only.
            effects.recordStart(user, game, isConfirmation);
            try {
                effects.start(game.gameId());
            } catch (final RuntimeException failure) {
                effects.warn("/hg start could not start " + game.gameId(), failure);
                user.reply("hg.start.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            // Said afterwards, and that ordering is the whole point: the reply used to go out first,
            // so a start that threw told the admin the event had begun.
            user.reply("hg.start.started", Map.of("count", game.participants()),
                    Feedback.SMALL_SUCCESS);
        });
    }

    /** What the confirmation is keyed on - the command, not the exact line somebody typed. */
    private static final String KEY = "/hg start";

}

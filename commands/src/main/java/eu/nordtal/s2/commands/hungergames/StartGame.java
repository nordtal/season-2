package eu.nordtal.s2.commands.hungergames;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Confirmations;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import java.util.Optional;

/**
 * {@code /hg start} - the one command that decides the whole event.
 *
 * Two minimums, and only one of them can be argued with. Below the <b>hard</b> minimum the command
 * refuses outright: the border step divides by the participant count, so a game below it is not a
 * bad game but a crash. Below the <b>soft</b> one it warns, names the numbers, and waits to be told
 * again - which is the confirmation this command keeps instead of the catalogue's generic one,
 * because "only 4 of the recommended 8 are ready" is worth more than "this cannot be undone".
 *
 * {@code consume}, never {@code confirm}, on the second step:
 * {@link Confirmations#confirm} arms on a miss, which is right when the confirmation is the same
 * command typed again and wrong here: a bare {@code /hg start confirm} typed twice would arm itself
 * and go through on the second attempt, having never shown the warning this whole branch exists for.
 */
public final class StartGame implements NordtalCommand<HungerGamesEffects> {

    /**
     * Keyed on the person <em>and</em> on {@link #KEY}, matching what {@link Confirmations#key} builds.
     *
     * So a warning shown to one admin cannot be spent by another, and the two halves of one flow
     * see one entry rather than two.
     */
    private final Confirmations confirmations = new Confirmations();

    @Override
    public Declaration declaration() {
        return HungerGamesCommands.START;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final HungerGamesEffects effects) {
        // The optional trailing word IS the second step. `/hg start confirm` in chat.
        attempt(user, effects, values.optionalString("confirm").isPresent());
    }

    private void attempt(final NordtalUser user, final HungerGamesEffects effects, final boolean isConfirmation) {
        effects.async(() -> attemptOnEffectsThread(user, effects, isConfirmation));
    }

    private void attemptOnEffectsThread(
            final NordtalUser user, final HungerGamesEffects effects, final boolean isConfirmation) {
        final Optional<HungerGamesEffects.Registration> registration = readRegistration(user, effects);
        if (registration.isEmpty()) {
            return;
        }
        final HungerGamesEffects.Registration game = registration.get();
        if (!"REGISTRATION".equals(game.state())) {
            user.reply(MESSAGES.hg().start().wrongState(game.state()), Feedback.REFUSED, Tone.WARN);
            return;
        }
        if (game.participants() < HungerGamesCommands.HARD_MINIMUM_PARTICIPANTS) {
            user.reply(
                    MESSAGES.hg()
                            .start()
                            .belowHardMinimum(HungerGamesCommands.HARD_MINIMUM_PARTICIPANTS, game.participants()),
                    Feedback.REFUSED,
                    Tone.BAD);
            return;
        }
        if (!confirmMinimum(user, effects, game, isConfirmation)) {
            return;
        }
        startGame(user, effects, game, isConfirmation);
    }

    private Optional<HungerGamesEffects.Registration> readRegistration(
            final NordtalUser user, final HungerGamesEffects effects) {
        final Optional<HungerGamesEffects.Registration> registration;
        try {
            registration = effects.registration();
        } catch (final RuntimeException failure) {
            effects.warn("/hg start could not read the registration", failure);
            user.reply(MESSAGES.hg().start().readFailed(), Feedback.REFUSED, Tone.BAD);
            return Optional.empty();
        }
        if (registration.isEmpty()) {
            user.reply(MESSAGES.hg().start().noGame(), Feedback.REFUSED, Tone.WARN);
        }
        return registration;
    }

    private boolean confirmMinimum(
            final NordtalUser user,
            final HungerGamesEffects effects,
            final HungerGamesEffects.Registration game,
            final boolean isConfirmation) {
        // Both halves ask the same question, and the order matters: an admin who always types the second step must.
        final boolean needsConfirming = game.participants() < effects.softMinimumParticipants();
        if (isConfirmation && needsConfirming) {
            if (!confirmations.consume(user, KEY)) {
                user.reply(MESSAGES.hg().start().confirmExpired(), Feedback.REFUSED, Tone.WARN);
                return false;
            }
        } else if (needsConfirming) {
            confirmations.arm(user, KEY);
            // REFUSED rather than nothing: the command did not do what was asked.
            user.reply(
                    MESSAGES.hg()
                            .start()
                            .belowSoftMinimum(
                                    game.participants(),
                                    effects.softMinimumParticipants(),
                                    Confirmations.WINDOW.toSeconds()),
                    // The confirmation question, which is neither a refusal nor a success.
                    Feedback.REFUSED,
                    Tone.WARN);
            return false;
        } else {
            // A start that needed no confirmation clears any stale one.
            confirmations.forget(user, KEY);
        }
        return true;
    }

    private void startGame(
            final NordtalUser user,
            final HungerGamesEffects effects,
            final HungerGamesEffects.Registration game,
            final boolean isConfirmation) {
        // SMALL_SUCCESS, and this is a deliberate departure from smp.
        effects.recordStart(user, game, isConfirmation);
        try {
            effects.start(game.gameId());
        } catch (final RuntimeException failure) {
            effects.warn("/hg start could not start " + game.gameId(), failure);
            user.reply(MESSAGES.hg().start().failed(), Feedback.REFUSED, Tone.BAD);
            return;
        }
        // Said afterwards, and that ordering is the whole point.
        user.reply(MESSAGES.hg().start().started(game.participants()), Feedback.SMALL_SUCCESS, Tone.GOOD);
    }

    /** What the confirmation is keyed on - the command, not the exact line somebody typed. */
    private static final String KEY = "/hg start";
}

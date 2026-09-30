package eu.nordtal.s2.commands.hungergames;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Confirmations;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.feedback.Feedback;
import java.util.Optional;

/**
 * {@code /hg start}: refuses below the hard minimum and asks to be told again below the soft one.
 *
 * The second step uses {@code consume}, never {@code confirm}, which arms on a miss and would skip the warning.
 */
public final class StartGame implements NordtalCommand<HungerGamesEffects> {

    /** Keyed on the person and {@link #KEY}, so one admin's warning cannot be spent by another. */
    private final Confirmations confirmations = new Confirmations();

    @Override
    public Declaration declaration() {
        return HungerGamesCommands.START;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final HungerGamesEffects effects) {
        // The optional trailing word is the second step: `/hg start confirm` in chat.
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
        // Both halves re-check the minimum, so typing the second step straight away never skips the warning.
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
        effects.recordStart(user, game, isConfirmation);
        try {
            effects.start(game.gameId());
        } catch (final RuntimeException failure) {
            effects.warn("/hg start could not start " + game.gameId(), failure);
            user.reply(MESSAGES.hg().start().failed(), Feedback.REFUSED, Tone.BAD);
            return;
        }
        // Said only once the start went through.
        user.reply(MESSAGES.hg().start().started(game.participants()), Feedback.SMALL_SUCCESS, Tone.GOOD);
    }

    /** The confirmation is keyed on the command, not the exact line somebody typed. */
    private static final String KEY = "/hg start";
}

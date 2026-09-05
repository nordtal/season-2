package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;

import java.util.List;
import java.util.Set;

/**
 * {@code /access} - what the bot does that nothing else can, now reachable from in game as well.
 *
 * <h2>Grouped, and renamed while it was being folded</h2>
 * They were {@code /grant-access}, {@code /revoke-access}, {@code /access-status} and
 * {@code /settle} - four unrelated top-level commands. Grouping them under one root is what
 * {@link Declaration}'s own rule asks for ("grouped by target rather than flattened, so that
 * somebody who knows one surface knows the other"), and it is what makes them typeable in chat at
 * all: {@code /grant-access} reads as a Discord command and {@code /access grant} reads as a command.
 *
 * <p>The rename is a real cost - an admin who knows the old names has to learn four new ones - and
 * it is paid once, before the season opens, rather than being avoided for ever.</p>
 *
 * <h2>{@code /unlink} is not here, and its admin twin is</h2>
 * The bot's {@code /unlink} is <b>self-service</b>: it takes no arguments, is visible to everybody,
 * and removes the caller's own link. That is not an admin command and does not belong in a catalogue
 * where everything is. {@code /access unlink <member>} is the admin version - somebody else's link,
 * for the case where a player has lost the account they linked - and the two stay separate because
 * folding them would either give every member the ability to unlink other people or take away a
 * self-service action that deliberately has no waiting period.
 *
 * <h2>Four of the six ask first</h2>
 * Granting, revoking and settling all move money or paid time and none of them has a clean undo:
 * revoking does not restore the days it took, and settling books a payment against a reference that
 * cannot be unbooked. Unlinking is the fourth and is guarded for a different reason - it is the one
 * that cannot be repaired by the admin who did it, because re-linking needs a code the
 * <em>player</em> generates. The two that ask nothing are {@code /access status}, which reads, and
 * {@code /access reload}, which re-reads a file.
 */
public final class AccessCommands {

    private AccessCommands() {
    }

    private static final Set<Surface> EVERYWHERE =
            Set.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE);

    // Every one of these takes an ACCOUNT and not a PLAYER, and the difference is the whole reason
    // the two kinds exist. Their subject is a Discord account: /access grant is exactly what an
    // admin runs for somebody whose payment arrived outside the normal flow, and a PLAYER argument
    // resolves through account_link on both surfaces - so it would have made the command unusable
    // on the person it exists for. Written as PLAYER for half an afternoon; that is what it cost.

    /** {@code /access status <member>} - the full picture: access, donor, language, grants, purchases. */
    public static final Declaration STATUS = new Declaration(
            List.of("access", "status"), Target.BOT, EVERYWHERE, true, false,
            List.of(Argument.account("member")));

    /** {@code /access grant <member> <days>} - days on top of whatever is already running. */
    public static final Declaration GRANT = new Declaration(
            List.of("access", "grant"), Target.BOT, EVERYWHERE, true, true,
            // Bounded, which the Discord command was not: it hand-checked "greater than zero" in the
            // handler and had no upper bound at all, so a mistyped 3650 was a decade of free access
            // and one keystroke away from 365.
            List.of(Argument.account("member"), Argument.integer("days", 1, 365)));

    /** {@code /access revoke <member>} - every running grant, at once. */
    public static final Declaration REVOKE = new Declaration(
            List.of("access", "revoke"), Target.BOT, EVERYWHERE, true, true,
            List.of(Argument.account("member")));

    /**
     * {@code /access unlink <member>} - break somebody else's account link.
     *
     * <p>The admin twin of the bot's self-service {@code /unlink}, for a player who has lost the
     * Discord or Minecraft account they linked. Guarded because the admin who does it cannot undo
     * it: re-linking needs a code the <em>player</em> generates in game.</p>
     */
    public static final Declaration UNLINK = new Declaration(
            List.of("access", "unlink"), Target.BOT, EVERYWHERE, true, true,
            List.of(Argument.account("member")));

    /**
     * {@code /access settle <reference>} - book a payment by hand.
     *
     * <p>The reference is suggested from the open requests on both surfaces, which is what makes it
     * typeable in chat at all: without the list it is a six-character string recalled from memory,
     * on the one command that books money.</p>
     */
    public static final Declaration SETTLE = new Declaration(
            List.of("access", "settle"), Target.BOT, EVERYWHERE, true, true,
            List.of(Argument.word("reference")));

    /**
     * {@code /access reload} - the bot's own wording.
     *
     * <p>Under the {@code access} root and not a {@code /messages} one of its own, which is what it
     * was for a day. Every other reload in the network is grouped under the root it belongs to -
     * {@code /smp reload}, {@code /hg reload}, {@code /limbo reload}, {@code /network reload} - and
     * this command is registered on three Paper servers, where a top-level {@code /messages} is a
     * generic enough name to collide with something else's.</p>
     */
    public static final Declaration RELOAD_MESSAGES = new Declaration(
            List.of("access", "reload"), Target.BOT, EVERYWHERE, true, false, List.of());

    /** Every {@code /access} command, plus the bot's own reload. */
    public static List<NordtalCommand<AccessEffects>> all() {
        return List.of(new ShowStatus(), new GrantAccess(), new RevokeAccess(), new UnlinkAccount(),
                new SettlePayment(), new ReloadBotMessages());
    }

    /** Every declaration here. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

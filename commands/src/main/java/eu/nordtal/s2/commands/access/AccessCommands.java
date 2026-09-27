package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.util.List;
import java.util.Set;

/**
 * {@code /access}: the bot's access commands, reachable from the console and Steward.
 *
 * Granting, revoking, settling and unlinking ask first, since none has a clean undo.
 */
public final class AccessCommands {

    private AccessCommands() {}

    /** Console only: a Discord command is guarded by the admin flag alone, while Steward asks for a security key. */
    private static final Set<Surface> CONSOLE_ONLY = Set.of(Surface.CONSOLE);

    /** The two Steward also runs as commands: console, plus the interface. */
    private static final Set<Surface> CONSOLE_AND_WEB = Set.of(Surface.CONSOLE, Surface.WEB);

    // ACCOUNT, not PLAYER: the subject is a Discord account, and PLAYER resolves through account_link.

    /** {@code /access status <member>}: access, donor, language, grants, purchases. */
    public static final Declaration STATUS = new Declaration(
            List.of("access", "status"), Target.BOT, CONSOLE_ONLY, true, false, List.of(Argument.account("member")));

    /** {@code /access grant <member> <days>}: days on top of whatever is already running. */
    public static final Declaration GRANT = new Declaration(
            List.of("access", "grant"),
            Target.BOT,
            CONSOLE_ONLY,
            true,
            true,
            // Bounded, so a mistyped extra digit does not turn 365 into a decade.
            List.of(Argument.account("member"), Argument.integer("days", 1, 365)));

    /** {@code /access revoke <member>}: every running grant, at once. */
    public static final Declaration REVOKE = new Declaration(
            List.of("access", "revoke"), Target.BOT, CONSOLE_ONLY, true, true, List.of(Argument.account("member")));

    /** {@code /access unlink <member>}: breaks somebody else's link, which only the player can redo. */
    public static final Declaration UNLINK = new Declaration(
            List.of("access", "unlink"), Target.BOT, CONSOLE_AND_WEB, true, true, List.of(Argument.account("member")));

    /** {@code /access settle <reference>}: books a payment by hand, with the reference offered from a list. */
    public static final Declaration SETTLE = new Declaration(
            List.of("access", "settle"),
            Target.BOT,
            CONSOLE_AND_WEB,
            true,
            true,
            List.of(Argument.reference("reference")));

    /** {@code /access reload}: the bot's own wording, under its root like every other reload. */
    public static final Declaration RELOAD_MESSAGES =
            new Declaration(List.of("access", "reload"), Target.BOT, CONSOLE_ONLY, true, false, List.of());

    /** Every {@code /access} command, plus the bot's own reload. */
    public static List<NordtalCommand<AccessEffects>> all() {
        return List.of(
                new ShowStatus(),
                new GrantAccess(),
                new RevokeAccess(),
                new UnlinkAccount(),
                new SettlePayment(),
                new ReloadBotMessages());
    }

    /** Every declaration here. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

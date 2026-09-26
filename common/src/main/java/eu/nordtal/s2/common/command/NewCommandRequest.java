package eu.nordtal.s2.common.command;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A command about to be sent to the process that owns it.
 *
 * <b>Everything the target needs, and nothing it could look up</b>
 *
 * The identities, the language and the arguments all ride on the row. That is deliberate for the
 * language in particular: the answer has to come back in the language of whoever typed the command,
 * even if their {@code discord_user.locale} is changed while the row is in flight. Looking it up on
 * the far side would make the reply's language depend on when it was claimed.
 *
 * What does <b>not</b> ride on the row is the admin flag. The target re-reads it after claiming,
 * because the whole point of checking twice is that it can change in between - a request written by
 * an admin who is revoked a second later must not carry its own permission with it.
 *
 * @param target       which process runs the effect, as {@code Target#name()}
 * @param command      the command path joined with spaces, no leading slash: {@code "smp aura"}
 * @param arguments    the arguments as a line, empty for a command that takes none
 * @param source       {@code DISCORD}, {@code GAME}, {@code CONSOLE} or {@code WEB}
 * @param requestedBy  who asked, for people to read
 * @param discordId    their Discord id, absent for the console
 * @param minecraftId  their Minecraft UUID, absent for the console and for an unlinked member
 * @param locale       the language tag the answer is rendered in
 * @param expires      when the asker stops waiting, absolute
 */
public record NewCommandRequest(
        String target,
        String command,
        String arguments,
        String source,
        String requestedBy,
        Optional<String> discordId,
        Optional<UUID> minecraftId,
        String locale,
        Instant expires) {

    public NewCommandRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(discordId, "discordId");
        Objects.requireNonNull(minecraftId, "minecraftId");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(expires, "expires");

        if (command.isBlank()) {
            throw new IllegalArgumentException("a command request needs a command");
        }
        if (command.startsWith("/")) {
            throw new IllegalArgumentException("the command is the path without its slash, got: " + command);
        }
        // Source first: both identity rules read it, and an unknown source would pass them by matching neither.
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("a command request comes from " + SOURCES + ", got: " + source);
        }
        if ("CONSOLE".equals(source) && (discordId.isPresent() || minecraftId.isPresent())) {
            throw new IllegalArgumentException("a console request carries no identity, got discordId=" + discordId
                    + " minecraftId=" + minecraftId);
        }
        if (("DISCORD".equals(source) || "WEB".equals(source)) && discordId.isEmpty()) {
            throw new IllegalArgumentException(
                    "a request from " + source + " always knows the asker's id - without one the"
                            + " target cannot re-check the admin flag after it claims the row");
        }
    }

    /**
     * The sources {@code command_request_source_check} allows.
     *
     * {@code SYSTEM} is a {@code Surface}, not a source: that command travels as {@code CONSOLE}.
     */
    private static final java.util.Set<String> SOURCES = java.util.Set.of("DISCORD", "GAME", "CONSOLE", "WEB");
}

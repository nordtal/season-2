package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.notify.Channel;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What a Minecraft server can be asked to do by another process; each record is one kind. */
public sealed interface ServerRequest {

    /** The SMP's inbox table. */
    InboxTable<ServerRequest> SMP = InboxTable.of("smp_inbox", Channel.SERVER, ServerRequest.class);

    /** The Hunger Games server's inbox table. */
    InboxTable<ServerRequest> HUNGER_GAMES = InboxTable.of("hunger_games_inbox", Channel.SERVER, ServerRequest.class);

    /** Limbo's inbox table. */
    InboxTable<ServerRequest> LIMBO = InboxTable.of("limbo_inbox", Channel.SERVER, ServerRequest.class);

    /**
     * A command of the shared catalogue, typed on another console or asked for in Steward, run where it belongs.
     * The one kind carrying a command line; it goes with the catalogue.
     *
     * @param path        the command path joined with spaces, no leading slash
     * @param arguments   the arguments as a line, decoded against the command's declaration
     * @param source      where it was typed: {@code CONSOLE}, {@code GAME} or {@code WEB}
     * @param requestedBy who asked, for people to read
     * @param discordId   their Discord id, {@code null} for a console
     * @param minecraftId their Minecraft account, {@code null} for a console and an unlinked member
     * @param locale      the language tag the answer is rendered in
     */
    record Command(
            String path,
            String arguments,
            String source,
            String requestedBy,
            @Nullable DiscordId discordId,
            @Nullable UUID minecraftId,
            String locale)
            implements ServerRequest {

        public Command {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(requestedBy, "requestedBy");
            Objects.requireNonNull(locale, "locale");
        }
    }
}

package eu.nordtal.s2.proxy.ping;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.network.NetworkSnapshot;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Substitutes the {@code {name}} placeholders in a MOTD, before MiniMessage ever sees the string.
 *
 * <b>Order matters, and it is this way round on purpose.</b>
 * Substitution happens <b>first</b> and parsing second, which is what lets a MOTD write
 * {@code <white>{hg-alive}</white>} and have the number take the colour. The reverse order would
 * mean a value containing an angle bracket could inject tags - not a risk with counts, and a real
 * one with {@code {smp-milestone}}, which is a key out of a YAML file somebody edits. The tag-shaped
 * characters in a substituted value are therefore escaped, so a milestone called {@code <red>} shows
 * up as text rather than turning the rest of the line red.
 *
 * <b>An unknown placeholder is left standing.</b>
 * {@code {hg-alve}} renders as {@code {hg-alve}} rather than as nothing. A typo that vanishes is a
 * typo nobody finds; one that shows up in the server browser is fixed the same day. This matches
 * how {@code eu.nordtal.s2.common.message.Messages} treats a parameter it was not given.
 *
 * Nothing here touches the database: every value comes either from the proxy itself (which knows
 * its own player counts) or from the {@link NetworkSnapshot} a timer refreshed.
 */
final class Placeholders {

    private Placeholders() {}

    /**
     * @param template  the MOTD as written in {@code network.yml}
     * @param proxy     the proxy, for its own player counts
     * @param phase     the phase whose MOTD this is
     * @param maximum   {@code network.yml#max-players}
     * @param snapshot  the last numbers that came back from the database
     * @param countdown the rendered countdown line, for {@code {countdown}}
     * @return the template with every recognised placeholder replaced
     */
    static String apply(
            final String template,
            final ProxyServer proxy,
            final SeasonPhase phase,
            final int maximum,
            final NetworkSnapshot snapshot,
            final String countdown) {
        if (template == null || template.indexOf('{') < 0) {
            return template == null ? "" : template;
        }

        final StringBuilder out = new StringBuilder(template.length() + 32);
        int index = 0;
        while (index < template.length()) {
            final char character = template.charAt(index);
            if (character != '{') {
                out.append(character);
                index++;
                continue;
            }
            final int close = template.indexOf('}', index);
            if (close < 0) {
                // An unclosed brace is the rest of the string, verbatim.
                out.append(template, index, template.length());
                break;
            }
            final String name = template.substring(index + 1, close);
            final String value = resolve(name, proxy, phase, maximum, snapshot, countdown);
            out.append(value == null ? template.substring(index, close + 1) : escape(value));
            index = close + 1;
        }
        return out.toString();
    }

    /** @return the value, or {@code null} for a name this build does not know */
    private static @Nullable String resolve(
            final String name,
            final ProxyServer proxy,
            final SeasonPhase phase,
            final int maximum,
            final NetworkSnapshot snapshot,
            final String countdown) {
        if (name.startsWith("players:")) {
            final String server = name.substring("players:".length());
            final Optional<RegisteredServer> registered = proxy.getServer(server);
            // A server velocity.toml lacks reads as 0, not an error: the MOTD is not where to discover misconfig.
            return registered
                    .map(value -> String.valueOf(value.getPlayersConnected().size()))
                    .orElse("0");
        }
        return switch (name) {
            case "online" -> String.valueOf(proxy.getPlayerCount());
            case "max" -> String.valueOf(maximum);
            case "phase" -> phase.name();
            case "countdown" -> countdown;

            case "hg-state" -> snapshot.hgState();
            case "hg-teams" -> String.valueOf(snapshot.hgTeams());
            case "hg-teams-alive" -> String.valueOf(snapshot.hgTeamsAlive());
            case "hg-participants" -> String.valueOf(snapshot.hgParticipants());
            case "hg-alive" -> String.valueOf(snapshot.hgAlive());
            case "hg-eliminated" -> String.valueOf(snapshot.hgEliminated());

            case "smp-milestone" -> snapshot.smpMilestone();
            case "smp-milestone-progress" -> String.valueOf(snapshot.smpProgress());
            case "smp-milestones-done" -> String.valueOf(snapshot.smpMilestonesDone());
            case "smp-milestones-total" -> String.valueOf(snapshot.smpMilestones());
            case "smp-aura-total" -> String.valueOf(snapshot.smpAuraTotal());
            case "smp-players" -> String.valueOf(snapshot.smpPlayers());

            default -> null;
        };
    }

    /**
     * Makes a substituted value inert for MiniMessage.
     *
     * Delegates to {@link MessageRenderer#escape(String)}, the same escape every message in the
     * network goes through, so the rule that only {@code <} can begin a tag is fixed in one place.
     */
    private static String escape(final String value) {
        return MessageRenderer.escape(value);
    }
}

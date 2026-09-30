package eu.nordtal.s2.proxy.ping;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.network.NetworkSnapshot;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Substitutes the {@code {name}} placeholders in a MOTD before MiniMessage parses it.
 *
 * Values are escaped, so a key out of YAML cannot inject tags; an unknown placeholder is left standing.
 */
final class Placeholders {

    private Placeholders() {}

    /**
     * Replaces every recognised placeholder in {@code template}.
     *
     * @param countdown the rendered countdown line, for {@code {countdown}}
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

    /** The value, or {@code null} for a name this build does not know. */
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
            // A server velocity.toml lacks reads as 0; the MOTD is not where to discover misconfiguration.
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

    /** Makes a substituted value inert for MiniMessage. */
    private static String escape(final String value) {
        return MessageRenderer.escape(value);
    }
}

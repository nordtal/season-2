package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.steward.journal.Journaled;
import eu.nordtal.season.steward.journal.Unjournaled;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import io.javalin.security.Roles;
import io.javalin.security.RouteRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Fails the build on a changing route that writes no journal line and is not named below. */
class JournaledRoutesTest extends WebFixture {

    private static final Set<HandlerType> WRITES =
            Set.of(HandlerType.POST, HandlerType.PUT, HandlerType.PATCH, HandlerType.DELETE);

    /** The changing routes that write no line of their own, each with its reason. */
    private static final Map<String, Unjournaled> LEFT_OUT = Map.ofEntries(
            Map.entry("POST /api/access/grant", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/access/revoke", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/access/unlink", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/access/settle", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/people/{id}/playtime", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/people/{id}/aura", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/people/{id}/spins", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/season/phase", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/season/date", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/smp/objective", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/smp/milestone", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/hunger-games/start", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /api/announcements", Unjournaled.IN_THE_CHANGE),
            Map.entry("POST /auth/logout", Unjournaled.OWN_SESSION),
            Map.entry("DELETE /api/step-up", Unjournaled.OWN_SESSION),
            Map.entry("POST /auth/webauthn/register/start", Unjournaled.CEREMONY_START),
            Map.entry("POST /auth/webauthn/authenticate/start", Unjournaled.CEREMONY_START),
            Map.entry("POST /auth/totp/enrol/start", Unjournaled.CEREMONY_START),
            Map.entry("POST /api/message-preview", Unjournaled.SHOWN_TO_ONESELF),
            Map.entry("POST /api/message-changes", Unjournaled.SHOWN_TO_ONESELF),
            Map.entry("POST /api/document-preview", Unjournaled.SHOWN_TO_ONESELF),
            Map.entry("POST /api/documents/render", Unjournaled.SHOWN_TO_ONESELF),
            Map.entry("POST /api/shared-parts/{name}/changes", Unjournaled.SHOWN_TO_ONESELF),
            Map.entry("POST /api/document-images", Unjournaled.SHOWN_ONCE_SAVED));

    @Test
    void everyChangingRouteIsJournaledOrNamedAsLeftOut() {
        final List<String> wrong = new ArrayList<>();
        final Map<String, Unjournaled> leftOut = new TreeMap<>();
        for (final Endpoint endpoint : RouteTable.endpoints(app)) {
            if (!WRITES.contains(endpoint.method)) {
                continue;
            }
            final String named = endpoint.method + " " + endpoint.path;
            final List<RouteRole> decisions = rolesOf(endpoint).stream()
                    .filter(role -> role instanceof Journaled || role instanceof Unjournaled)
                    .toList();
            if (decisions.size() != 1) {
                wrong.add(named + " carries " + decisions.size() + " journal decisions");
            } else if (decisions.getFirst() instanceof Unjournaled reason) {
                leftOut.put(named, reason);
            }
        }
        assertTrue(
                wrong.isEmpty(),
                "Every changing route says what it journals, with a Journaled or an Unjournaled on the line"
                        + " that registers it:\n  " + String.join("\n  ", wrong));
        assertEquals(
                new TreeMap<>(LEFT_OUT),
                leftOut,
                "A changing route left out of the journal is named in LEFT_OUT with its reason, and nothing else is.");
    }

    private static Set<RouteRole> rolesOf(final Endpoint endpoint) {
        final Roles roles = endpoint.metadata(Roles.class);
        return roles == null ? Set.of() : Set.copyOf(roles.getRoles());
    }
}

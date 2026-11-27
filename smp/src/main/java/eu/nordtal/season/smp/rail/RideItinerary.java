package eu.nordtal.season.smp.rail;

import eu.nordtal.season.packrendering.menu.MenuFont;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Builds the travel-order itinerary from the cart's current rail to a chosen station.
 * Counts switched junctions on each stretch between passed stations and checks whether each name fits the small font.
 */
final class RideItinerary {

    /** One intermediate station passed along the route, with the switched junctions on the stretch before it. */
    record Passed(UUID station, String name, int switchesBefore) {}

    /** The complete itinerary of one selected route in travel order. */
    record Plan(
            String originName,
            boolean atStation,
            List<Passed> passed,
            int switchesBeforeDestination,
            String destinationName,
            int distance,
            List<RailPos> routeRails) {

        Plan {
            passed = List.copyOf(passed);
            routeRails = List.copyOf(routeRails);
        }
    }

    private RideItinerary() {}

    /**
     * Computes the itinerary from {@code at} to {@code destination} over {@code graph}.
     * Hidden intermediate stations are left out of the passed list; returns empty when no route leads there.
     */
    static Optional<Plan> of(
            final RailGraph graph,
            final RailPos at,
            final Collection<StationRow> stations,
            final Set<UUID> hidden,
            final UUID destination,
            final String spawnName,
            final String trackLabel) {
        final Map<RailPos, StationRow> byPos = new HashMap<>();
        @Nullable StationRow target = null;
        for (final StationRow row : stations) {
            byPos.put(row.pos(), row);
            if (row.id().equals(destination)) {
                target = row;
            }
        }
        if (target == null) {
            return Optional.empty();
        }
        final @Nullable String targetName = displayName(target, spawnName);
        if (targetName == null) {
            return Optional.empty();
        }
        final Optional<Route> found = Route.between(graph, at, target.pos());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        final Route route = found.get();
        final @Nullable StationRow originStation = byPos.get(at);
        final @Nullable String originDisplay = originStation == null ? null : displayName(originStation, spawnName);
        final String originName = originDisplay != null ? originDisplay : trackLabel;
        final List<Passed> passed = new ArrayList<>();
        final int tailSwitches = collectPassed(route, graph.junctions(), byPos, hidden, destination, spawnName, passed);
        return Optional.of(new Plan(
                originName, originDisplay != null, passed, tailSwitches, targetName, route.length(), route.rails()));
    }

    private static int collectPassed(
            final Route route,
            final Set<RailPos> junctions,
            final Map<RailPos, StationRow> byPos,
            final Set<UUID> hidden,
            final UUID destination,
            final String spawnName,
            final List<Passed> passed) {
        final Set<Integer> switchIndices = route.switches(junctions).keySet();
        int switches = switchIndices.contains(0) ? 1 : 0;
        int railIndex = 0;
        final List<RailEdge> legs = route.legs();
        for (int i = 0; i < legs.size(); i++) {
            final RailEdge leg = legs.get(i);
            railIndex += leg.rails().size() + 1;
            if (i == legs.size() - 1) {
                break;
            }
            if (switchIndices.contains(railIndex)) {
                switches++;
            }
            final @Nullable StationRow mid = byPos.get(leg.to());
            if (mid == null || mid.id().equals(destination) || hidden.contains(mid.id())) {
                continue;
            }
            final @Nullable String name = displayName(mid, spawnName);
            if (name != null) {
                passed.add(new Passed(mid.id(), name, switches));
                switches = 0;
            }
        }
        return switches;
    }

    /** Returns whether {@code name} can be spelled in full in the five-pixel font without substitution. */
    static boolean fitsSmallFont(final String name) {
        for (int index = 0; index < name.length(); index++) {
            final char character = name.charAt(index);
            if (character == ' ' || character == '\u2026') {
                continue;
            }
            if (!MenuFont.covers(Character.toUpperCase(character))) {
                return false;
            }
        }
        return true;
    }

    /** Formats {@code distance} in metres using the five-pixel font's small m glyph. */
    static String smallMetres(final int distance) {
        return distance + " " + MenuFont.METRE;
    }

    private static @Nullable String displayName(final StationRow row, final String spawnName) {
        return row.spawn() ? spawnName : row.name();
    }
}

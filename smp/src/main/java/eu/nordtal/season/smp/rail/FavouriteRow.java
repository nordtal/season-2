package eu.nordtal.season.smp.rail;

import java.util.UUID;

/** A row of {@code smp_rail_favourite}: a station a player marked as a favourite. */
public record FavouriteRow(UUID player, UUID station) {}

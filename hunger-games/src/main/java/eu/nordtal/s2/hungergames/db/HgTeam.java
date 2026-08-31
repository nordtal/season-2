package eu.nordtal.s2.hungergames.db;

import java.util.UUID;

/** One row of {@code hg_team}. {@code colourRgb}/{@code colourNamed} are null until countdown. */
public record HgTeam(UUID id, UUID gameId, String name, Integer colourRgb, String colourNamed) {
}

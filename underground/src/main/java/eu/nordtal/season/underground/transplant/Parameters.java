package eu.nordtal.season.underground.transplant;

/**
 * The measures of the transplant, in blocks.
 *
 * @param ambiguousReach how far from a built block an ambiguous block counts as built
 * @param outsideReach how far into a cave from the open sky the map stays as it is
 * @param taper the length over which a kept cave narrows to its end
 * @param shell how much rock and space around a built block stays
 * @param protectedReach how far the open space around something built is followed
 * @param wall how much rock around that open space stays
 * @param crust how much ground below the outside stays
 * @param band the depth below the kept zone within which generated caves close
 * @param roughness how far noise moves the closing surface
 * @param top the highest layer the generated world replaces
 * @param entrances whether map caves that go deeper than the kept zone are joined to generated caves
 */
public record Parameters(
        int ambiguousReach,
        int outsideReach,
        int taper,
        int shell,
        int protectedReach,
        int wall,
        int crust,
        int band,
        double roughness,
        int top,
        boolean entrances) {

    /** The widest wall distance that is measured inside a cave of the map. */
    static final int WALL_CAP = 16;

    /** The widest wall distance that is measured inside a generated cave. */
    static final int CAVE_CAP = 32;

    /** The longest distance a byte holds in thirds of a block, below the two values that mark a cell unreached. */
    static final int MOST = 84;

    /**
     * Throws when a measure cannot be held.
     * A distance is counted in one byte, and the protected space with its shell and wall stays in the tile's margin.
     */
    public void check() {
        final String[] names = {
            "--ambiguous-reach",
            "--outside-reach",
            "--taper",
            "--shell",
            "--protected-reach",
            "--wall",
            "--crust",
            "--band"
        };
        final int[] reaches = {ambiguousReach, outsideReach, taper, shell, protectedReach, wall, crust, band};
        for (int i = 0; i < reaches.length; i++) {
            if (reaches[i] < 0 || reaches[i] > MOST) {
                throw new IllegalArgumentException(names[i] + " " + reaches[i]
                        + " cannot be counted. Give a distance from 0 to " + MOST + " blocks.");
            }
        }
        final int margin = TileLoader.MARGIN * 16;
        if (shell + protectedReach + wall > margin) {
            throw new IllegalArgumentException("--shell " + shell + ", --protected-reach " + protectedReach
                    + " and --wall " + wall + " reach " + (shell + protectedReach + wall) + " blocks together, more"
                    + " than the " + margin + " blocks a tile reads beyond its edge. Make them shorter.");
        }
        if (ambiguousReach > margin) {
            throw new IllegalArgumentException("--ambiguous-reach " + ambiguousReach + " is more than the " + margin
                    + " blocks a tile reads beyond its edge. Give at most " + margin + " blocks.");
        }
        if (taper > outsideReach) {
            throw new IllegalArgumentException("--taper " + taper + " is longer than --outside-reach " + outsideReach
                    + ". Give a taper no longer than the outside reach.");
        }
        if (crust < taper) {
            throw new IllegalArgumentException("--crust " + crust + " is shorter than --taper " + taper
                    + ", so the mouth of an entrance would close. Give a crust at least as long as the taper.");
        }
    }

    /** Returns the measures the tool runs with unless a command line changes them. */
    public static Parameters standard() {
        return new Parameters(8, 24, 8, 5, 24, 3, 8, 12, 1.5, 236, true);
    }

    /** Returns the same measures with or without entrances. */
    public Parameters withEntrances(final boolean wanted) {
        return new Parameters(
                ambiguousReach, outsideReach, taper, shell, protectedReach, wall, crust, band, roughness, top, wanted);
    }
}

package eu.nordtal.season.stewardagent.pack;

import java.io.IOException;
import java.nio.file.Path;

/** Writes the map pack zip from BlueMap's tiles folder. */
@FunctionalInterface
interface MapZipper {

    void map(Path tiles, Path zip) throws IOException;
}

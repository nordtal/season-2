package eu.nordtal.season.stewardagent.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Writes the pack zip from the vanilla facts, the prizes, the wheel's texts and the pack's fixed folders. */
@FunctionalInterface
interface PackZipper {

    void zip(Path facts, Path prizes, Path texts, Path zip, List<Path> roots) throws IOException;
}

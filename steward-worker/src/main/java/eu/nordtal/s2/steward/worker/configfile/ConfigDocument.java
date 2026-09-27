package eu.nordtal.s2.steward.worker.configfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One config file read as a form, its entries in file order with each section before its keys.
 *
 * @param file where it was read from
 * @param revision what the file said when read; a save carrying a stale one is refused
 * @param header the comment block at the top of the file, without {@code # } and the trailing blank line
 * @param entries every key in the file, in file order, sections included
 */
public record ConfigDocument(Path file, String revision, List<String> header, List<ConfigEntry> entries) {

    public ConfigDocument {
        header = List.copyOf(header);
        entries = List.copyOf(entries);
    }

    /**
     * Returns the entry at a dotted path.
     *
     * @param path the dotted path
     * @return the entry, if the file has one
     */
    public Optional<ConfigEntry> find(final String path) {
        return entries.stream().filter(entry -> entry.path().equals(path)).findFirst();
    }

    /** Returns the paths of every entry that {@link ConfigFiles#write} would accept a change to. */
    public List<String> editablePaths() {
        return entries.stream()
                .filter(ConfigEntry::editable)
                .map(ConfigEntry::path)
                .toList();
    }
}

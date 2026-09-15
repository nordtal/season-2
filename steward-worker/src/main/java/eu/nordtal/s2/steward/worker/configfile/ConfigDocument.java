package eu.nordtal.s2.steward.worker.configfile;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One config file, read as a form rather than as an object.
 *
 * <p>The entries are in the order the file has them, a nested section immediately before the keys
 * beneath it, which is the order a page should draw them in: jcore writes them in {@code @Order},
 * and that order is somebody's decision about what an operator reads first.</p>
 *
 * @param file     where it was read from
 * @param revision what the file said when it was read - see {@link ConfigFiles#revisionOf}. It is
 *                 handed to the browser and comes back with the next save, so a form somebody left
 *                 open while another window wrote the same file is refused instead of quietly
 *                 undoing that write
 * @param header  the comment block at the top of the file - jcore's {@code @ConfigSpec(header=…)},
 *                with the leading {@code # } removed and the blank line that ends it dropped.
 *                Empty when the file starts with a key or with a comment that belongs to one
 * @param entries every key in the file, in file order, sections included
 */
public record ConfigDocument(
        @NotNull Path file,
        @NotNull String revision,
        @NotNull List<String> header,
        @NotNull List<ConfigEntry> entries) {

    /** Defensive copies: a document is a snapshot of a file at a moment, not a mutable model. */
    public ConfigDocument {
        header = List.copyOf(header);
        entries = List.copyOf(entries);
    }

    /**
     * @param path the dotted path
     * @return the entry at that path, if the file has one
     */
    public @NotNull Optional<ConfigEntry> find(final @NotNull String path) {
        return entries.stream().filter(entry -> entry.path().equals(path)).findFirst();
    }

    /**
     * @return the paths of every entry that {@link ConfigFiles#write} would accept a change to
     */
    public @NotNull List<String> editablePaths() {
        return entries.stream().filter(ConfigEntry::editable).map(ConfigEntry::path).toList();
    }
}

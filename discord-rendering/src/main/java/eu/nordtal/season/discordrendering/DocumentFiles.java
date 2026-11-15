package eu.nordtal.season.discordrendering;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.utils.FileUpload;

/** Opens the packaged banners and adds the document's uploaded images. */
final class DocumentFiles {
    private DocumentFiles() {}

    static List<FileUpload> files(final List<String> attachments, final List<FileUpload> uploads) {
        final List<FileUpload> all =
                new ArrayList<>(attachments.stream().map(DocumentFiles::banner).toList());
        all.addAll(uploads);
        return all;
    }

    private static FileUpload banner(final String name) {
        final InputStream stream = DocumentFiles.class.getClassLoader().getResourceAsStream("banners/" + name);
        if (stream == null) {
            throw new IllegalStateException("banners/" + name + " is missing from the jar");
        }
        return FileUpload.fromData(stream, name);
    }
}

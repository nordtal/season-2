package eu.nordtal.season.discordrendering;

import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.utils.FileUpload;

/** Joins drawn documents into one message, which uploads each image once, so no "edited" mark stands between them. */
public final class OneMessage {
    private OneMessage() {}

    /** Returns one message that shows {@code first}, then {@code next} under it. */
    public static DocumentRenderer.Drawn of(final DocumentRenderer.Drawn first, final DocumentRenderer.Drawn next) {
        final List<MessageTopLevelComponent> both = new ArrayList<>(first.components());
        both.addAll(next.components());
        final List<String> named = new ArrayList<>(first.attachments());
        next.attachments().stream().filter(name -> !named.contains(name)).forEach(named::add);
        final List<FileUpload> uploaded = new ArrayList<>(first.uploads());
        next.uploads().stream()
                .filter(file ->
                        uploaded.stream().noneMatch(held -> held.getName().equals(file.getName())))
                .forEach(uploaded::add);
        return new DocumentRenderer.Drawn(List.copyOf(both), List.copyOf(named), List.copyOf(uploaded));
    }
}

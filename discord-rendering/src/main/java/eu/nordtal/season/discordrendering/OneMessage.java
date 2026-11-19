package eu.nordtal.season.discordrendering;

import eu.nordtal.season.messages.document.DocumentCheck;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.utils.FileUpload;

/** Joins drawn documents into one message, which uploads each image once. */
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

    /** The most files Discord takes in one message. */
    static final int FILES = 10;

    /** Returns whether Discord takes the message, which can be too big though each part fits on its own. */
    public static boolean fits(final DocumentRenderer.Drawn drawn) {
        return MessageComponentTree.of(drawn.components())
                                .findAll(Component.class)
                                .size()
                        <= DocumentCheck.COMPONENTS
                && drawn.text().length() <= DocumentCheck.TEXT
                && drawn.attachments().size() + drawn.uploads().size() <= FILES;
    }
}

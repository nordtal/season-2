package eu.nordtal.season.discordrendering;

import eu.nordtal.season.discordrendering.DocumentRenderer.Filled;
import eu.nordtal.season.messages.document.DocumentDeclaration;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.document.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;

/** Fills every slot of a draft with an example, as a preview and a draft sent to an admin show it. */
final class ExampleSlots {

    private ExampleSlots() {}

    static Map<String, Filled> of(final DocumentDeclaration declaration, final List<Locale> languages) {
        final Map<String, Filled> slots = new HashMap<>();
        declaration.slots().forEach(slot -> slots.put(slot.id(), example(slot, languages)));
        return slots;
    }

    private static Filled example(final Slot slot, final List<Locale> languages) {
        return switch (slot.type()) {
            case BUTTON -> new DocumentRenderer.FilledButton(slot.id());
            case SELECT ->
                new DocumentRenderer.FilledSelect(
                        slot.id(),
                        List.of(new DocumentRenderer.Option(
                                slot.id(), slot.lined() ? null : slot.id(), Value.examples(slot.values()), null)));
            case LIST -> new DocumentRenderer.FilledList(List.of(Value.examples(slot.values())));
            case LANGUAGES -> new DocumentRenderer.FilledLanguages(languages);
            case INPUT ->
                new DocumentRenderer.FilledInput(placeholder -> TextInput.create(slot.id(), TextInputStyle.SHORT)
                        .setPlaceholder(placeholder)
                        .build());
            case ITEM, LABEL -> throw new IllegalArgumentException("the game fills a menu's " + slot.id());
        };
    }
}

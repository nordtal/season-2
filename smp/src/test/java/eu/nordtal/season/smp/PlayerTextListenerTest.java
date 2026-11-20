package eu.nordtal.season.smp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class PlayerTextListenerTest {

    @Test
    void filteringKeepsComponentStylesAndChildren() throws ReflectiveOperationException {
        final Component input = Component.text("head ")
                .append(Component.text("red " + Character.toString(0xFE000), NamedTextColor.RED));
        final Component expected = Component.text("head ").append(Component.text("red ", NamedTextColor.RED));

        assertEquals(expected, filtered(input));
    }

    private static Component filtered(final Component input) throws ReflectiveOperationException {
        final java.lang.reflect.Method method = PlayerTextListener.class.getDeclaredMethod("filtered", Component.class);
        method.setAccessible(true);
        return (Component) method.invoke(null, input);
    }
}

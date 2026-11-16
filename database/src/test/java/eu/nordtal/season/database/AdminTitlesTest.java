package eu.nordtal.season.database;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Shown;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Holds every title of the admin bundle to the 32 characters a title of the admin channel has. */
class AdminTitlesTest {

    private static final int TITLE = 32;

    @Test
    void everyRunTitleFitsForEveryKindAndStage() {
        final List<MessageRef> titles = new ArrayList<>();
        for (final UpdateKind kind : UpdateKind.values()) {
            for (final RunStage stage : RunStage.values()) {
                titles.add(TEXTS.run().title(kind, stage));
            }
        }
        assertEquals(List.of(), tooLong(titles));
    }

    @Test
    void everyJournalActionFits() {
        assertEquals(
                List.of(),
                tooLong(List.of(JournalAction.values()).stream()
                        .map(action -> TEXTS.journal().action(action))
                        .toList()));
    }

    /**
     * Every alert title fits, found as each alert text without shown places of its own.
     * A line below a title names its own places, so the rest are titles. A title that names a value takes the
     * longest value the stack has, and a title that lists services takes one long name or two short ones.
     */
    @Test
    void everyAlertTitleFitsWithTheLongestValueTheStackNames() throws ReflectiveOperationException {
        final List<MessageRef> titles = new ArrayList<>();
        int found = 0;
        for (final Method method : AdminTexts.Alerts.class.getMethods()) {
            if (method.isAnnotationPresent(Shown.class) || method.getReturnType() != MessageRef.class) {
                continue;
            }
            found++;
            for (final Object[] args : argsOf(method.getParameters())) {
                titles.add((MessageRef) method.invoke(TEXTS.alert(), args));
            }
        }
        assertTrue(found > 30, "the alert titles are found: " + found);
        assertEquals(List.of(), tooLong(titles));
    }

    private static final Map<String, String> LONGEST =
            Map.of("service", "hunger-games", "volume", "smp-world", "channel", "#deutsch-chat", "name", "Player");

    private static final List<List<String>> LISTS = List.of(List.of("hunger-games"), List.of("smp", "proxy"));

    /** Every combination of the values a title's arguments can take; a count is the size of the list beside it. */
    private static List<Object[]> argsOf(final Parameter[] parameters) {
        List<Object[]> combinations = new ArrayList<>();
        combinations.add(new Object[parameters.length]);
        for (int at = 0; at < parameters.length; at++) {
            final Parameter parameter = parameters[at];
            final Class<?> type = parameter.getType();
            final List<Object> values = new ArrayList<>();
            if (type.isEnum()) {
                values.addAll(List.of(type.getEnumConstants()));
            } else if (type == boolean.class) {
                values.addAll(List.of(true, false));
            } else if (type == List.class) {
                values.addAll(LISTS);
            } else if (type == String.class) {
                final String name = parameter.getAnnotation(Arg.class).value();
                values.add(Objects.requireNonNull(LONGEST.get(name), "no longest value for " + name));
            } else if (type != int.class && type != long.class) {
                throw new IllegalStateException("no values for " + type);
            }
            final List<Object[]> next = new ArrayList<>();
            for (final Object[] combination : combinations) {
                if (values.isEmpty()) {
                    next.add(combination);
                    continue;
                }
                for (final Object value : values) {
                    final Object[] copy = combination.clone();
                    copy[at] = value;
                    next.add(copy);
                }
            }
            combinations = next;
        }
        for (final Object[] combination : combinations) {
            for (int at = 0; at < parameters.length; at++) {
                final Class<?> type = parameters[at].getType();
                if (type == int.class || type == long.class) {
                    final int size = Arrays.stream(combination)
                            .filter(List.class::isInstance)
                            .mapToInt(list -> ((List<?>) list).size())
                            .findFirst()
                            .orElse(1);
                    combination[at] = type == int.class ? (Object) size : (Object) (long) size;
                }
            }
        }
        return combinations;
    }

    private static List<String> tooLong(final List<MessageRef> titles) {
        return titles.stream()
                .map(DatabaseText::english)
                .filter(title -> title.length() > TITLE)
                .toList();
    }
}

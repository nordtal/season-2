package eu.nordtal.season.database.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Holds the admin bundle's action names against {@link JournalAction}, since its {@code other} branch hides a gap. */
class JournalActionLabelTest {

    @Test
    void everyActionHasANameOfItsOwnInTheShippedBundle() throws IOException {
        final Properties bundle = new Properties();
        try (Reader reader = new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/messages/admin/en.properties")),
                StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }
        final String select = Objects.requireNonNull(bundle.getProperty("journal.action"), "journal.action");
        final List<String> missing = Arrays.stream(JournalAction.values())
                .map(action -> action.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .filter(choice -> !Pattern.compile("[{ ]" + Pattern.quote(choice) + " \\{")
                        .matcher(select)
                        .find())
                .toList();
        assertEquals(List.of(), missing, "these actions fall through to their own key in the journal");
    }
}

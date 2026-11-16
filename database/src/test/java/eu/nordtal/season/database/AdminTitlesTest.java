package eu.nordtal.season.database;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.DiscordRole;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.MessageRef;
import java.util.ArrayList;
import java.util.List;
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

    /** The alerts that name a value take the longest value the stack has: a service, a volume or a channel. */
    @Test
    void everyAlertTitleFitsWithTheLongestValueTheStackNames() {
        final List<MessageRef> titles = new ArrayList<>();
        for (final AlertType type : AlertType.values()) {
            titles.add(TEXTS.alert().clear(type));
        }
        for (final DiscordRole role : DiscordRole.values()) {
            titles.add(TEXTS.alert().roleNotChanged(role, true));
            titles.add(TEXTS.alert().roleNotChanged(role, false));
        }
        titles.addAll(List.of(
                TEXTS.alert().noServices(),
                TEXTS.alert().notRunning("hunger-games"),
                TEXTS.alert().unhealthy("hunger-games"),
                TEXTS.alert().restarting("hunger-games"),
                TEXTS.alert().failedOnce("hunger-games"),
                TEXTS.alert().olderImage(List.of("hunger-games"), 1),
                TEXTS.alert().notCompared(),
                TEXTS.alert().deadPack(),
                TEXTS.alert().noBackup(),
                TEXTS.alert().noDump(),
                TEXTS.alert().noArchive("smp-world"),
                TEXTS.alert().oldDump(),
                TEXTS.alert().oldArchive("smp-world"),
                TEXTS.alert().noOffsite(),
                TEXTS.alert().oldOffsite(),
                TEXTS.alert().disk(),
                TEXTS.alert().memory(),
                TEXTS.alert().payment(),
                TEXTS.alert().roleAmbiguous("Player"),
                TEXTS.alert().roleNotCreated("Player"),
                TEXTS.alert().lockWithoutChannel(),
                TEXTS.alert().lockNotKept(),
                TEXTS.alert().startChannelHidden(),
                TEXTS.alert().languageNotKept(),
                TEXTS.alert().channelSeen("#deutsch-chat"),
                TEXTS.alert().dm(),
                TEXTS.alert().linkFailed(),
                TEXTS.alert().purchaseFailed(),
                TEXTS.alert().linkRefused()));
        assertEquals(List.of(), tooLong(titles));
    }

    private static List<String> tooLong(final List<MessageRef> titles) {
        return titles.stream()
                .map(DatabaseText::english)
                .filter(title -> title.length() > TITLE)
                .toList();
    }
}

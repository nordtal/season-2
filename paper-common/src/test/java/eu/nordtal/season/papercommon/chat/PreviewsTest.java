package eu.nordtal.season.papercommon.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.inbox.DocumentPreview;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.tab.TabList;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** A preview reaches the admin's player where its key is shown, and nobody else. */
class PreviewsTest {

    private static final PlayerId ALEX = PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));

    private static final MessageRenderer RENDERER =
            MessageRenderer.of(Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN));

    /** The proxy paints a bad tone its own way; every other service keeps the defaults. */
    private static final ToneColours PROXY = ToneColours.parse(Map.of(Tone.BAD, "#123456"), unreadable -> {});

    /** Every call the player received, by method name and its one argument. */
    private final List<Object[]> calls = new ArrayList<>();

    /** Both arguments of every tab list the player received. */
    private final List<Object[]> headerAndFooter = new ArrayList<>();

    private final Player alex = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                if (method.getName().equals("getUniqueId")) {
                    return ALEX.value();
                }
                if (method.getName().equals("playerListFooter")) {
                    return Component.text("the footer now");
                }
                if (method.getName().equals("sendPlayerListHeaderAndFooter")) {
                    headerAndFooter.add(args);
                }
                calls.add(new Object[] {method.getName(), args == null ? null : args[0]});
                return null;
            });

    /** The work handed to the main thread, which a test runs itself; a window cannot open without a server. */
    private final List<Runnable> onMain = new ArrayList<>();

    private final List<Duration> delays = new ArrayList<>();

    private final Scheduler main = new Scheduler() {
        @Override
        public void execute(final Runnable work) {
            onMain.add(work);
        }

        @Override
        public Task after(final Duration delay, final Runnable work) {
            delays.add(delay);
            onMain.add(work);
            return () -> {};
        }
    };

    private final Previews previews = new Previews(
            uuid -> uuid.equals(ALEX.value()) ? alex : null,
            RENDERER,
            service -> service.equals("proxy") ? PROXY : ToneColours.DEFAULTS,
            main);

    private static DocumentPreview drafted(final DocumentSpec.Shape shape, final String name, final String parts) {
        return new DocumentPreview(
                "en",
                new Documents.Packaged(
                        DocumentSpec.game("test", name, "Test", shape)
                                .titled(name)
                                .declaration(),
                        Document.decode("{\"parts\": [" + parts + "]}")));
    }

    private static final String TWO_LINES = """
            {"type": "TEXT", "lines": [{"text": {"en": "Drafted"}}, {"text": {"en": "<muted>and below</muted>"}}]}""";

    private static MessagePreview preview(final Display shown) {
        return preview(shown, "smp");
    }

    private static MessagePreview preview(final Display shown, final String service) {
        return new MessagePreview(
                "admin.confirm", "en", "Type <bad>{command}</bad>", shown, service, Map.of("command", "/smp unlock"));
    }

    /** The colour the value of the one chat line shown so far is drawn in. */
    private String colourOfTheValue() {
        final Component line = (Component) calls.getFirst()[1];
        final List<Component> parts = line.children();
        return Objects.requireNonNull(parts.getLast().color()).asHexString().toLowerCase(Locale.ROOT);
    }

    private static String plain(final Object component) {
        return PlainTextComponentSerializer.plainText().serialize((Component) component);
    }

    @Test
    void aTitleIsShownAsATitleWithTheMessagesValues() {
        assertInstanceOf(Answer.Done.class, previews.show(ALEX, preview(Display.TITLE)));

        assertEquals(1, calls.size());
        assertEquals("showTitle", calls.getFirst()[0]);
        assertEquals("Type /smp unlock", plain(((Title) calls.getFirst()[1]).title()));
    }

    @Test
    void anActionBarIsShownThereAndAMenuLineInChat() {
        previews.show(ALEX, preview(Display.ACTION_BAR));
        previews.show(ALEX, preview(Display.GUI));

        assertEquals("sendActionBar", calls.get(0)[0]);
        assertEquals("sendMessage", calls.get(1)[0]);
        assertEquals("Type /smp unlock", plain(calls.get(1)[1]));
    }

    @Test
    void aPlayerWhoIsNotHereIsRefusedAndNothingIsShown() {
        final Answer answer = previews.show(
                PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000b")), preview(Display.CHAT));

        assertEquals(ServerRefusal.NOT_HERE, ((Answer.Refused) answer).refusal().reason());
        assertTrue(calls.isEmpty());
    }

    /** A key of another service, the proxy's on the SMP, is painted as that service paints it. */
    @Test
    void aToneIsPaintedInTheColourOfTheKeysOwnService() {
        previews.show(ALEX, preview(Display.CHAT, "proxy"));

        assertEquals("#123456", colourOfTheValue());
    }

    @Test
    void aDraftedTitleShowsItsFirstLineAsTitleAndTheRestAsSubtitle() {
        assertInstanceOf(Answer.Done.class, previews.show(ALEX, drafted(DocumentSpec.Shape.TITLE, "t", TWO_LINES)));

        final Title title = (Title) calls.getFirst()[1];
        assertEquals("Drafted", plain(title.title()));
        assertEquals("and below", plain(title.subtitle()));
    }

    @Test
    void aDraftedTabListHeaderStandsOverTheFooterThePlayerSeesNow() {
        previews.show(
                ALEX,
                new DocumentPreview(
                        "en",
                        new Documents.Packaged(
                                TabList.HEADER.declaration(), Document.decode("{\"parts\": [" + TWO_LINES + "]}"))));

        assertEquals("sendPlayerListHeaderAndFooter", calls.getFirst()[0]);
        final Object[] both = headerAndFooter.getFirst();
        assertEquals("Drafted\nand below", plain(both[0]));
        assertEquals("the footer now", plain(both[1]));
    }

    @Test
    void aDraftedBossBarIsTheHudsLineForAWhile() {
        previews.show(ALEX, drafted(DocumentSpec.Shape.BOSS_BAR, "b", """
                {"type": "TEXT", "lines": [{"text": {"en": "Drafted"}}, {"text": {"en": "and below"}}]}"""));

        assertEquals(List.of(Pill.of("Drafted and below")), previews.bossBar(alex, Locale.ENGLISH));
        assertEquals(List.of(Previews.BOSS_BAR), delays);

        onMain.forEach(Runnable::run);
        assertEquals(List.of(), previews.bossBar(alex, Locale.ENGLISH));
    }

    @Test
    void aDraftedKickScreenArrivesAsChatLines() {
        previews.show(ALEX, drafted(DocumentSpec.Shape.KICK_SCREEN, "k", TWO_LINES));

        assertEquals("sendMessage", calls.getFirst()[0]);
        assertEquals("Drafted\nand below", plain(calls.getFirst()[1]));
    }

    @Test
    void aDraftedMenuOpensOnTheMainThread() {
        final DocumentSpec<DocumentSpec.None> menu =
                DocumentSpec.menu("test", "m", "Test", DocumentSpec.None.class).titled("M");
        previews.show(
                ALEX, new DocumentPreview("en", new Documents.Packaged(menu.declaration(), Document.decode("""
                                {"parts": [{"type": "TEXT", "lines": [{"style": "TITLE", "text": {"en": "Menu"}}]}]}"""))));

        assertTrue(calls.isEmpty());
        assertEquals(1, onMain.size());
    }

    @Test
    void aServiceThatSetsNoColoursPaintsInTheDefaults() {
        previews.show(ALEX, preview(Display.CHAT));

        assertEquals(Tone.BAD.hex(), colourOfTheValue());
    }
}

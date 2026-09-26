package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.plan.Installation;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.source.Modrinth;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Which of a service's fixed plugins the plugins tab lists as not installed, and which jars it
 * counts as Nordtal's own.
 */
class AbsentFixedPluginsTest {

    private static final Map<String, String> PROJECTS = Map.of(
            Topology.PACKETEVENTS, "HYKaKraK",
            Topology.VOICE_CHAT, "9eGKb6K1",
            Topology.CORE_PROTECT, "Lu3KuzdV");

    private static Topology.Service smp() {
        return Topology.SERVICES.stream()
                .filter(service -> service.name().equals("smp"))
                .findFirst()
                .orElseThrow();
    }

    private static Installation.Jar jar(final String name) {
        return new Installation.Jar(Path.of("/plugins", name), name);
    }

    /** What the smp volume on the dev host holds: everything but CoreProtect. */
    private static final List<Installation.Jar> SMP_TODAY = List.of(
            jar("packetevents-spigot-2.13.0.jar"),
            jar("papermc-display-tags-2.2.0.jar"),
            jar("smp-0.9.5.jar"),
            jar("voicechat-bukkit-2.6.24.jar"));

    @Test
    void namesOnlyTheFixedPluginThatIsNotOnTheDisk() {
        assertEquals(List.of(Topology.CORE_PROTECT), PluginsApi.absentFixed(smp(), SMP_TODAY, Map.of(), PROJECTS));
    }

    @Test
    void recognisesAModrinthJarByItsProjectWhateverItsFileIsCalled() {
        final List<Installation.Jar> renamed = List.of(
                jar("smp-0.9.5.jar"),
                jar("papermc-display-tags-2.2.0.jar"),
                jar("packetevents-spigot-2.13.0.jar"),
                jar("voicechat-bukkit-2.6.24.jar"),
                jar("BlockLog-24.1.jar"));
        final Map<String, Modrinth.Project> identified = Map.of(
                "BlockLog-24.1.jar",
                new Modrinth.Project(
                        "Lu3KuzdV", "coreprotect", "CoreProtect", null, "https://modrinth.com/plugin/coreprotect"));
        assertEquals(List.of(), PluginsApi.absentFixed(smp(), renamed, identified, PROJECTS));
    }

    @Test
    void listsAMissingNordtalJarToo() {
        final List<Installation.Jar> noTags = SMP_TODAY.stream()
                .filter(jar -> !jar.fileName().startsWith("papermc"))
                .toList();
        assertEquals(
                List.of(Topology.DISPLAY_TAGS, Topology.CORE_PROTECT),
                PluginsApi.absentFixed(smp(), noTags, Map.of(), PROJECTS));
    }

    @Test
    void countsTheNameTagForkAsNordtalAndListsItFirst() {
        assertTrue(Topology.isNordtal("papermc-display-tags"));
        assertTrue(Topology.isNordtal("smp"));
        assertFalse(Topology.isNordtal("packetevents-spigot"));
        assertEquals(
                "papermc-display-tags",
                Topology.NORDTAL_PLUGINS.keySet().iterator().next());
        assertEquals("Display Tags", Topology.NORDTAL_PLUGINS.get("papermc-display-tags"));
    }
}

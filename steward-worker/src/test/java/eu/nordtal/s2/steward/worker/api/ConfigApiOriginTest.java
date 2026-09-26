package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.s2.steward.worker.configfile.ConfigLocation;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigApiOriginTest {

    private static Map<String, Object> row(final String service, final String name) {
        return ConfigApi.describe(new ConfigLocation(service, name, Path.of("/configs", service, name), true, true));
    }

    @Test
    void aServicesOwnFileIsNordtalWithNoPluginName() {
        assertEquals("nordtal", row("discord-bot", "bot.yml").get("origin"));
        assertNull(row("discord-bot", "bot.yml").get("plugin"));
    }

    @Test
    void aNordtalPluginsDataFolderIsNordtalAndNamedLikeThePluginsTab() {
        assertEquals("nordtal", row("smp", "smp/config.yml").get("origin"));
        assertEquals("SMP", row("smp", "smp/config.yml").get("plugin"));
        assertEquals("nordtal", row("smp", "DisplayTags/config.yml").get("origin"));
        assertEquals("Display Tags", row("smp", "DisplayTags/config.yml").get("plugin"));
    }

    @Test
    void anyOtherFolderIsThirdPartyAndKeepsItsOwnName() {
        assertEquals("third-party", row("smp", "bStats/config.yml").get("origin"));
        assertEquals("bStats", row("smp", "bStats/config.yml").get("plugin"));
        assertEquals(
                "third-party",
                row("proxy", "voicechat/voicechat-proxy.properties").get("origin"));
    }
}

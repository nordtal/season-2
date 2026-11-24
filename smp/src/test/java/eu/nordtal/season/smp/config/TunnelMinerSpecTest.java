package eu.nordtal.season.smp.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.settings.SettingRefused;
import eu.nordtal.season.spec.Specs;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TunnelMinerSpecTest {

    @Test
    void timeFactorAcceptsBoundsAndRefusesValuesOutsideRange() {
        assertDoesNotThrow(() -> SmpSettings.checkTunnelMiner(spec(0.01)));
        assertDoesNotThrow(() -> SmpSettings.checkTunnelMiner(spec(0.08)));
        assertDoesNotThrow(() -> SmpSettings.checkTunnelMiner(spec(1.0)));
        assertThrows(SettingRefused.class, () -> SmpSettings.checkTunnelMiner(spec(0.005)));
        assertThrows(SettingRefused.class, () -> SmpSettings.checkTunnelMiner(spec(1.01)));
    }

    private static TunnelMinerSpec spec(final double timeFactor) {
        return Specs.createUnsafe(TunnelMinerSpec.class, Map.of("time-factor", timeFactor));
    }
}

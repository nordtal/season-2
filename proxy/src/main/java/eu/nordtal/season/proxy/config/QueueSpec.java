package eu.nordtal.season.proxy.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;

/** How fast the waiting room lets players onto {@code smp}. It applies while the proxy runs. */
@ConfigSpec
public interface QueueSpec {

    /** The seconds between two players let onto {@code smp}. */
    @Order(1)
    @Name("SMP interval (seconds)")
    @Key("smp-interval-seconds")
    @Explain("One player goes onto the SMP per interval; too low and a full return lags the server, too high and the"
            + " last one waits long.")
    default int smpIntervalSeconds() {
        return 3;
    }
}

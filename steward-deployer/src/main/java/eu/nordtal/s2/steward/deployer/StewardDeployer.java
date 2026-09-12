package eu.nordtal.s2.steward.deployer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one service allowed to create containers (§8a, §8b).
 *
 * <p>It carries {@code compose.yml} inside its own image, so "which compose file is live" is a
 * version number rather than a commit hash, and a compose change rides on the one step that has to
 * exist anyway: a new deployer image. <b>It never recreates itself</b> - that is the setup script
 * on the host (§9c).</p>
 */
public final class StewardDeployer {

    private static final Logger log = LoggerFactory.getLogger(StewardDeployer.class);

    private StewardDeployer() {
    }

    public static void main(String[] args) {
        log.info("steward-deployer: no operation implemented yet");
    }
}

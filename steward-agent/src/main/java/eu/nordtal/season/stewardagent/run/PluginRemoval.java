package eu.nordtal.season.stewardagent.run;

import java.util.List;

/** How a plugin removal run takes an added plugin off a server it holds stopped. */
public interface PluginRemoval {

    /** Refuses every removal, for a runner that was given no plugin directory. */
    PluginRemoval NONE = new PluginRemoval() {
        @Override
        public boolean has(final String service, final String artifact) {
            return false;
        }

        @Override
        public List<String> remove(final String service, final String artifact) {
            throw new IllegalStateException("this runner removes no plugins");
        }
    };

    /** Whether an admin added that plugin to that server, so the run has something to remove. */
    boolean has(String service, String artifact);

    /**
     * Deletes the plugin's jar and data folder, then its row.
     *
     * @return the files deleted
     * @throws RuntimeException naming what is wrong; nothing past it was deleted
     */
    List<String> remove(String service, String artifact);
}

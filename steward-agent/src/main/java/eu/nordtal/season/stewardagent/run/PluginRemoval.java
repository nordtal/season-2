package eu.nordtal.season.stewardagent.run;

import java.util.List;

/** Removes an added plugin from a server that the run holds stopped. */
public interface PluginRemoval {

    /** Refuses every removal. */
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

    /** Returns whether an admin added the plugin to the server. */
    boolean has(String service, String artifact);

    /**
     * Deletes the plugin's jar and data folder, then its row.
     *
     * @return the files deleted
     * @throws RuntimeException naming what is wrong; nothing past it was deleted
     */
    List<String> remove(String service, String artifact);
}

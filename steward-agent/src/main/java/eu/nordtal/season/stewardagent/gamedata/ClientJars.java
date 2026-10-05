package eu.nordtal.season.stewardagent.gamedata;

import java.io.IOException;

/** Where a version's client jar comes from; an interface so the loop is tested without Mojang. */
@FunctionalInterface
interface ClientJars {

    /** Fetches {@code minecraftVersion}'s client jar for as long as the returned one stays open. */
    Jar open(String minecraftVersion) throws IOException;

    /** An open client jar; closing it deletes whatever it fetched. */
    interface Jar extends AssetSource, AutoCloseable {

        @Override
        void close();
    }
}

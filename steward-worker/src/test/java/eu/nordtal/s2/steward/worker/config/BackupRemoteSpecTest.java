package eu.nordtal.s2.steward.worker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code backup.remote} - the offsite target steward/95 put on the backup page.
 *
 * <p>The page draws a form out of this section and saves it back through {@code ConfigApi}, so two
 * properties of the schema are load-bearing for something a person can see. The first is that the
 * two credentials are declared {@code @Secret}: a secret is sent as {@code filled: true} with no
 * value, which is the whole reason the page can show that a key is set without the key being in the
 * browser. {@code ConfigEntry.isSecretKey} would catch both names by its own heuristic as well -
 * the point of the annotation is that the masking does not depend on what the key is called, and
 * that is exactly what steward/70 wrote down for the bot's credentials.</p>
 *
 * <p>The second is that the endpoint is empty by default. A deployment with no offsite copy must
 * look like one, rather than like a target somebody has to check before believing the archives are
 * only on this disk.</p>
 *
 * <p>Built straight from the interface with {@link SchemaWriter#build} rather than through
 * {@link Configs}: whether the annotation is there is a property of the interface and needs no file
 * on disk to demonstrate.</p>
 */
class BackupRemoteSpecTest {

    private static SchemaNode remote() {
        final SchemaNode backup =
                SchemaWriter.build(StewardSpec.class).children().get("backup");
        assertNotNull(backup, "StewardSpec's schema has no 'backup' entry at all");
        final SchemaNode remote = backup.children().get("remote");
        assertNotNull(remote, "BackupSpec's schema has no 'remote' section");
        return remote;
    }

    @Test
    @DisplayName("both credentials of backup.remote are declared @Secret")
    void theKeysAreSecret() {
        final SchemaNode accessKey = remote().children().get("access-key");
        assertNotNull(accessKey, "RemoteSpec's schema has no 'access-key' entry");
        assertTrue(accessKey.secret(), "backup.remote.access-key must never be sent to a browser");

        final SchemaNode secretKey = remote().children().get("secret-key");
        assertNotNull(secretKey, "RemoteSpec's schema has no 'secret-key' entry");
        assertTrue(secretKey.secret(), "backup.remote.secret-key must never be sent to a browser");
    }

    @Test
    @DisplayName("the endpoint, the bucket and the prefix are not secret - a target is not a credential")
    void theTargetItselfIsReadable() {
        for (final String key : new String[] {"endpoint", "bucket", "prefix"}) {
            final SchemaNode node = remote().children().get(key);
            assertNotNull(node, "RemoteSpec's schema has no '" + key + "' entry");
            assertFalse(
                    node.secret(),
                    key + " is where the copy goes, not a credential - masking it"
                            + " would hide the one thing an operator needs to read back");
        }
    }

    @Test
    @DisplayName("a fresh file has no offsite target, and says so by being empty")
    void freshIsEmpty() {
        final StewardSpec.BackupSpec.RemoteSpec remote = new StewardSpec.BackupSpec.RemoteSpec() {};
        assertEquals("", remote.endpoint(), "a deployment with no Storage Box must look like one");
        assertEquals("", remote.bucket());
        assertEquals("", remote.prefix());
        assertEquals("", remote.accessKey());
        assertEquals("", remote.secretKey());
    }
}
